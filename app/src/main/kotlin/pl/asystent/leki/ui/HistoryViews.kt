package pl.asystent.leki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.core.HistoryView
import pl.asystent.leki.core.groupByPeriod
import pl.asystent.leki.core.longDay
import pl.asystent.leki.core.periodLabel
import java.time.LocalDate

/** Ile ostatnich dni na liście „Dni” jest rozwiniętych; starsze trafiają do zwiniętych kart tygodniowych. */
private const val RECENT_DAYS = 7
private val VIEW_LABELS = listOf("Dni", "Tygodnie", "Miesiące")
private val VIEWS = listOf(HistoryView.Days, HistoryView.Weeks, HistoryView.Months)

/** Stan widoku historii (zakładka z listą pomiarów albo zapisów EKG): sposób grupowania, filtr i ręcznie zmienione karty. Przeżywa obrót ekranu. */
class HistoryState(view: Int = 0, flagOnly: Boolean = false, toggled: List<String> = emptyList()) {
    var view by mutableStateOf(view)
    var flagOnly by mutableStateOf(flagOnly)
    internal val toggled: SnapshotStateList<String> = toggled.toMutableStateList()

    /** Karta jest rozwinięta, gdy jej stan domyślny został zmieniony dotknięciem albo jest domyślnie otwarta. Klucz zawiera widok, więc po przełączeniu wracają ustawienia domyślne. */
    internal fun isOpen(key: String, byDefault: Boolean) = ("$view:$key" in toggled) != byDefault
    internal fun toggle(key: String) { val k = "$view:$key"; if (!toggled.remove(k)) toggled.add(k) }

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Saver: Saver<HistoryState, Any> = Saver(
            save = { listOf(it.view, it.flagOnly, ArrayList(it.toggled)) },
            restore = { v -> val l = v as List<Any>; HistoryState(l[0] as Int, l[1] as Boolean, l[2] as List<String>) },
        )
    }
}

@Composable
fun rememberHistoryState(): HistoryState = rememberSaveable(saver = HistoryState.Saver) { HistoryState() }

/** Nagłówek listy: tytuł, przełącznik Dni | Tygodnie | Miesiące i filtr pozycji z oznaczeniem. */
@Composable
private fun HistoryBar(title: String, hist: HistoryState, filterOn: String, filterOff: String) {
    Column(Modifier.padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.weight(1f))
            LinkButton(if (hist.flagOnly) filterOff else filterOn, { hist.flagOnly = !hist.flagOnly })
        }
        SegRow(VIEW_LABELS, hist.view, { hist.view = it }, Modifier.padding(top = 4.dp))
    }
}

/** Zwijana karta okresu: nazwa, jednowierszowe podsumowanie i (po rozwinięciu) pozycje. */
@Composable
private fun PeriodCard(label: String, summary: String, warn: Boolean, expanded: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    LekiCard(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (expanded) "rozwinięte" else "zwinięte" }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                Text(summary, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = if (warn) pal.warn else pal.ink3), modifier = Modifier.padding(top = 2.dp))
            }
            PhIcon(Ph.CaretRight, Modifier.rotate(if (expanded) 90f else 0f))
        }
        if (expanded) { Divider(); content() }
    }
}

/**
 * Długa lista pozycji (pomiary ciśnienia, zapisy EKG), od najnowszej, podzielona na okresy.
 * Widok „Dni”: ostatnie 7 dni rozwinięte dzień po dniu, starsze w zwiniętych kartach tygodniowych;
 * „Tygodnie” i „Miesiące”: same karty okresów z podsumowaniem (najnowszy tydzień rozwinięty).
 * [rows] rysuje pozycje jednego dnia (z kreskami między nimi), [summary] daje podsumowanie okresu i znacznik, czy są w nim pozycje do uwagi.
 */
fun <T> LazyListScope.historyList(
    prefix: String, title: String, items: List<T>, hist: HistoryState, today: LocalDate, dateOf: (T) -> LocalDate,
    filterOn: String, filterOff: String, emptyText: String,
    summary: (List<T>) -> Pair<String, Boolean>, rows: @Composable ColumnScope.(List<T>) -> Unit,
) {
    val view = VIEWS[hist.view.coerceIn(0, VIEWS.size - 1)]
    item(key = "$prefix-bar") { HistoryBar(title, hist, filterOn, filterOff) }
    if (items.isEmpty()) { item(key = "$prefix-empty") { Hint(emptyText, Modifier.padding(top = 10.dp)) }; return }

    fun LazyListScope.periods(list: List<T>, v: HistoryView, openFirst: Boolean) {
        groupByPeriod(list, v, dateOf).forEachIndexed { i, g ->
            item(key = "$prefix-${v.ordinal}-${g.key}") {
                val (text, warn) = summary(g.items)
                val key = "${v.ordinal}-${g.key}"
                PeriodCard(periodLabel(g.start, g.end, v), text, warn, hist.isOpen(key, openFirst && i == 0), { hist.toggle(key) }) {
                    groupByPeriod(g.items, HistoryView.Days, dateOf).forEachIndexed { j, d ->
                        if (j > 0) Divider()
                        Text(longDay(d.start), style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
                        rows(d.items)
                    }
                }
            }
        }
    }

    when (view) {
        HistoryView.Days -> {
            val from = today.minusDays((RECENT_DAYS - 1).toLong())
            val recent = items.filter { dateOf(it) >= from }
            val older = items.filter { dateOf(it) < from }
            groupByPeriod(recent, HistoryView.Days, dateOf).forEach { g ->
                item(key = "$prefix-d-${g.key}") {
                    Column(Modifier.padding(top = 14.dp)) {
                        Text(longDay(g.start), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                        LekiCard { rows(g.items) }
                    }
                }
            }
            if (older.isNotEmpty()) {
                item(key = "$prefix-older") { Overline("Starsze, według tygodni", Modifier.padding(top = 20.dp)) }
                periods(older, HistoryView.Weeks, openFirst = false)
            }
        }
        HistoryView.Weeks -> periods(items, HistoryView.Weeks, openFirst = true)
        HistoryView.Months -> periods(items, HistoryView.Months, openFirst = false)
    }
}

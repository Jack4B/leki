package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Lab
import pl.asystent.leki.core.LabDir
import pl.asystent.leki.core.LabFilter
import pl.asystent.leki.core.LabPreset
import pl.asystent.leki.core.LabRow
import pl.asystent.leki.core.LabWindow
import pl.asystent.leki.core.LabsModel
import pl.asystent.leki.core.LabSort
import pl.asystent.leki.core.Marker
import pl.asystent.leki.core.labChange
import pl.asystent.leki.core.labCompare
import pl.asystent.leki.core.labDays
import pl.asystent.leki.core.labDeltaText
import pl.asystent.leki.core.labFlag
import pl.asystent.leki.core.labGroupSummary
import pl.asystent.leki.core.labPresetDates
import pl.asystent.leki.core.labTrend
import pl.asystent.leki.core.labWindow
import pl.asystent.leki.core.labsOf
import pl.asystent.leki.core.lnum
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.rangeText
import pl.asystent.leki.core.shortDate
import java.time.LocalDate

/** Stan widoków Badań (sortowanie, dzień, porównanie, filtry, okno miniwykresów); przeżywa obrót ekranu. */
class LabsUi(
    sort: Int = 0, asOf: String = "", exact: Boolean = false, compare: Boolean = false, cmp: String = "", win: Int = 2,
    fOut: Boolean = false, fChanged: Boolean = false, fPinned: Boolean = false, mapOut: Boolean = false, ghost: Boolean = false, dwin: Int = 2, cmpChanged: Boolean = false,
) {
    var sort by mutableStateOf(sort)
    var asOf by mutableStateOf(asOf)
    var exact by mutableStateOf(exact)
    var compare by mutableStateOf(compare)
    var cmp by mutableStateOf(cmp)
    /** Okno miniwykresów na liście (indeks [LabWindow]); domyślnie wszystko. */
    var win by mutableStateOf(win)
    var fOut by mutableStateOf(fOut)
    var fChanged by mutableStateOf(fChanged)
    var fPinned by mutableStateOf(fPinned)
    var mapOut by mutableStateOf(mapOut)
    /** „Nałóż rok temu” na wykresie parametru. */
    var ghost by mutableStateOf(ghost)
    /** Okno wykresu w szczegółach parametru (indeks [LabWindow]). */
    var dwin by mutableStateOf(dwin)
    var cmpChanged by mutableStateOf(cmpChanged)

    val filter: LabFilter get() = LabFilter(fOut, fChanged, fPinned)
    val sortMode: LabSort get() = LabSort.values()[sort.coerceIn(0, 2)]

    /** Wybrane do porównania dni (do 4, od najstarszego); domyślnie dwa najnowsze, a dni, których wyniki zniknęły, są pomijane. */
    fun cmpDates(days: List<LocalDate>): List<LocalDate> =
        cmp.split(',').mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.filter { it in days }.ifEmpty { days.take(2) }.take(4).sorted()

    fun toggleCmp(d: LocalDate, days: List<LocalDate>) {
        val cur = cmpDates(days)
        cmp = (if (d in cur) cur - d else if (cur.size < 4) cur + d else cur).sorted().joinToString(",")
    }

    companion object {
        val Saver: Saver<LabsUi, Any> = Saver(
            save = { listOf(it.sort, it.asOf, it.exact, it.compare, it.cmp, it.win, it.fOut, it.fChanged, it.fPinned, it.mapOut, it.ghost, it.dwin, it.cmpChanged) },
            restore = { v ->
                val l = v as List<*>
                LabsUi(l[0] as Int, l[1] as String, l[2] as Boolean, l[3] as Boolean, l[4] as String, l[5] as Int, l[6] as Boolean, l[7] as Boolean, l[8] as Boolean, l[9] as Boolean, l[10] as Boolean, l[11] as Int, l[12] as Boolean)
            },
        )
    }
}

@Composable
fun rememberLabsUi(): LabsUi = rememberSaveable(saver = LabsUi.Saver) { LabsUi() }

/** Czy wyniki obejmują więcej niż rok; dopiero wtedy pojawiają się wybory okna czasu i porównania z rokiem wcześniej. */
fun AppState.labsSpanOverYear(): Boolean {
    val d = labs.map { it.date }
    return d.isNotEmpty() && java.time.temporal.ChronoUnit.DAYS.between(d.min(), d.max()) > 366
}

private fun labWindows() = LabWindow.values().map { it.label }

private fun arrow(d: Double?) = if (d == null || d == 0.0) "" else if (d > 0) "▲ " else "▼ "

/** Lista parametrów (Badania → Przegląd): statystyki, sortowanie, filtry, wybór dnia lub porównanie dni, a pod spodem parametry z poprzednim wynikiem, zmianą i tendencją. */
@OptIn(ExperimentalLayoutApi::class)
fun LazyListScope.overview(
    s: AppState, model: LabsModel, ui: LabsUi, days: List<Pair<LocalDate, Int>>, asOf: String, today: LocalDate,
    showNone: Boolean, toggleNone: () -> Unit, onOpen: (String) -> Unit, onAddTo: (String) -> Unit,
) {
    val dayList = days.map { it.first }
    val compare = ui.compare && days.size >= 2
    item {
        val lastDate = model.withResults.mapNotNull { it.last?.date }.maxOrNull()
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("${model.withResults.size}", "parametrów z wynikami", Modifier.weight(1f))
            Stat("${model.withResults.count { labFlag(it.last!!, it.marker).isNotEmpty() }}", "poza zakresem", Modifier.weight(1f), valueColor = if (model.withResults.any { labFlag(it.last!!, it.marker).isNotEmpty() }) pal.warn else pal.ink)
            Stat(lastDate?.let { shortDate(it) } ?: "brak", "ostatnie badanie", Modifier.weight(1f))
        }
        if (days.size >= 2) Row(Modifier.padding(top = 14.dp)) { ToggleChip("Porównaj dni", compare) { ui.compare = !ui.compare } }
        if (days.isNotEmpty() && !compare) {
            SegRow(listOf("Grupami", "A–Z", "Najnowsze"), ui.sort, { ui.sort = it }, Modifier.padding(top = 14.dp))
            // filtry pojawiają się, gdy mają sens: „zmienione” od drugiego wyniku któregokolwiek parametru, „przypięte” po przypięciu pierwszego
            val canChange = s.markers.any { s.labsOf(it.id).size >= 2 }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Poza zakresem", ui.fOut) { ui.fOut = !ui.fOut }
                if (canChange) ToggleChip("Zmienione od poprzedniego", ui.fChanged) { ui.fChanged = !ui.fChanged }
                if (s.markers.any { it.pin }) ToggleChip("Przypięte", ui.fPinned) { ui.fPinned = !ui.fPinned }
            }
            if (s.labsSpanOverYear()) {
                Text("Okres miniwykresów", style = TextStyle(fontSize = 12.sp, color = pal.ink3), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                SegRow(labWindows(), ui.win, { ui.win = it })
            }
            // wybór daty: stan na dany dzień (ostatni wynik z tego dnia lub wcześniejszy) albo tylko wyniki z tego dnia
            DropdownField(
                "Wyniki z dnia", listOf("" to "Najnowsze (każdy parametr z ostatniej daty)") + days.map { (d, n) -> d.toString() to plDate(d) + " · " + n + " " + plN(n, "wynik", "wyniki", "wyników") },
                asOf, { ui.asOf = it }, Modifier.padding(top = 14.dp),
            )
            if (asOf.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp)) { ToggleChip("Tylko wyniki z tego dnia", ui.exact) { ui.exact = !ui.exact } }
                Hint(if (ui.exact) "Pokazane są tylko parametry zbadane tego dnia." else "Każdy parametr pokazuje ostatni wynik z tego dnia lub wcześniejszy; wykres kończy się na tym dniu.", Modifier.padding(top = 6.dp))
            }
        }
        val shown = model.pinned.size + model.out.size + model.groups.sumOf { it.items.size } + model.flat.size
        if (compare) { /* tabela poniżej */ }
        else if (model.withResults.isEmpty()) EmptyBox(if (days.isEmpty()) "Brak wyników. Zeskanuj wyniki ze zdjęcia lub pliku PDF albo dodaj pierwszy wynik ręcznie." else "Brak wyników z tego dnia.", Modifier.padding(top = 16.dp))
        else if (shown == 0 && ui.filter.active) EmptyBox("Żaden parametr nie spełnia wybranych filtrów.", Modifier.padding(top = 16.dp))
    }
    if (compare) { labCompareSection(s, ui, days, dayList); return }
    val win = LabWindow.values()[ui.win.coerceIn(0, 2)]
    fun LazyListScope.card(key: String, title: String?, rows: List<LabRow>) {
        if (rows.isEmpty()) return
        item(key = key) {
            Column(Modifier.padding(top = 18.dp)) {
                if (title != null) Text(title, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                LekiCard { rows.forEachIndexed { i, x -> if (i > 0) Divider(); LabRowItem(x, showDate = true, spark = true, win = win, today = today, extra = true) { onOpen(x.marker.id) } } }
            }
        }
    }
    if (model.sort == LabSort.Groups) {
        card("pinned", "Przypięte", model.pinned)
        card("out", "Ostatni wynik poza zakresem", model.out)
        model.groups.forEach { g -> card("grp" + g.name, g.name, g.items) }
    } else card("flat", null, model.flat)
    if (model.none.isNotEmpty() && !ui.filter.active) item {
        Column(Modifier.padding(top = 18.dp)) {
            LinkButton((if (showNone) "▾ " else "▸ ") + "Parametry bez wyników (${model.none.size})", toggleNone)
            if (showNone) LekiCard {
                model.none.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onAddTo(m.id) }.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                            Hint(listOfNotNull(m.unit.takeIf { it.isNotEmpty() }, rangeText(m).takeIf { it.isNotEmpty() }?.let { "zakres $it" }).joinToString(", "))
                        }
                        Text("Dodaj", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
                    }
                }
            }
        }
    }
}

/** Porównanie wyników z kilku dni: gotowe skróty albo wybór do 4 dni; parametry w wierszach (grupami), dni w kolumnach od najstarszego, zmiana pod nazwą. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.labCompareSection(s: AppState, ui: LabsUi, days: List<Pair<LocalDate, Int>>, dayList: List<LocalDate>) {
    val selected = ui.cmpDates(dayList)
    item(key = "cmp-pick") {
        var all by remember { mutableStateOf(false) }
        Column(Modifier.padding(top = 14.dp)) {
            val presets = LabPreset.values().map { it to labPresetDates(dayList, it) }.filter { it.second.isNotEmpty() }
            if (presets.isNotEmpty()) {
                Text("Skróty", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    presets.forEach { (p, ds) -> ToggleChip(p.label, selected == ds) { ui.cmp = ds.joinToString(",") } }
                }
            }
            Text("Dni do porównania (do 4)", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (all) days else days.take(12)).forEach { (d, n) -> ToggleChip(plDate(d) + " (" + n + ")", d in selected) { ui.toggleCmp(d, dayList) } }
            }
            if (days.size > 12) LinkButton(if (all) "Pokaż mniej dni" else "Pokaż starsze dni (${days.size - 12})", { all = !all })
            Hint("W nawiasie liczba wyników z tego dnia. Kolumny idą od najstarszego dnia do najnowszego.", Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 10.dp)) { ToggleChip("Tylko zmienione", ui.cmpChanged) { ui.cmpChanged = !ui.cmpChanged } }
        }
    }
    item(key = "cmp-table") {
        val rows = remember(s.labs, s.markers, selected, ui.cmpChanged) { s.labCompare(selected).filter { !ui.cmpChanged || it.significant } }
        if (rows.isEmpty()) { EmptyBox(if (ui.cmpChanged) "Żaden parametr nie zmienił się istotnie między wybranymi dniami." else "Brak wyników z wybranych dni.", Modifier.padding(top = 16.dp)); return@item }
        LekiCard(Modifier.padding(top = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Parametr", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3), modifier = Modifier.weight(1.5f))
                selected.forEach { d -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { FitText(shortDate(d), TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3), wrap = false, minSp = 9f) } }
            }
            var group = ""
            rows.forEach { r ->
                if (r.group != group) {
                    group = r.group
                    Divider()
                    Text(group.uppercase(), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3, letterSpacing = 0.8.sp), modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp))
                } else Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.5f)) {
                        Text(r.marker.name, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        if (r.marker.unit.isNotEmpty()) Hint(r.marker.unit)
                        if (r.delta != null) Text("zmiana " + arrow(r.delta) + labDeltaText(r.delta, r.pct), style = TextStyle(fontSize = 11.5.sp, fontWeight = if (r.significant) FontWeight.SemiBold else FontWeight.Normal, color = if (r.significant) pal.ink2 else pal.ink3))
                    }
                    r.labs.forEach { l ->
                        val out = l != null && labFlag(l, r.marker).isNotEmpty()
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (l == null) Text("–", style = TextStyle(fontSize = 15.sp, color = pal.ink3))
                            else FitText(lnum(l.value), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = if (out) pal.warn else pal.ink), wrap = false, minSp = 10f)
                        }
                    }
                }
            }
        }
        Hint("Pomarańczowa wartość jest poza zakresem odniesienia. Kreska oznacza, że tego dnia parametru nie badano. „Zmiana” to różnica między pierwszym a ostatnim z wybranych dni, w którym parametr badano; pogrubiona jest zmiana istotna (co najmniej 10% zakresu odniesienia lub poprzedniej wartości). Ocenia lekarz.", Modifier.padding(top = 8.dp))
    }
}

private val NAME_W = 116.dp
private val CELL_W = 62.dp
private val ROW_H = 52.dp
private val HEAD_H = 46.dp
private val GRP_H = 30.dp

/** Mapa wyników: parametry w wierszach, wszystkie dni badań w kolumnach (przewijane w bok, nazwy zostają po lewej); kolor pola pokazuje ocenę względem zakresu. */
@Composable
fun LabMap(s: AppState, ui: LabsUi, onOpen: (String) -> Unit) {
    val days = remember(s.labs) { s.labDays().map { it.first }.sorted() }
    if (days.isEmpty()) { EmptyBox("Brak wyników. Zeskanuj wyniki ze zdjęcia lub pliku PDF albo dodaj pierwszy wynik ręcznie.", Modifier.padding(top = 16.dp)); return }
    val rows = remember(s.labs, s.markers, ui.mapOut) {
        s.labCompare(days).filter { r -> !ui.mapOut || r.labs.any { it != null && labFlag(it, r.marker).isNotEmpty() } }
    }
    val items = remember(rows) {
        val l = ArrayList<Pair<String?, pl.asystent.leki.core.LabCompareRow?>>(); var g = ""
        rows.forEach { if (it.group != g) { g = it.group; l.add(g to null) }; l.add(null to it) }
        l
    }
    val scroll = rememberScrollState()
    // otwarcie na najnowszych badaniach (po prawej)
    LaunchedEffect(days.size) { withTimeoutOrNull(1500) { snapshotFlow { scroll.maxValue }.first { it > 0 } }?.let { scroll.scrollTo(it) } }
    Column(Modifier.padding(top = 16.dp)) {
        Row { ToggleChip("Tylko poza zakresem", ui.mapOut) { ui.mapOut = !ui.mapOut } }
        if (rows.isEmpty()) { EmptyBox("Żaden parametr nie ma wyniku poza zakresem.", Modifier.padding(top = 12.dp)); return@Column }
        LekiCard(Modifier.padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(NAME_W)) {
                    Box(Modifier.height(HEAD_H).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) { Text("Parametr", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3)) }
                    items.forEach { (g, r) ->
                        if (g != null) Box(Modifier.height(GRP_H).padding(start = 12.dp), contentAlignment = Alignment.BottomStart) {
                            Text(g.uppercase(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = pal.ink3, letterSpacing = 0.6.sp))
                        } else if (r != null) Box(Modifier.height(ROW_H).fillMaxWidth().clickable(role = Role.Button) { onOpen(r.marker.id) }.padding(start = 12.dp, end = 4.dp), contentAlignment = Alignment.CenterStart) {
                            Text(r.marker.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        }
                    }
                }
                Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                    Column {
                        Row(Modifier.height(HEAD_H)) {
                            days.forEach { d ->
                                Column(Modifier.width(CELL_W).height(HEAD_H), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(shortDate(d), style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2))
                                    Text(d.year.toString(), style = TextStyle(fontSize = 10.5.sp, color = pal.ink3))
                                }
                            }
                        }
                        items.forEach { (g, r) ->
                            if (g != null) Box(Modifier.height(GRP_H)) else if (r != null) Row(Modifier.height(ROW_H)) {
                                r.labs.forEach { l ->
                                    val f = if (l != null) labFlag(l, r.marker) else ""
                                    val hasRange = l != null && (l.lo != null || l.hi != null || r.marker.lo != null || r.marker.hi != null)
                                    val bg = if (f.isNotEmpty()) pal.warnBg else if (hasRange) pal.accentSoft.copy(alpha = 0.6f) else pal.surface
                                    Box(
                                        Modifier.width(CELL_W).height(ROW_H).padding(3.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(role = Role.Button) { onOpen(r.marker.id) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (l == null) Text("–", style = TextStyle(fontSize = 14.sp, color = pal.ink3))
                                        else FitText(
                                            (if (f.startsWith("powyżej")) "▲" else if (f.startsWith("poniżej")) "▼" else "") + lnum(l.value),
                                            TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = if (f.isNotEmpty()) pal.warn else pal.ink), wrap = false, minSp = 9f,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Hint("Pomarańczowe pola są poza zakresem odniesienia (▲ powyżej, ▼ poniżej), zielone w zakresie, bez koloru: parametr bez zakresu. Kreska: tego dnia nie badano. Przesuń tabelę w bok, żeby zobaczyć starsze badania; dotknij parametru, żeby otworzyć jego historię i wykres. Ocenia lekarz.", Modifier.padding(top = 8.dp))
    }
}

/** Wyniki pogrupowane według dni badań (dni, tygodnie, miesiące, lata); w obrębie dnia w kolejności parametrów z ustawień. */
fun LazyListScope.labsByDate(s: AppState, hist: HistoryState, today: LocalDate, onEdit: (Lab) -> Unit) {
    if (s.labs.isEmpty()) { item { EmptyBox("Brak wyników.", Modifier.padding(top = 16.dp)) }; return }
    val all = s.labs.filter { s.marker(it.marker) != null }
        .sortedWith(compareByDescending<Lab> { it.date }.thenBy { l -> s.markers.indexOfFirst { it.id == l.marker } })
    val items = if (hist.flagOnly) all.filter { l -> s.marker(l.marker)?.let { labFlag(l, it).isNotEmpty() } == true } else all
    historyList(
        "lab", "Wyniki według dat", items, hist, today, { it.date }, "Tylko poza zakresem", "Pokaż wszystkie", "Brak wyników poza zakresem odniesienia.",
        { labGroupSummary(it, s.markers) }, 90,
    ) { rows ->
        rows.forEachIndexed { i, l ->
            if (i > 0) Divider()
            s.marker(l.marker)?.let { m -> LabRowItem(LabRow(m, listOf(l)), showDate = false, spark = false) { onEdit(l) } }
        }
    }
}

/** Wyniki jednego parametru: wartość, ocena względem zakresu (własnego lub parametru), data i notatka; dotknięcie otwiera edycję. */
@Composable
fun LabResultRows(rows: List<Lab>, m: Marker, onEdit: (Lab) -> Unit) {
    rows.forEachIndexed { i, l ->
        if (i > 0) Divider()
        val fl = labFlag(l, m)
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(l) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lnum(l.value), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink))
                    Hint(m.unit)
                    if (fl.isNotEmpty()) Text(fl, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                }
                val own = if (l.lo != null || l.hi != null) "zakres z badania ${rangeText(l, m)}" else ""
                Hint(listOf(plDate(l.date), own, l.note).filter { it.isNotEmpty() }.joinToString(", "))
            }
            Text("Edytuj", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
        }
    }
}

/** Wiersz parametru: nazwa, data i zakres, opcjonalnie poprzedni wynik ze zmianą i tendencją ([extra]), miniwykres z wybranego okna i ostatnia wartość. */
@Composable
fun LabRowItem(
    x: LabRow, showDate: Boolean, spark: Boolean, win: LabWindow = LabWindow.All, today: LocalDate = LocalDate.now(), extra: Boolean = false, onClick: () -> Unit,
) {
    val l = x.last ?: return
    val m = x.marker
    val f = labFlag(l, m)
    val sub = listOfNotNull(if (showDate) shortDate(l.date) else null, rangeText(l, m).takeIf { it.isNotEmpty() }?.let { "zakres $it" }).joinToString(" · ")
    val change = if (extra) labChange(m, x.list) else null
    val trend = if (extra) labTrend(m, x.list) else null
    val note = listOfNotNull(
        change?.let { "poprzednio ${lnum(it.prev.value)}, " + arrow(it.delta) + labDeltaText(it.delta, it.pct) },
        trend?.takeIf { it.dir == LabDir.Up || it.dir == LabDir.Down || it.dir == LabDir.Flat }?.text,
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            if (sub.isNotEmpty()) Hint(sub)
            if (note.isNotEmpty()) Text(note, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (change?.significant == true) FontWeight.SemiBold else FontWeight.Normal, color = if (change?.significant == true) pal.ink2 else pal.ink3), modifier = Modifier.padding(top = 2.dp))
        }
        if (spark) Sparkline(labWindow(x.list, win, today).map { it.value }, f.isNotEmpty())
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 4.dp)) {
            Text(lnum(l.value), style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = if (f.isNotEmpty()) pal.warn else pal.ink))
            Hint(m.unit)
            if (f.isNotEmpty()) Text(f, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
        }
    }
}

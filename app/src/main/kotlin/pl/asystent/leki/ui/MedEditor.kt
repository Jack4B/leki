package pl.asystent.leki.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Defaults
import pl.asystent.leki.core.MedEdit
import pl.asystent.leki.core.MedSave
import pl.asystent.leki.core.Pl
import pl.asystent.leki.core.deleteMed
import pl.asystent.leki.core.deletePhase
import pl.asystent.leki.core.fmtNum
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.num
import pl.asystent.leki.core.parseNum
import pl.asystent.leki.core.phaseAt
import pl.asystent.leki.core.phaseText
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.MedDraft
import pl.asystent.leki.core.saveMed
import pl.asystent.leki.core.stockLeft
import pl.asystent.leki.core.shortDate
import java.time.LocalDate

/** Edytor leku: nowy, edycja istniejącego albo zamiana na inny lek ([replaces]). Zmiana dawkowania dodaje nową fazę od wybranego dnia, a historia zostaje. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedEditor(s: AppState, store: Store, medId: String?, replaces: String?, onClose: () -> Unit, onReplace: (String) -> Unit, prefill: MedDraft? = null) {
    val today = LocalDate.now()
    val toast = LocalToast.current
    val m = medId?.let { s.med(it) }
    val oldMed = replaces?.let { s.med(it) }
    val init = remember(medId, replaces) {
        when {
            m != null -> { val p = m.phaseAt(today) ?: m.phases.first(); Triple(if (m.phases.first().from > today) m.phases.first().from else today, p.doses, p.dow) }
            oldMed != null -> { val p = oldMed.phaseAt(today) ?: oldMed.phases.first(); Triple(today, p.doses, p.dow) }
            else -> Triple(today, emptyMap(), Defaults.allDow)
        }
    }
    // prefill: propozycja ze zdjęcia opakowania (tylko dla nowego leku); użytkownik sprawdza i poprawia wszystko przed zapisem
    val pre = if (m == null && oldMed == null) prefill else null
    var name by remember(medId, replaces) { mutableStateOf(m?.name ?: pre?.fullName() ?: "") }
    var substance by remember(medId, replaces) { mutableStateOf(m?.substance ?: pre?.substance ?: "") }
    var note by remember(medId, replaces) { mutableStateOf(m?.note ?: "") }
    var unit by remember(medId, replaces) { mutableStateOf(m?.unit ?: oldMed?.unit ?: pre?.unit ?: "tabl.") }
    var timer by remember(medId, replaces) { mutableStateOf((m?.timerMin ?: 0).toString()) }
    var mmol by remember(medId, replaces) { mutableStateOf(if ((m?.mmol ?: 0.0) > 0) num(m!!.mmol) else "") }
    val stockInit = remember(medId, replaces) { m?.let { s.stockLeft(it, today) }?.let { num(it) } ?: "" }
    var stock by remember(medId, replaces) { mutableStateOf(if (stockInit.isEmpty()) pre?.count?.let { num(it) } ?: "" else stockInit) }
    var showRaw by remember { mutableStateOf(false) }
    var end by remember(medId, replaces) { mutableStateOf(m?.end) }
    var from by remember(medId, replaces) { mutableStateOf(init.first) }
    val doses = remember(medId, replaces) { mutableStateMapOf<String, Double>().also { it.putAll(init.second) } }
    val dow = remember(medId, replaces) { mutableStateListOf<Int>().also { it.addAll(init.third) } }
    var err by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun edit(endOverride: LocalDate?, useEnd: Boolean) = MedEdit(
        id = medId, name = name, substance = substance, note = note, unit = unit, timerMin = timer.toIntOrNull() ?: 0, mmol = parseNum(mmol) ?: 0.0,
        end = if (useEnd) endOverride else end, from = from, doses = doses.toMap(), dow = dow.toList(), replaces = replaces,
        stockLeft = parseNum(stock)?.takeIf { it >= 0 }, stockEdited = stock.trim() != stockInit, today = today,
    )

    fun save(endOverride: LocalDate? = null, useEnd: Boolean = false, message: String? = null) {
        when (val r = s.saveMed(edit(endOverride, useEnd))) {
            is MedSave.Error -> err = r.message
            is MedSave.Ok -> { store.replace(r.state); toast(message ?: r.message); onClose() }
        }
    }

    val title = when { oldMed != null -> "Nowy lek w miejsce: ${oldMed.name}"; m == null -> "Nowy lek"; else -> "Edycja leku" }
    FullScreenDialog(title, onClose) {
        if (oldMed != null) BodyText("Po zapisaniu ${oldMed.name} zostanie zakończony w dniu przed datą początku nowego leku. Jego historia i raporty zostają.")
        if (m != null) {
            val mEnd = m.end
            LekiCard(Modifier) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mEnd != null) {
                        Text("Lek zakończony", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        BodyText("Ostatni dzień przyjmowania: ${plDate(mEnd)}. Od następnego dnia lek nie pojawia się w planie, a historia zostaje.")
                        LekiButton("Wznów lek", { save(null, true, "Wznowiono lek ${name.trim()}.") }, Modifier.fillMaxWidth())
                    } else {
                        Text("Zakończyć przyjmowanie tego leku?", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        BodyText("Zakończenie nie usuwa historii. Wybierz ostatni dzień przyjmowania.")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val y = today.minusDays(1)
                            LekiButton("Do wczoraj ${shortDate(y)}", { save(y, true, "Zakończono ${name.trim()}. Ostatni dzień: ${plDate(y)}.") }, Modifier.weight(1f))
                            LekiButton("Do dziś ${shortDate(today)}", { save(today, true, "Zakończono ${name.trim()}. Ostatni dzień: ${plDate(today)}.") }, Modifier.weight(1f))
                        }
                        LekiButton("Zamień na inny lek", { onReplace(m.id) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (pre != null) {
            Notice("Dane odczytane ze zdjęcia opakowania. Sprawdź nazwę, moc i liczbę sztuk, a potem ustaw dawkowanie według zaleceń lekarza.")
            if (pre.names.size > 1) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Inne napisy z opakowania (dotknij, żeby użyć jako nazwy)", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pre.names.forEach { n -> val full = pre.fullName(n); ToggleChip(n, name == full) { name = full; err = "" } }
                }
            }
        }
        if (pre != null && pre.lines.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinkButton((if (showRaw) "▾ " else "▸ ") + "Pokaż cały rozpoznany tekst (${pre.lines.size})", { showRaw = !showRaw })
            if (showRaw) {
                Hint("Dotknij napisu, żeby użyć go jako nazwy. Tak wygląda tekst odczytany z opakowania, od góry.")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pre.lines.forEach { l -> ToggleChip(l, name == l) { name = l; err = "" } }
                }
            }
        }
        LekiField("Nazwa", name, { name = it; err = "" }, placeholder = "np. nazwa z opakowania")
        LekiField("Substancja (opcjonalnie)", substance, { substance = it }, placeholder = "np. substancja czynna")
        LekiField("Wskazówka przy dawce (opcjonalnie)", note, { note = it }, placeholder = "np. po posiłku, popić wodą")

        LekiCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Dawkowanie", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                Column {
                    DateField("Obowiązuje od", from, { from = it })
                    Hint(if (m == null) "Pierwszy dzień przyjmowania." else "Nowe dawkowanie zaczyna obowiązywać od tego dnia. Wcześniejsze dni zostają bez zmian.", Modifier.padding(top = 6.dp))
                }
                s.sortedSlots().forEach { sl ->
                    val v = doses[sl.id] ?: 0.0
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(sl.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                            Text(sl.time.hhmm(), style = TextStyle(fontSize = 12.5.sp, fontFamily = Mono, color = pal.ink3))
                        }
                        Row(
                            Modifier.clip(CtlShape).border(1.dp, pal.line, CtlShape), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StepBtn("−") { val n = Math.round((v - 0.5) * 2) / 2.0; if (n > 0) doses[sl.id] = n else doses.remove(sl.id); err = "" }
                            Box(Modifier.size(width = 54.dp, height = 46.dp), contentAlignment = Alignment.Center) { Text(num(v), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink)) }
                            StepBtn("+") { doses[sl.id] = minOf(20.0, Math.round((v + 0.5) * 2) / 2.0); err = "" }
                        }
                    }
                }
                Hint("Dawka w ${unit.ifEmpty { "tabl." }} na jedno przyjęcie. 0 oznacza, że w tej porze leku nie bierzesz.")
                Column {
                    Text("Dni tygodnia", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Defaults.dowOrder.forEach { d -> Pill(Pl.dowShort[d], d in dow) { if (d in dow) dow.remove(d) else dow.add(d); err = "" } }
                    }
                }
            }
        }
        if (m != null) {
            val cur = m.phaseAt(today)
            LekiCard {
                Text("Historia dawkowania", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(14.dp))
                m.phases.forEach { p ->
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("od ${plDate(p.from)}" + if (p == cur) " (obecne)" else "", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink))
                            Text(s.phaseText(m, p), style = TextStyle(fontSize = 12.5.sp, color = pal.ink2))
                        }
                        if (m.phases.size > 1) LekiButton("Usuń", { store.update { it.deletePhase(m.id, p.from) }; toast("Usunięto zmianę dawkowania") }, danger = true, small = true)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiField("Jednostka", unit, { unit = it }, Modifier.weight(1f))
            LekiField("Stoper po dawce, min", timer, { timer = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
        }
        Hint("Stoper (0 = bez) odlicza czas od odhaczenia dawki, np. 30 minut, w czasie których nie wolno się kłaść.")
        Column {
            LekiField("Potas w jednej tabletce, mmol (opcjonalnie)", mmol, { mmol = it }, keyboard = KeyboardType.Decimal, mono = true, placeholder = "np. 10")
            Hint("Jeśli podasz, dzień pokaże sumę potasu z leków.", Modifier.padding(top = 6.dp))
        }
        Column {
            LekiField("Ile zostało w opakowaniu, $unit (opcjonalnie)", stock, { stock = it }, keyboard = KeyboardType.Decimal, mono = true, placeholder = "np. 28")
            Hint("Aplikacja odlicza dawki, które odhaczasz, i ostrzeże, gdy zapasu zostanie na kilka dni (próg w Ustawieniach → Przypomnienia). Puste pole wyłącza śledzenie.", Modifier.padding(top = 6.dp))
        }
        Column {
            DateField("Ostatni dzień przyjmowania (opcjonalnie)", end, { end = it; err = "" }, placeholder = "bez końca")
            Hint("Po tym dniu lek znika z listy dnia i trafia do zakończonych. Historia zostaje.", Modifier.padding(top = 6.dp))
            if (end != null) LinkButton("Usuń datę końca", { end = null })
        }
        BodyText("Dawkowanie zmieniaj zgodnie z zaleceniem lekarza.")
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onClose, Modifier.weight(1f))
            LekiButton("Zapisz", { save() }, Modifier.weight(1f), primary = true)
        }
        if (m != null) LinkButton("Usuń lek razem z historią", { confirmDelete = true }, danger = true)
    }
    if (confirmDelete && m != null) ConfirmDialog(
        "Usunąć lek ${m.name}?", "Lek zniknie z planu, a odhaczenia tego leku zostaną usunięte z historii. Jeśli chcesz tylko przestać go brać, zamiast tego ustaw ostatni dzień przyjmowania.",
        "Usuń lek", onConfirm = { store.update { it.deleteMed(m.id) }; toast("Usunięto lek"); onClose() }, onDismiss = { confirmDelete = false }, danger = true,
    )
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 48.dp, height = 46.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
    }
}

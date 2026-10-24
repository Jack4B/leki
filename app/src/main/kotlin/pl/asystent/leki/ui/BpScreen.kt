package pl.asystent.leki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Bp
import pl.asystent.leki.core.addBp
import pl.asystent.leki.core.Trend
import pl.asystent.leki.core.bpFlag
import pl.asystent.leki.core.bpGroupSummary
import pl.asystent.leki.core.bpOrder
import pl.asystent.leki.core.bpStats
import pl.asystent.leki.core.deleteBp
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.longDay
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.thresholdText
import java.time.LocalDate
import java.time.LocalTime

private val RANGES = listOf(7, 30, 90, 365, 0)
private val RANGE_LABELS = listOf("7 dni", "30 dni", "90 dni", "Rok", "Wszystko")

@Composable
fun BpScreen(s: AppState, store: Store, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), initialRange: Int = 7, hist: HistoryState = rememberHistoryState()) {
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    val undo = LocalUndo.current
    var sys by rememberSaveable { mutableStateOf("") }
    var dia by rememberSaveable { mutableStateOf("") }
    var pulse by rememberSaveable { mutableStateOf("") }
    var bpNote by rememberSaveable { mutableStateOf("") }
    // puste = „teraz”: data, godzina i pora pomiaru podążają za zegarem, dopóki użytkownik ich nie zmieni (formularz bywa otwarty od rana do wieczora)
    var dateStr by rememberSaveable { mutableStateOf("") }
    var timeStr by rememberSaveable { mutableStateOf("") }
    var ctxSel by rememberSaveable { mutableStateOf(-1) }
    val dateVal = if (dateStr.isEmpty()) today else LocalDate.parse(dateStr)
    val timeVal = if (timeStr.isEmpty()) now.toLocalTime().withSecond(0).withNano(0) else LocalTime.parse(timeStr)
    val ctxIdx = if (ctxSel >= 0) ctxSel else if (now.hour < 11) 0 else if (now.hour >= 17) 1 else 2
    var err by remember { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(initialRange) }
    val ctxNames = listOf("rano", "wieczór", "inne")

    val list = s.bp.sortedWith(bpOrder).let { all -> if (range == 0) all else all.filter { it.date >= today.minusDays((range - 1).toLong()) } }
    val st = bpStats(list)
    // historia pokazuje wszystkie pomiary (zakres 7/30/90 dni dotyczy podsumowania i wykresu); długą listę dzieli widok Dni | Tygodnie | Miesiące
    val all = s.bp.sortedWith(bpOrder).reversed()

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Text("Nowy pomiar", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            BodyText("Mierz siedząc, po 5 minutach odpoczynku, na tym samym ramieniu.", Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LekiField("Skurczowe", sys, { sys = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
                    LekiField("Rozkurczowe", dia, { dia = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
                    LekiField("Tętno", pulse, { pulse = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField("Data", dateVal, { dateStr = it.toString() }, Modifier.weight(1f))
                    TimeField("Godzina", timeVal, { timeStr = it.toString() }, Modifier.weight(1f))
                }
                Column {
                    Text("Pora pomiaru", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    SegRow(listOf("Rano", "Wieczór", "Inne"), ctxIdx, { ctxSel = it })
                }
                LekiField("Notatka (opcjonalnie)", bpNote, { bpNote = it }, placeholder = "np. po spacerze, brak skurczów")
                ErrorText(err)
                LekiButton("Zapisz pomiar", {
                    val sy = sys.toIntOrNull(); val di = dia.toIntOrNull(); val pu = pulse.toIntOrNull()?.takeIf { it > 0 }
                    err = when {
                        sy == null || di == null || sy == 0 || di == 0 -> "Wpisz ciśnienie skurczowe i rozkurczowe."
                        sy < 60 || sy > 260 || di < 30 || di > 160 -> "Wartości poza zakresem (skurczowe 60-260, rozkurczowe 30-160)."
                        sy <= di -> "Skurczowe musi być większe od rozkurczowego."
                        pu != null && (pu < 30 || pu > 220) -> "Tętno powinno mieścić się w zakresie 30-220."
                        else -> ""
                    }
                    if (err.isEmpty()) {
                        store.update { it.addBp(Bp(newId(), dateVal, timeVal, sy!!, di!!, pu, ctxNames[ctxIdx], bpNote.trim())) }
                        toast("Zapisano pomiar $sy/$di")
                        sys = ""; dia = ""; pulse = ""; bpNote = ""
                        dateStr = ""; timeStr = ""; ctxSel = -1
                    }
                }, Modifier.fillMaxWidth(), primary = true, icon = Ph.Plus)
            }
        }
        item {
            Column(Modifier.padding(top = 24.dp)) {
                if (s.bp.isNotEmpty()) SegRow(RANGE_LABELS, RANGES.indexOf(range).coerceAtLeast(0), { range = RANGES[it] }, Modifier.padding(bottom = 14.dp))
                if (list.isNotEmpty()) {
                    Text("Podsumowanie", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("${st.s}/${st.d}", "średnie ciśnienie", Modifier.weight(1f))
                        Stat("${st.n}", "pomiarów", Modifier.weight(1f))
                        Stat(st.p?.toString() ?: "brak", "średnie tętno", Modifier.weight(1f))
                    }
                    val sb = StringBuilder()
                    if (st.rano.n > 0) sb.append("Rano: ${st.rano.s}/${st.rano.d} (${st.rano.n}). ")
                    if (st.wieczor.n > 0) sb.append("Wieczór: ${st.wieczor.s}/${st.wieczor.d} (${st.wieczor.n}). ")
                    sb.append("Oznaczenia: ${s.settings.thresholdText()}. Ocenia lekarz.")
                    Hint(sb.toString(), Modifier.padding(top = 10.dp))
                } else if (s.bp.isNotEmpty()) Hint("Brak pomiarów w tym okresie.")
            }
        }
        if (list.isNotEmpty()) item {
            val from = if (range == 0) list.minOf { it.date } else today.minusDays((range - 1).toLong())
            Column(Modifier.padding(top = 24.dp)) {
                Text("Przebieg", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                Row(Modifier.padding(top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Hint("● skurczowe"); Hint("○ rozkurczowe"); Hint("mmHg")
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val plotW = maxWidth.value - 34f
                    val geo = remember(list, from, today, plotW, s.settings.hiSys, s.settings.hiDia) { Trend.build(list, from, today, plotW, s.settings.hiSys, s.settings.hiDia) }
                    if (geo != null) {
                        Column {
                            BpTrendView(geo, "Wykres ciśnienia skurczowego i rozkurczowego w czasie, przewijany w poziomie")
                            val long = geo.width > plotW + 1f
                            Hint(
                                (if (long) "Przesuń wykres palcem w bok, aby zobaczyć wcześniejsze dni. " else "") +
                                    (if (geo.detailed) "Każdy punkt to jeden pomiar. " else "Słupek to zakres pomiarów danego dnia (min–max), linia to średnia dnia. ") +
                                    "Przerywane linie: progi ${s.settings.hiSys}/${s.settings.hiDia}; czerwone punkty to pomiary na progu lub powyżej.",
                                Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        if (s.bp.isEmpty()) item { EmptyBox("Brak pomiarów. Wpisz pierwszy wynik w formularzu powyżej.", Modifier.padding(top = 24.dp)) }
        if (s.bp.isNotEmpty()) historyList(
            "bp", "Zapisane pomiary", all.let { a -> if (hist.flagOnly) a.filter { s.settings.bpFlag(it) == "podwyższone" } else a },
            hist, today, { it.date }, "Tylko podwyższone", "Pokaż wszystkie", "Brak pomiarów na progu podwyższonego ciśnienia lub powyżej.",
            { bpGroupSummary(it, s.settings) },
        ) { rows ->
            BpRows(rows, s, onDelete = { id ->
                s.bp.firstOrNull { it.id == id }?.let { b ->
                    store.update { it.deleteBp(id) }
                    undo("Usunięto pomiar ${b.sys}/${b.dia}") { store.update { it.addBp(b) } }
                }
            })
        }
    }
}

/** Pomiary jednego dnia: godzina, wynik, tętno z notatką i przycisk usuwania; kreski między pozycjami. */
@Composable
private fun BpRows(rows: List<Bp>, s: AppState, onDelete: (String) -> Unit) {
    rows.forEachIndexed { i, r ->
        if (i > 0) Divider()
        val f = s.settings.bpFlag(r)
        val meta = listOfNotNull(r.pulse?.let { "tętno $it" }, r.ctx, r.note.ifEmpty { null }).joinToString(", ")
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(r.time.hhmm(), style = TextStyle(fontSize = 13.sp, fontFamily = Mono, color = pal.ink3), modifier = Modifier.padding(end = 2.dp))
            Column(Modifier.weight(1f)) {
                Text("${r.sys}/${r.dia}", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink))
                Text(buildString { append(meta); if (f.isNotEmpty()) { if (meta.isNotEmpty()) append(' '); append(f) } },
                    style = TextStyle(fontSize = 12.5.sp, color = if (f.isNotEmpty()) pal.warn else pal.ink3))
            }
            LekiButton("Usuń", { onDelete(r.id) }, danger = true, small = true)
        }
    }
}

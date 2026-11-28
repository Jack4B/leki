package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Bp
import pl.asystent.leki.core.addBp
import pl.asystent.leki.core.Trend
import pl.asystent.leki.core.BP_ALERT_HIGH
import pl.asystent.leki.core.BP_ALERT_LOW
import pl.asystent.leki.core.bpFlag
import pl.asystent.leki.core.bpFlagText
import pl.asystent.leki.core.bpLiveStatus
import pl.asystent.leki.core.bpVeryHigh
import pl.asystent.leki.core.bpVeryLow
import pl.asystent.leki.core.bpGroupSummary
import pl.asystent.leki.core.bpOrder
import pl.asystent.leki.core.bpStats
import pl.asystent.leki.core.deleteBp
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.saveBp
import pl.asystent.leki.core.shortDate
import pl.asystent.leki.core.thresholdText
import java.time.LocalDate
import java.time.LocalTime

private val RANGES = listOf(7, 30, 90, 365, 0)
private val RANGE_LABELS = listOf("7 dni", "30 dni", "90 dni", "Rok", "Wszystko")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpScreen(
    s: AppState,
    store: Store,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    initialRange: Int = 7,
    hist: HistoryState = rememberHistoryState(),
    openFormRequest: Boolean = false,
    onFormRequestHandled: () -> Unit = {},
) {
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()

    var formOpen by rememberSaveable { mutableStateOf(s.bp.isEmpty() || openFormRequest) }
    var editId by rememberSaveable { mutableStateOf("") }
    var avgMode by rememberSaveable { mutableStateOf(false) }
    var sys by rememberSaveable { mutableStateOf("") }
    var dia by rememberSaveable { mutableStateOf("") }
    var pulse by rememberSaveable { mutableStateOf("") }
    var sys2 by rememberSaveable { mutableStateOf("") }
    var dia2 by rememberSaveable { mutableStateOf("") }
    var bpNote by rememberSaveable { mutableStateOf("") }
    // puste = „teraz”: data, godzina i pora pomiaru podążają za zegarem, dopóki użytkownik ich nie zmieni
    var dateStr by rememberSaveable { mutableStateOf("") }
    var timeStr by rememberSaveable { mutableStateOf("") }
    var ctxSel by rememberSaveable { mutableStateOf(-1) }

    val dateVal = if (dateStr.isEmpty()) today else LocalDate.parse(dateStr)
    val timeVal = if (timeStr.isEmpty()) now.toLocalTime().withSecond(0).withNano(0) else LocalTime.parse(timeStr)
    val ctxIdx = if (ctxSel >= 0) ctxSel else if (now.hour < 11) 0 else if (now.hour >= 17) 1 else 2
    var err by remember { mutableStateOf("") }
    var range by rememberSaveable { mutableStateOf(initialRange) }
    val ctxNames = listOf("rano", "wieczór", "inne")

    val diaFocus = remember { FocusRequester() }
    val pulseFocus = remember { FocusRequester() }

    LaunchedEffect(openFormRequest) {
        if (openFormRequest) {
            editId = ""
            formOpen = true
            onFormRequestHandled()
        }
    }

    fun clearForm() {
        editId = ""; avgMode = false
        sys = ""; dia = ""; pulse = ""; sys2 = ""; dia2 = ""; bpNote = ""
        dateStr = ""; timeStr = ""; ctxSel = -1; err = ""
    }

    fun startEdit(b: Bp) {
        editId = b.id
        avgMode = false
        sys = b.sys.toString()
        dia = b.dia.toString()
        pulse = b.pulse?.toString().orEmpty()
        bpNote = b.note
        dateStr = b.date.toString()
        timeStr = b.time.toString()
        ctxSel = ctxNames.indexOf(b.ctx).coerceAtLeast(0)
        err = ""
        formOpen = true
        scope.launch { listState.animateScrollToItem(0) }
    }

    val latest = remember(s.bp) { s.bp.maxWithOrNull(compareBy({ it.date }, { it.time })) }
    val list = s.bp.sortedWith(bpOrder).let { all -> if (range == 0) all else all.filter { it.date >= today.minusDays((range - 1).toLong()) } }
    val st = bpStats(list)
    val all = s.bp.sortedWith(bpOrder).reversed()

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            // Stała wskazówka bezpieczeństwa po ostatnim pomiarze z dziś lub wczoraj (niezależna od zwinięcia formularza)
            latest?.takeIf { it.date >= today.minusDays(1) }?.let { last ->
                if (bpVeryHigh(last)) Notice("Ostatni pomiar ${last.sys}/${last.dia}. $BP_ALERT_HIGH", Modifier.padding(bottom = 12.dp))
                else if (bpVeryLow(last)) Notice("Ostatni pomiar ${last.sys}/${last.dia}. $BP_ALERT_LOW", Modifier.padding(bottom = 12.dp))
            }

            if (s.bp.isEmpty() || formOpen) {
                val effSys = run {
                    val a = sys.toIntOrNull(); val b = sys2.toIntOrNull()
                    if (avgMode && a != null && b != null) Math.round((a + b) / 2.0).toInt() else a
                }
                val effDia = run {
                    val a = dia.toIntOrNull(); val b = dia2.toIntOrNull()
                    if (avgMode && a != null && b != null) Math.round((a + b) / 2.0).toInt() else a
                }
                LekiCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (editId.isNotEmpty()) "Edycja pomiaru" else "Nowy pomiar ciśnienia", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = pal.ink))
                            if (s.bp.isNotEmpty()) LinkButton("Zamknij", { clearForm(); formOpen = false })
                        }
                        BodyText("Mierz siedząc, po 5 minutach odpoczynku, na tym samym ramieniu.")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LekiField(
                                "Skurczowe", sys,
                                { v ->
                                    val clean = v.filter { c -> c.isDigit() }.take(3)
                                    sys = clean
                                    if (clean.length == 3 && (clean.toIntOrNull() ?: 0) >= 100) runCatching { diaFocus.requestFocus() }
                                },
                                Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true,
                                imeAction = ImeAction.Next, keyboardActions = KeyboardActions(onNext = { runCatching { diaFocus.requestFocus() } }),
                            )
                            LekiField(
                                "Rozkurczowe", dia,
                                { v ->
                                    val clean = v.filter { c -> c.isDigit() }.take(3)
                                    dia = clean
                                    if (clean.length >= 2 && (clean.toIntOrNull() ?: 0) in 45..159 && clean.length == 2 && clean.first() in '5'..'9') {
                                        runCatching { pulseFocus.requestFocus() }
                                    } else if (clean.length == 3) {
                                        runCatching { pulseFocus.requestFocus() }
                                    }
                                },
                                Modifier.weight(1f).focusRequester(diaFocus), keyboard = KeyboardType.Number, mono = true,
                                imeAction = ImeAction.Next, keyboardActions = KeyboardActions(onNext = { runCatching { pulseFocus.requestFocus() } }),
                            )
                            LekiField(
                                "Tętno", pulse,
                                { pulse = it.filter { c -> c.isDigit() }.take(3) },
                                Modifier.weight(1f).focusRequester(pulseFocus), keyboard = KeyboardType.Number, mono = true,
                                imeAction = ImeAction.Done,
                            )
                        }
                        if (editId.isEmpty()) {
                            ToggleChip("Uśrednij 2 kolejne pomiary", avgMode) { avgMode = !avgMode }
                            if (avgMode) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    LekiField("2. skurczowe", sys2, { sys2 = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
                                    LekiField("2. rozkurczowe", dia2, { dia2 = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), keyboard = KeyboardType.Number, mono = true)
                                }
                                if (effSys != null && effDia != null && sys2.isNotEmpty() && dia2.isNotEmpty()) {
                                    Hint("Średnia do zapisu: $effSys/$effDia mmHg")
                                }
                            }
                        }
                        s.settings.bpLiveStatus(effSys, effDia)?.let { (kind, msg) ->
                            StatusPill(msg, kind = kind)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DateField("Data", dateVal, { dateStr = it.toString() }, Modifier.weight(1f))
                            TimeField("Godzina", timeVal, { timeStr = it.toString() }, Modifier.weight(1f))
                        }
                        Column {
                            Text("Pora pomiaru", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                            SegRow(listOf("Rano", "Wieczór", "Inne"), ctxIdx, { ctxSel = it })
                        }
                        LekiField("Notatka (opcjonalnie)", bpNote, { bpNote = it }, placeholder = "np. po spacerze, przed lekiem")
                        ErrorText(err)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (editId.isNotEmpty()) LekiButton("Anuluj", { clearForm(); formOpen = false }, Modifier.weight(1f))
                            LekiButton(
                                if (editId.isNotEmpty()) "Zapisz zmiany" else "Zapisz pomiar",
                                {
                                    val sy = effSys; val di = effDia; val pu = pulse.toIntOrNull()?.takeIf { it > 0 }
                                    err = when {
                                        sy == null || di == null || sy == 0 || di == 0 -> "Wpisz ciśnienie skurczowe i rozkurczowe."
                                        avgMode && (sys2.toIntOrNull() == null || dia2.toIntOrNull() == null) -> "Wpisz drugi pomiar do uśrednienia albo wyłącz tryb 2 pomiarów."
                                        sy < 60 || sy > 260 || di < 30 || di > 160 -> "Wartości poza zakresem aplikacji (skurczowe 60-260, rozkurczowe 30-160). Sprawdź wynik i zmierz ponownie; przy objawach zadzwoń pod 112."
                                        sy <= di -> "Skurczowe musi być większe od rozkurczowego."
                                        pu != null && (pu < 20 || pu > 250) -> "Tętno powinno mieścić się w zakresie 20-250."
                                        else -> ""
                                    }
                                    if (err.isEmpty()) {
                                        val entry = Bp(editId.ifEmpty { newId() }, dateVal, timeVal, sy!!, di!!, pu, ctxNames[ctxIdx], bpNote.trim())
                                        val wasEdit = editId.isNotEmpty()
                                        store.update { if (wasEdit) it.saveBp(entry) else it.addBp(entry) }
                                        toast(
                                            if (bpVeryHigh(entry)) "Zapisano pomiar $sy/$di. Bardzo wysokie ciśnienie: zobacz wskazówkę u góry ekranu."
                                            else if (wasEdit) "Zaktualizowano pomiar $sy/$di"
                                            else "Zapisano pomiar $sy/$di",
                                        )
                                        clearForm()
                                        formOpen = false
                                    }
                                },
                                Modifier.weight(1.4f), primary = true, icon = if (editId.isNotEmpty()) Ph.Check else Ph.Plus,
                            )
                        }
                    }
                }
            } else if (latest != null) {
                val latestFlag = s.settings.bpFlagText(latest)
                LekiCard {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Ostatni · " + (if (latest.date == today) "dziś" else if (latest.date == today.minusDays(1)) "wczoraj" else shortDate(latest.date)) + ", " + latest.time.hhmm(),
                                style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = TNUM, color = pal.ink3),
                            )
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                                Text("${latest.sys}/${latest.dia}", style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM, color = if (latestFlag.isNotEmpty()) pal.warn else pal.ink))
                                latest.pulse?.let { Text("♥ $it/min", style = TextStyle(fontSize = 13.5.sp, fontFeatureSettings = TNUM, color = pal.ink2), modifier = Modifier.padding(bottom = 3.dp)) }
                            }
                            if (latestFlag.isNotEmpty()) Text(latestFlag, style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                        }
                        LekiButton("Dodaj pomiar", { clearForm(); formOpen = true }, primary = true, icon = Ph.Plus)
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 16.dp)) {
                if (s.bp.isNotEmpty()) SegRow(RANGE_LABELS, RANGES.indexOf(range).coerceAtLeast(0), { range = RANGES[it] }, Modifier.padding(bottom = 14.dp))
                if (list.isNotEmpty()) {
                    LekiCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Podsumowanie okresu", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.ink))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Stat("${st.s}/${st.d}", "średnie ciśnienie", Modifier.weight(1f))
                                Stat("${st.n}", "pomiarów", Modifier.weight(1f))
                                Stat(st.p?.toString() ?: "brak", "średnie tętno", Modifier.weight(1f))
                            }
                            if (st.rano.n > 0 || st.wieczor.n > 0) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (st.rano.n > 0) StatusPill("Rano: ${st.rano.s}/${st.rano.d} (${st.rano.n})")
                                    if (st.wieczor.n > 0) StatusPill("Wieczór: ${st.wieczor.s}/${st.wieczor.d} (${st.wieczor.n})")
                                }
                            }
                            Hint("Progi oznaczeń: ${s.settings.thresholdText()}. Ocenia lekarz.")
                        }
                    }
                } else if (s.bp.isNotEmpty()) Hint("Brak pomiarów w tym okresie.")
            }
        }

        if (list.isNotEmpty()) item {
            val from = if (range == 0) list.minOf { it.date } else today.minusDays((range - 1).toLong())
            LekiCard(Modifier.padding(top = 14.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Przebieg w czasie", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.ink))
                    BpLegend(s.settings.hiSys, s.settings.hiDia, Modifier.padding(top = 8.dp, bottom = 8.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val plotW = maxWidth.value - 34f
                        val geo = remember(list, from, today, plotW, s.settings.hiSys, s.settings.hiDia) { Trend.build(list, from, today, plotW, s.settings.hiSys, s.settings.hiDia) }
                        if (geo != null) {
                            Column {
                                BpTrendView(geo, "Wykres ciśnienia skurczowego i rozkurczowego w czasie, przewijany w poziomie")
                                val long = geo.width > plotW + 1f
                                Hint(
                                    (if (long) "Przesuń wykres palcem w bok, aby zobaczyć wcześniejsze dni. " else "") +
                                        (if (geo.detailed) "Każdy punkt to jeden pomiar. " else "Linia to średnia dnia, jasna kreska to zakres dnia (min–max). ") +
                                        "Wartości progów są wyróżnione na osi." +
                                        (if (geo.gaps.isNotEmpty()) " Szare pola to przerwy od ${Trend.GAP_DAYS} dni bez pomiaru." else ""),
                                    Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (s.bp.isEmpty()) item { EmptyBox("Brak pomiarów. Wpisz pierwszy wynik w formularzu powyżej.", Modifier.padding(top = 20.dp)) }
        if (s.bp.isNotEmpty()) historyList(
            "bp", "Zapisane pomiary", all.let { a -> if (hist.flagOnly) a.filter { s.settings.bpFlag(it) == "podwyższone" } else a },
            hist, today, { it.date }, "Tylko podwyższone", "Pokaż wszystkie", "Brak pomiarów na progu podwyższonego ciśnienia lub powyżej.",
            { bpGroupSummary(it, s.settings) },
        ) { rows ->
            BpRows(
                rows, s,
                onEdit = ::startEdit,
                onDelete = { id ->
                    s.bp.firstOrNull { it.id == id }?.let { b ->
                        store.update { it.deleteBp(id) }
                        undo("Usunięto pomiar ${b.sys}/${b.dia}") { store.update { it.addBp(b) } }
                    }
                },
            )
        }
    }
}

/** Pomiary jednego dnia: pora i godzina, wynik z tętnem, dotknięcie lub przycisk Edytuj otwiera korektę, Usuń kasuje z opcją Cofnij. */
@Composable
private fun BpRows(rows: List<Bp>, s: AppState, onEdit: (Bp) -> Unit, onDelete: (String) -> Unit) {
    rows.forEachIndexed { i, r ->
        if (i > 0) Divider()
        val f = s.settings.bpFlagText(r)
        val meta = listOfNotNull(r.pulse?.let { "♥ $it/min" }, r.note.ifEmpty { null }).joinToString(" · ")
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(r) }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (f.isNotEmpty()) pal.warnBg else pal.sunken).padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(r.time.hhmm(), style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, color = if (f.isNotEmpty()) pal.warn else pal.ink2))
                Text(r.ctx, style = TextStyle(fontSize = 11.sp, color = if (f.isNotEmpty()) pal.warn else pal.ink3))
            }
            Column(Modifier.weight(1f)) {
                Text("${r.sys}/${r.dia}", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM, color = if (f.isNotEmpty()) pal.warn else pal.ink))
                val subText = listOfNotNull(meta.takeIf { it.isNotEmpty() }, f.takeIf { it.isNotEmpty() }).joinToString(" · ")
                if (subText.isNotEmpty()) Text(subText, style = TextStyle(fontSize = 12.5.sp, color = if (f.isNotEmpty()) pal.warn else pal.ink3))
            }
            LekiButton("Edytuj", { onEdit(r) }, small = true, tonal = true)
            LekiButton("Usuń", { onDelete(r.id) }, danger = true, small = true)
        }
    }
}

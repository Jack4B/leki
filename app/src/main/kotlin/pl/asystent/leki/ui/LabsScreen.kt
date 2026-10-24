package pl.asystent.leki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Lab
import pl.asystent.leki.core.LabRow
import pl.asystent.leki.core.Marker
import pl.asystent.leki.core.deleteLab
import pl.asystent.leki.core.deleteMarker
import pl.asystent.leki.core.labChart
import pl.asystent.leki.core.labFlag
import pl.asystent.leki.core.labGroupSummary
import pl.asystent.leki.core.labCompare
import pl.asystent.leki.core.LabWindow
import pl.asystent.leki.core.labDays
import pl.asystent.leki.core.labDeltaText
import pl.asystent.leki.core.labGhost
import pl.asystent.leki.core.labTrend
import pl.asystent.leki.core.labWindow
import pl.asystent.leki.core.labYearAgo
import pl.asystent.leki.core.labsModel
import pl.asystent.leki.core.labsOf
import pl.asystent.leki.core.lnum
import pl.asystent.leki.core.markerUsed
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.parseNum
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.deleteEcg
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.rangeText
import pl.asystent.leki.core.saveEcg
import pl.asystent.leki.core.saveLab
import pl.asystent.leki.core.saveMarker
import pl.asystent.leki.core.shortDate
import java.time.LocalDate

@Composable
fun LabsScreen(s: AppState, store: Store, onImport: () -> Unit, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), initialMode: Int = 0, ui: LabsUi = rememberLabsUi()) {
    val toast = LocalToast.current
    val undo = LocalUndo.current
    var openId by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var labEdit by remember { mutableStateOf<Pair<Lab?, String>?>(null) } // (wynik do edycji lub null, id parametru)
    var markerEdit by remember { mutableStateOf<Marker?>(null) }
    var markerNew by remember { mutableStateOf(false) }
    var showNone by rememberSaveable { mutableStateOf(false) }
    var markersOpen by rememberSaveable { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var ecgDraft by remember { mutableStateOf<pl.asystent.leki.core.Ecg?>(null) }
    var ecgOpenId by rememberSaveable { mutableStateOf("") }
    val ecgImport = rememberEcgImporter { ecgDraft = it }
    val ecgHist = rememberHistoryState()
    val labHist = rememberHistoryState()
    val markerHist = rememberHistoryState()

    // data, której wyniki już nie istnieją (np. po usunięciu), wraca do „najnowszych”
    val labDays = s.labDays()
    val asOf = runCatching { LocalDate.parse(ui.asOf) }.getOrNull()?.takeIf { d -> labDays.any { it.first == d } }
    val today = LocalDate.now()
    val model = s.labsModel(ui.sortMode, asOf, ui.exact && asOf != null, ui.filter)
    fun openLab(l: Lab?, id: String) { if (s.markers.isEmpty()) toast("Najpierw dodaj parametr badania w Ustawieniach.") else labEdit = l to id }
    if (openId.isNotEmpty() && s.marker(openId) == null) openId = ""
    val open = s.marker(openId)
    // „wstecz” w szczegółach parametru wraca do listy badań, a nie do innej zakładki
    androidx.activity.compose.BackHandler(enabled = open != null) { openId = "" }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        if (open == null) {
            item {
                Text("Badania", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
                // przyciski dotyczą wyników badań; w zakładce EKG ma być jeden, własny przycisk dodawania
                if (mode != 3) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    LekiButton("Skanuj wyniki", { scan = true }, Modifier.weight(1.4f), primary = true, icon = Ph.Scan)
                    LekiButton("Dodaj ręcznie", { openLab(null, model.order.firstOrNull() ?: s.markers.firstOrNull()?.id.orEmpty()) }, Modifier.weight(1f), tonal = true, icon = Ph.Plus)
                }
                SegRow(listOf("Przegląd", "Mapa", "Daty", "EKG"), mode, { mode = it })
            }
            if (mode == 3) ecgSection(s, ecgHist, today, onAdd = { ecgImport() }, onOpen = { ecgOpenId = it }, onDelete = { id -> s.ecg(id)?.let { e -> store.update { it.deleteEcg(id) }; undo("Usunięto EKG z ${plDate(e.date)}") { store.update { it.saveEcg(e) } } } })
            else if (mode == 2) labsByDate(s, labHist, today) { openLab(it, it.marker) }
            else if (mode == 1) item { LabMap(s, ui) { openId = it } }
            else overview(s, model, ui, labDays, asOf?.toString().orEmpty(), today, showNone, { showNone = !showNone }, { openId = it }, { openLab(null, it) })
            if (mode != 3) item {
                Column(Modifier.padding(top = 18.dp)) {
                    LinkButton("Parametry badań: nazwy, jednostki, zakresy ›", { markersOpen = true })
                    LinkButton("Dołącz wyniki z pliku (np. przygotowanego z Diagnostyki)", onImport)
                }
            }
        } else {
            val list = s.labsOf(open.id)
            val ix = model.order.indexOf(open.id)
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    LinkButton("‹ Wszystkie badania", { openId = "" })
                    if (ix >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LekiButton("‹", { openId = model.order[ix - 1] }, enabled = ix > 0, small = true)
                        Hint("${ix + 1} z ${model.order.size}")
                        LekiButton("›", { openId = model.order[ix + 1] }, enabled = ix < model.order.size - 1, small = true)
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(open.name, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.weight(1f))
                        LekiButton("Dodaj wynik", { openLab(null, open.id) }, primary = true, small = true)
                    }
                    if (list.isEmpty()) {
                        BodyText("Brak wyników. Dodaj pierwszy wynik badania" + (rangeText(open).takeIf { it.isNotEmpty() }?.let { ", zakres odniesienia $it ${open.unit}" } ?: "") + ".")
                    } else {
                        val last = list.last(); val prev = list.getOrNull(list.size - 2); val f = labFlag(last, open)
                        val delta = prev?.let { last.value - it.value }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stat(lnum(last.value), "ostatni wynik" + (if (open.unit.isNotEmpty()) ", ${open.unit}" else "") + ", " + shortDate(last.date), Modifier.weight(1f))
                            Stat(if (delta == null) "brak" else (if (delta > 0) "+" else if (delta < 0) "−" else "") + lnum(Math.abs(delta)), if (prev != null) "zmiana od " + shortDate(prev.date) else "zmiana", Modifier.weight(1f))
                            Stat(rangeText(last, open).ifEmpty { "brak" }, "zakres odniesienia", Modifier.weight(1f))
                        }
                        if (f.isNotEmpty()) Text("Ostatni wynik jest $f. Ocenia lekarz.", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 10.dp))
                        // tendencja (od trzech wyników) i porównanie z wynikiem sprzed roku: pojawiają się wraz z przyrostem danych
                        val trend = labTrend(open, list)
                        val yAgo = labYearAgo(list)
                        if (trend != null) Hint("Tendencja: ${trend.text}. Opis dotyczy liczb, nie oceny zdrowotnej.", Modifier.padding(top = 8.dp))
                        if (yAgo != null) Hint("Rok temu (${plDate(yAgo.date)}): ${lnum(yAgo.value)}, teraz ${lnum(last.value)} (${labDeltaText(last.value - yAgo.value, if (yAgo.value != 0.0) (last.value - yAgo.value) / Math.abs(yAgo.value) else null)}).", Modifier.padding(top = 4.dp))
                        val spanYear = s.labsOf(open.id).let { l -> l.size >= 2 && java.time.temporal.ChronoUnit.DAYS.between(l.first().date, l.last().date) > 366 }
                        if (spanYear) SegRow(LabWindow.values().map { it.label }, ui.dwin, { ui.dwin = it }, Modifier.padding(top = 14.dp))
                        val shown = labWindow(list, LabWindow.values()[ui.dwin.coerceIn(0, 2)], today).ifEmpty { list }
                        val ghost = if (shown.size >= 2) labGhost(list, shown.first().date, shown.last().date) else emptyList()
                        if (ghost.isNotEmpty()) Row(Modifier.padding(top = 10.dp)) { ToggleChip("Nałóż rok temu", ui.ghost) { ui.ghost = !ui.ghost } }
                        labChart(shown, open, if (ui.ghost) ghost else emptyList())?.let { LabChartView(it, "Wykres: ${open.name} w czasie, z zakresem odniesienia", Modifier.padding(top = 14.dp)) }
                        if (ghost.isNotEmpty() && ui.ghost) Hint("Szara przerywana linia z pustymi kółkami: wyniki sprzed roku, przesunięte o rok, żeby porównać ten sam okres.", Modifier.padding(top = 6.dp))
                        if (rangeText(open).isNotEmpty()) Hint("Zielone tło to zakres odniesienia parametru. Jest orientacyjny: laboratoria podają własne zakresy (wynik może mieć własny zakres z badania).", Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        LinkButton("Zmień nazwę lub zakres odniesienia", { markerEdit = open }, Modifier.weight(1f, fill = false))
                        LinkButton(if (open.pin) "★ Odepnij" else "☆ Przypnij", { store.update { st -> st.saveMarker(open.copy(pin = !open.pin)) }; toast(if (open.pin) "Odpięto parametr" else "Przypięto parametr na górze listy") })
                    }
                }
            }
            // do 12 wyników zwykła lista, dłuższą historię parametru dzieli widok Dni | Tygodnie | Miesiące
            if (list.size in 1..12) item {
                Column(Modifier.padding(top = 18.dp)) {
                    Text("Wyniki, od najnowszego", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    LekiCard { LabResultRows(list.reversed(), open) { openLab(it, it.marker) } }
                }
            } else if (list.size > 12) historyList(
                "labm", "Wyniki, od najnowszego",
                list.reversed().let { l -> if (markerHist.flagOnly) l.filter { labFlag(it, open).isNotEmpty() } else l },
                markerHist, today, { it.date }, "Tylko poza zakresem", "Pokaż wszystkie", "Brak wyników poza zakresem odniesienia.",
                { labGroupSummary(it, listOf(open)) }, 365,
            ) { rows -> LabResultRows(rows, open) { openLab(it, it.marker) } }
        }
    }

    if (scan) ScanDialog(s, store, onClose = { scan = false })
    ecgDraft?.let { EcgEditor(it, true, store) { ecgDraft = null } }
    s.ecg(ecgOpenId)?.let { EcgEditor(it, false, store) { ecgOpenId = "" } }
    labEdit?.let { (lab, markerId) ->
        LabEditor(s, lab, markerId, onSave = { store.update { st -> st.saveLab(it) }; labEdit = null; toast("Zapisano wynik") },
            onDelete = { id ->
                s.labs.firstOrNull { it.id == id }?.let { l ->
                    store.update { it.deleteLab(id) }; labEdit = null
                    undo("Usunięto wynik z ${plDate(l.date)}") { store.update { it.saveLab(l) } }
                }
            }, onDismiss = { labEdit = null })
    }
    if (markersOpen) MarkersDialog(s, onEdit = { markerEdit = it }, onNew = { markerNew = true }, onClose = { markersOpen = false })
    if (markerEdit != null || markerNew) MarkerEditor(s, markerEdit, onSave = { store.update { st -> st.saveMarker(it) }; markerEdit = null; markerNew = false; toast("Zapisano parametr") },
        onDelete = { id -> store.update { it.deleteMarker(id) }; markerEdit = null; markerNew = false; toast("Usunięto parametr") }, onDismiss = { markerEdit = null; markerNew = false })
}

@Composable
private fun LabEditor(s: AppState, lab: Lab?, markerId: String, onSave: (Lab) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var marker by remember { mutableStateOf(s.marker(markerId)?.id ?: s.markers.first().id) }
    var value by remember { mutableStateOf(lab?.let { lnum(it.value) } ?: "") }
    var date by remember { mutableStateOf(lab?.date ?: LocalDate.now()) }
    var note by remember { mutableStateOf(lab?.note ?: "") }
    var lo by remember { mutableStateOf(lab?.lo?.let { lnum(it) } ?: "") }
    var hi by remember { mutableStateOf(lab?.hi?.let { lnum(it) } ?: "") }
    var err by remember { mutableStateOf("") }
    val m = s.marker(marker)
    FullScreenDialog(if (lab != null) "Edycja wyniku" else "Nowy wynik", onDismiss) {
        DropdownField("Parametr", s.markers.map { it.id to it.name }, marker, { marker = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiField("Wynik" + if (!m?.unit.isNullOrEmpty()) ", ${m!!.unit}" else "", value, { value = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, mono = true)
            DateField("Data", date, { date = it }, Modifier.weight(1f))
        }
        LekiField("Notatka (opcjonalnie)", note, { note = it }, placeholder = "np. na czczo, laboratorium")
        Column {
            Text("Zakres z tego badania (opcjonalnie)", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiField("", lo, { lo = it }, Modifier.weight(1f), placeholder = "od", keyboard = KeyboardType.Decimal, mono = true)
                LekiField("", hi, { hi = it }, Modifier.weight(1f), placeholder = "do", keyboard = KeyboardType.Decimal, mono = true)
            }
            Hint("Laboratoria i wiek zmieniają normy. Wpisz zakres z wyniku, jeśli różni się od zakresu parametru; ten wynik będzie oceniany według niego, a pozostałe według zakresu parametru.", Modifier.padding(top = 6.dp))
        }
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onDismiss, Modifier.weight(1f))
            LekiButton("Zapisz", {
                val n = parseNum(value); val l = parseNum(lo); val h = parseNum(hi)
                err = when {
                    m == null -> "Wybierz parametr."
                    n == null || n < 0 || n > 100000 -> "Wpisz wynik jako liczbę, np. 4,2."
                    lo.isNotBlank() && l == null -> "Dolna granica zakresu musi być liczbą."
                    hi.isNotBlank() && h == null -> "Górna granica zakresu musi być liczbą."
                    l != null && h != null && l >= h -> "Dolna granica zakresu musi być mniejsza od górnej."
                    else -> ""
                }
                if (err.isEmpty()) onSave(Lab(lab?.id ?: newId(), date, marker, n!!, note.trim(), l, h))
            }, Modifier.weight(1f), primary = true)
        }
        if (lab != null) LinkButton("Usuń wynik", { onDelete(lab.id) }, danger = true)
    }
}

@Composable
fun MarkerEditor(s: AppState, m: Marker?, onSave: (Marker) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(m?.name ?: "") }
    var unit by remember { mutableStateOf(m?.unit ?: "") }
    var grp by remember { mutableStateOf(m?.grp ?: "") }
    var lo by remember { mutableStateOf(m?.lo?.let { lnum(it) } ?: "") }
    var hi by remember { mutableStateOf(m?.hi?.let { lnum(it) } ?: "") }
    var pin by remember { mutableStateOf(m?.pin ?: false) }
    var err by remember { mutableStateOf("") }
    val used = m != null && s.markerUsed(m.id)
    FullScreenDialog(if (m != null) "Parametr badania" else "Nowy parametr", onDismiss) {
        LekiField("Nazwa", name, { name = it }, placeholder = "np. Ferrytyna")
        LekiField("Jednostka", unit, { unit = it }, placeholder = "np. mmol/l")
        LekiField("Grupa (opcjonalnie)", grp, { grp = it }, placeholder = "np. Lipidy")
        Column {
            Text("Zakres odniesienia (opcjonalnie)", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiField("", lo, { lo = it }, Modifier.weight(1f), placeholder = "od", keyboard = KeyboardType.Decimal, mono = true)
                LekiField("", hi, { hi = it }, Modifier.weight(1f), placeholder = "do", keyboard = KeyboardType.Decimal, mono = true)
            }
            Hint("Przepisz zakres z wyniku z laboratorium, bo każde podaje własny. Aplikacja tylko oznacza wartości, ocenia lekarz.", Modifier.padding(top = 6.dp))
        }
        CheckRow("Przypnij na górze listy", pin, { pin = it }, subtitle = "Przypięte parametry są w Badania → Przegląd w osobnej karcie na górze.")
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onDismiss, Modifier.weight(1f))
            LekiButton("Zapisz", {
                val l = parseNum(lo); val h = parseNum(hi)
                err = when {
                    name.isBlank() -> "Wpisz nazwę parametru."
                    lo.isNotBlank() && l == null -> "Dolna granica musi być liczbą."
                    hi.isNotBlank() && h == null -> "Górna granica musi być liczbą."
                    l != null && h != null && l >= h -> "Dolna granica musi być mniejsza od górnej."
                    else -> ""
                }
                if (err.isEmpty()) onSave(Marker(m?.id ?: ("m" + newId()), name.trim(), unit.trim(), l, h, grp.trim(), pin))
            }, Modifier.weight(1f), primary = true)
        }
        if (m != null) {
            if (used) BodyText("Parametr ma wyniki lub kontrole, więc nie można go usunąć.") else LinkButton("Usuń parametr", { onDelete(m.id) }, danger = true)
        }
    }
}

/** Lista parametrów badań (nazwa, jednostka, zakres, przypięcie) z dodawaniem i edycją; dawniej w Ustawieniach, teraz tam, gdzie się z nich korzysta. */
@Composable
private fun MarkersDialog(s: AppState, onEdit: (Marker) -> Unit, onNew: () -> Unit, onClose: () -> Unit) {
    FullScreenDialog("Parametry badań", onClose, footer = { LekiButton("Dodaj parametr", onNew, Modifier.fillMaxWidth(), primary = true, icon = Ph.Plus) }) {
        BodyText("Nazwy, jednostki i zakresy odniesienia parametrów. Zakresy są orientacyjne: wpisz te z wyników swojego laboratorium. Dotknij parametru, żeby go zmienić albo przypiąć na górze Przeglądu.")
        LekiCard {
            s.markers.forEachIndexed { i, m ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onEdit(m) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        Hint(listOf(m.unit, rangeText(m).takeIf { it.isNotEmpty() }?.let { "zakres odniesienia $it" } ?: "bez zakresu", m.grp).filter { it.isNotEmpty() }.joinToString(", "))
                    }
                    if (m.pin) Text("★", style = TextStyle(fontSize = 15.sp, color = pal.accent))
                    Text("Edytuj", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
                }
            }
        }
    }
}

package pl.asystent.leki.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import pl.asystent.leki.core.saveLab
import pl.asystent.leki.core.saveMarker
import pl.asystent.leki.core.shortDate
import java.time.LocalDate

@Composable
fun LabsScreen(s: AppState, store: Store, onImport: () -> Unit, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), initialMode: Int = 0) {
    val toast = LocalToast.current
    var openId by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var labEdit by remember { mutableStateOf<Pair<Lab?, String>?>(null) } // (wynik do edycji lub null, id parametru)
    var markerEdit by remember { mutableStateOf<Marker?>(null) }
    var markerNew by remember { mutableStateOf(false) }
    var showNone by rememberSaveable { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var ecgDraft by remember { mutableStateOf<pl.asystent.leki.core.Ecg?>(null) }
    var ecgOpenId by rememberSaveable { mutableStateOf("") }
    val ecgImport = rememberEcgImporter { ecgDraft = it }
    var ecgDel by remember { mutableStateOf<String?>(null) }
    val ecgHist = rememberHistoryState()

    val model = s.labsModel()
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
                if (mode != 2) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    LekiButton("Skanuj wyniki", { scan = true }, Modifier.weight(1.4f), primary = true, icon = Ph.Scan)
                    LekiButton("Dodaj ręcznie", { openLab(null, model.order.firstOrNull() ?: s.markers.firstOrNull()?.id.orEmpty()) }, Modifier.weight(1f), tonal = true, icon = Ph.Plus)
                }
                SegRow(listOf("Parametry", "Według dat", "EKG"), mode, { mode = it })
            }
            if (mode == 2) ecgSection(s, ecgHist, java.time.LocalDate.now(), onAdd = { ecgImport() }, onOpen = { ecgOpenId = it }, onDelete = { ecgDel = it })
            else if (mode == 1) labsByDate(s) { openLab(it, it.marker) } else overview(s, model, showNone, { showNone = !showNone }, { openId = it }, { openLab(null, it) })
            if (mode != 2) item {
                Column(Modifier.padding(top = 18.dp)) {
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
                        val last = list.last(); val prev = list.getOrNull(list.size - 2); val f = labFlag(last.value, open)
                        val delta = prev?.let { last.value - it.value }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stat(lnum(last.value), "ostatni wynik" + (if (open.unit.isNotEmpty()) ", ${open.unit}" else "") + ", " + shortDate(last.date), Modifier.weight(1f))
                            Stat(if (delta == null) "brak" else (if (delta > 0) "+" else if (delta < 0) "−" else "") + lnum(Math.abs(delta)), if (prev != null) "zmiana od " + shortDate(prev.date) else "zmiana", Modifier.weight(1f))
                            Stat(rangeText(open).ifEmpty { "brak" }, "zakres odniesienia", Modifier.weight(1f))
                        }
                        if (f.isNotEmpty()) Text("Ostatni wynik jest $f. Ocenia lekarz.", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 10.dp))
                        labChart(list, open)?.let { LabChartView(it, "Wykres: ${open.name} w czasie, z zakresem odniesienia", Modifier.padding(top = 14.dp)) }
                        if (rangeText(open).isNotEmpty()) Hint("Zielone tło to zakres odniesienia. Jest orientacyjny: laboratoria podają własne zakresy.", Modifier.padding(top = 6.dp))
                    }
                    LinkButton("Zmień nazwę lub zakres odniesienia", { markerEdit = open }, Modifier.padding(top = 8.dp))
                }
            }
            if (list.isNotEmpty()) item {
                Column(Modifier.padding(top = 18.dp)) {
                    Text("Wyniki, od najnowszego", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    LekiCard {
                        list.reversed().forEachIndexed { i, l ->
                            if (i > 0) Divider()
                            val fl = labFlag(l.value, open)
                            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { openLab(l, l.marker) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(lnum(l.value), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink))
                                        Hint(open.unit)
                                        if (fl.isNotEmpty()) Text(fl, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                                    }
                                    Hint(plDate(l.date) + if (l.note.isNotEmpty()) ", ${l.note}" else "")
                                }
                                Text("Edytuj", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
                            }
                        }
                    }
                }
            }
        }
    }

    if (scan) ScanDialog(s, store, onClose = { scan = false })
    ecgDraft?.let { EcgEditor(it, true, store) { ecgDraft = null } }
    s.ecg(ecgOpenId)?.let { EcgEditor(it, false, store) { ecgOpenId = "" } }
    ecgDel?.let { id ->
        s.ecg(id)?.let { e ->
            ConfirmDialog("Usunąć EKG?", "Zapis z ${plDate(e.date)} ${e.time.hhmm()} zniknie z aplikacji, raportów i przyszłych kopii zapasowych.", "Usuń",
                onConfirm = { store.update { it.deleteEcg(id) }; ecgDel = null; toast("Usunięto EKG") }, onDismiss = { ecgDel = null }, danger = true)
        } ?: run { ecgDel = null }
    }
    labEdit?.let { (lab, markerId) ->
        LabEditor(s, lab, markerId, onSave = { store.update { st -> st.saveLab(it) }; labEdit = null; toast("Zapisano wynik") },
            onDelete = { id -> store.update { it.deleteLab(id) }; labEdit = null; toast("Usunięto wynik") }, onDismiss = { labEdit = null })
    }
    if (markerEdit != null || markerNew) MarkerEditor(s, markerEdit, onSave = { store.update { st -> st.saveMarker(it) }; markerEdit = null; markerNew = false; toast("Zapisano parametr") },
        onDelete = { id -> store.update { it.deleteMarker(id) }; markerEdit = null; markerNew = false; toast("Usunięto parametr") }, onDismiss = { markerEdit = null; markerNew = false })
}

private fun androidx.compose.foundation.lazy.LazyListScope.overview(
    s: AppState, model: pl.asystent.leki.core.LabsModel, showNone: Boolean, toggleNone: () -> Unit, onOpen: (String) -> Unit, onAddTo: (String) -> Unit,
) {
    item {
        val lastDate = s.labs.maxByOrNull { it.date }?.date
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("${model.withResults.size}", "parametrów z wynikami", Modifier.weight(1f))
            Stat("${model.out.size}", "poza zakresem", Modifier.weight(1f), valueColor = if (model.out.isNotEmpty()) pal.warn else pal.ink)
            Stat(lastDate?.let { shortDate(it) } ?: "brak", "ostatnie badanie", Modifier.weight(1f))
        }
        if (model.withResults.isEmpty()) EmptyBox("Brak wyników. Zeskanuj wyniki ze zdjęcia lub pliku PDF albo dodaj pierwszy wynik ręcznie.", Modifier.padding(top = 16.dp))
    }
    if (model.out.isNotEmpty()) item {
        Column(Modifier.padding(top = 18.dp)) {
            Text("Ostatni wynik poza zakresem", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
            LekiCard { model.out.forEachIndexed { i, x -> if (i > 0) Divider(); LabRowItem(x, showDate = true, spark = true) { onOpen(x.marker.id) } } }
        }
    }
    model.groups.forEach { g ->
        item(key = "grp" + g.name) {
            Column(Modifier.padding(top = 18.dp)) {
                Text(g.name, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                LekiCard { g.items.forEachIndexed { i, x -> if (i > 0) Divider(); LabRowItem(x, showDate = true, spark = true) { onOpen(x.marker.id) } } }
            }
        }
    }
    if (model.none.isNotEmpty()) item {
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

private fun androidx.compose.foundation.lazy.LazyListScope.labsByDate(s: AppState, onEdit: (Lab) -> Unit) {
    val byDate = s.labs.groupBy { it.date }.toSortedMap(compareByDescending { it })
    if (byDate.isEmpty()) item { EmptyBox("Brak wyników.", Modifier.padding(top = 16.dp)) }
    byDate.forEach { (d, labs) ->
        item(key = "d$d") {
            val rows = labs.mapNotNull { l -> s.marker(l.marker)?.let { m -> Triple(m, l, s.markers.indexOf(m)) } }.sortedBy { it.third }
            val nOut = rows.count { labFlag(it.second.value, it.first).isNotEmpty() }
            Column(Modifier.padding(top = 18.dp)) {
                Text(plDate(d) + " · ${rows.size} ${plN(rows.size, "wynik", "wyniki", "wyników")}" + if (nOut > 0) ", poza zakresem: $nOut" else "",
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                LekiCard { rows.forEachIndexed { i, (m, l, _) -> if (i > 0) Divider(); LabRowItem(LabRow(m, listOf(l)), showDate = false, spark = false) { onEdit(l) } } }
            }
        }
    }
}

@Composable
private fun LabRowItem(x: LabRow, showDate: Boolean, spark: Boolean, onClick: () -> Unit) {
    val l = x.last ?: return
    val m = x.marker
    val f = labFlag(l.value, m)
    val sub = listOfNotNull(if (showDate) shortDate(l.date) else null, rangeText(m).takeIf { it.isNotEmpty() }?.let { "zakres $it" }).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn64().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            if (sub.isNotEmpty()) Hint(sub)
        }
        if (spark) Sparkline(x.list.map { it.value }, f.isNotEmpty())
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 4.dp)) {
            Text(lnum(l.value), style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = if (f.isNotEmpty()) pal.warn else pal.ink))
            Hint(m.unit)
            if (f.isNotEmpty()) Text(f, style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
        }
    }
}

private fun Modifier.heightIn64() = this.then(Modifier.heightIn(min = 64.dp))

@Composable
private fun LabEditor(s: AppState, lab: Lab?, markerId: String, onSave: (Lab) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var marker by remember { mutableStateOf(s.marker(markerId)?.id ?: s.markers.first().id) }
    var value by remember { mutableStateOf(lab?.let { lnum(it.value) } ?: "") }
    var date by remember { mutableStateOf(lab?.date ?: LocalDate.now()) }
    var note by remember { mutableStateOf(lab?.note ?: "") }
    var err by remember { mutableStateOf("") }
    var confirmDel by remember { mutableStateOf(false) }
    val m = s.marker(marker)
    FullScreenDialog(if (lab != null) "Edycja wyniku" else "Nowy wynik", onDismiss) {
        DropdownField("Parametr", s.markers.map { it.id to it.name }, marker, { marker = it })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiField("Wynik" + if (!m?.unit.isNullOrEmpty()) ", ${m!!.unit}" else "", value, { value = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal, mono = true)
            DateField("Data", date, { date = it }, Modifier.weight(1f))
        }
        LekiField("Notatka (opcjonalnie)", note, { note = it }, placeholder = "np. na czczo, laboratorium")
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onDismiss, Modifier.weight(1f))
            LekiButton("Zapisz", {
                val n = parseNum(value)
                err = when {
                    m == null -> "Wybierz parametr."
                    n == null || n < 0 || n > 100000 -> "Wpisz wynik jako liczbę, np. 4,2."
                    else -> ""
                }
                if (err.isEmpty()) onSave(Lab(lab?.id ?: newId(), date, marker, n!!, note.trim()))
            }, Modifier.weight(1f), primary = true)
        }
        if (lab != null) LinkButton("Usuń wynik", { confirmDel = true }, danger = true)
    }
    if (confirmDel && lab != null) ConfirmDialog("Usunąć ten wynik?", "Wynik zniknie z dziennika i raportu.", "Usuń", onConfirm = { onDelete(lab.id) }, onDismiss = { confirmDel = false }, danger = true)
}

@Composable
fun MarkerEditor(s: AppState, m: Marker?, onSave: (Marker) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(m?.name ?: "") }
    var unit by remember { mutableStateOf(m?.unit ?: "") }
    var grp by remember { mutableStateOf(m?.grp ?: "") }
    var lo by remember { mutableStateOf(m?.lo?.let { lnum(it) } ?: "") }
    var hi by remember { mutableStateOf(m?.hi?.let { lnum(it) } ?: "") }
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
                if (err.isEmpty()) onSave(Marker(m?.id ?: ("m" + newId()), name.trim(), unit.trim(), l, h, grp.trim()))
            }, Modifier.weight(1f), primary = true)
        }
        if (m != null) {
            if (used) BodyText("Parametr ma wyniki lub kontrole, więc nie można go usunąć.") else LinkButton("Usuń parametr", { onDelete(m.id) }, danger = true)
        }
    }
}

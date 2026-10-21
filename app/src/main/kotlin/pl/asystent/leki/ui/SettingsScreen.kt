package pl.asystent.leki.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import pl.asystent.leki.BuildConfig
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.Event
import pl.asystent.leki.core.Marker
import pl.asystent.leki.core.Med
import pl.asystent.leki.core.Settings as AppSettings
import pl.asystent.leki.core.Slot
import pl.asystent.leki.core.ThemeMode
import pl.asystent.leki.core.deleteEvent
import pl.asystent.leki.core.deleteSlot
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.phaseAt
import pl.asystent.leki.core.phaseText
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.rangeText
import pl.asystent.leki.core.reminders
import pl.asystent.leki.core.saveEvent
import pl.asystent.leki.core.saveMarker
import pl.asystent.leki.core.saveSlot
import pl.asystent.leki.core.deleteMarker
import pl.asystent.leki.core.slotUsage
import pl.asystent.leki.reminders.Reminders
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun SettingsScreen(s: AppState, store: Store, importer: () -> Unit, newMedRequest: Boolean, onNewMedHandled: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val today = LocalDate.now()
    var medEdit by remember { mutableStateOf<Pair<String?, String?>?>(null) } // (id leku, id leku zastępowanego)
    var slotEdit by remember { mutableStateOf<Slot?>(null) }
    var slotNew by remember { mutableStateOf(false) }
    var eventEdit by remember { mutableStateOf<Event?>(null) }
    var eventNew by remember { mutableStateOf(false) }
    var markerEdit by remember { mutableStateOf<Marker?>(null) }
    var markerNew by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    var showAllMarkers by rememberSaveable { mutableStateOf(false) }
    val saveJson = rememberJsonSaver(s, store)

    LaunchedEffect(newMedRequest) { if (newMedRequest) { medEdit = null to null; onNewMedHandled() } }

    val active = s.meds.filter { m -> m.end.let { e -> e == null || e >= today } }
    val ended = s.meds.filter { m -> m.end.let { e -> e != null && e < today } }
    val evs = s.events.sortedWith { a, b ->
        val fa = a.date >= today; val fb = b.date >= today
        if (fa != fb) (if (fa) -1 else 1) else if (fa) a.date.compareTo(b.date) else b.date.compareTo(a.date)
    }
    val c = s.settings
    fun setCfg(f: (AppSettings) -> AppSettings) = store.update { it.copy(settings = f(it.settings)) }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 32.dp)) {
        item {
            SectionTitle("Leki") { LekiButton("Dodaj lek", { medEdit = null to null }, small = true, icon = Ph.Plus) }
            if (active.isEmpty()) EmptyBox("Brak aktywnych leków.") else LekiCard { active.forEachIndexed { i, m -> if (i > 0) Divider(); MedRow(s, m, today) { medEdit = m.id to null } } }
            if (ended.isNotEmpty()) {
                LinkButton((if (showEnded) "▾ " else "▸ ") + "Zakończone (${ended.size})", { showEnded = !showEnded })
                if (showEnded) LekiCard { ended.forEachIndexed { i, m -> if (i > 0) Divider(); MedRow(s, m, today) { medEdit = m.id to null } } }
            }
        }
        item {
            SectionTitle("Pory dnia") { LekiButton("Dodaj porę", { slotNew = true }, small = true, icon = Ph.Plus) }
            LekiCard {
                s.sortedSlots().forEachIndexed { i, sl ->
                    if (i > 0) Divider()
                    SettingRow(sl.name, sl.time.hhmm(), mono = true) { slotEdit = sl }
                }
            }
        }
        item {
            SectionTitle("Kontrole i przypomnienia") { LekiButton("Dodaj", { eventNew = true }, small = true, icon = Ph.Plus) }
            if (evs.isEmpty()) EmptyBox("Brak kontroli. Dodaj np. badanie krwi lub wizytę u lekarza, a pojawi się w danym dniu.")
            else LekiCard { evs.forEachIndexed { i, e -> if (i > 0) Divider(); SettingRow(e.title, plDate(e.date) + if (e.field.isNotEmpty()) ", pole: ${e.field}" else "") { eventEdit = e } } }
        }
        item {
            SectionTitle("Parametry badań") { LekiButton("Dodaj", { markerNew = true }, small = true, icon = Ph.Plus) }
            val shown = if (s.markers.size > 8 && !showAllMarkers) s.markers.take(8) else s.markers
            LekiCard {
                shown.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    SettingRow(m.name, listOf(m.unit, rangeText(m).takeIf { it.isNotEmpty() }?.let { "zakres odniesienia $it" } ?: "bez zakresu").filter { it.isNotEmpty() }.joinToString(", ")) { markerEdit = m }
                }
            }
            if (s.markers.size > 8) LinkButton(if (showAllMarkers) "Pokaż mniej" else "Pokaż wszystkie (${s.markers.size})", { showAllMarkers = !showAllMarkers })
            Hint("Zakresy są orientacyjne. Wpisz te z wyników swojego laboratorium.", Modifier.padding(top = 6.dp))
        }
        item { GeneralSection(s, store, ::setCfg) }
        item { ReminderSection(s, store) }
        item {
            SectionTitle("Dane")
            LekiCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BodyText("Ostatnia kopia: " + (s.lastBackup?.let { plDate(it) } ?: "jeszcze nie zrobiono") + ". Dane są zapisane tylko na tym urządzeniu, w tej aplikacji. Odinstalowanie aplikacji albo wyczyszczenie jej danych usunie je, dopóki nie wczytasz kopii.")
                    LekiButton("Zaszyfrowana kopia (hasło)", { encrypt = true }, Modifier.fillMaxWidth(), primary = true, icon = Ph.LockKey)
                    Hint("Zalecane do chmury (Dysk Google, Filen). Dane są szyfrowane na tym urządzeniu, zanim opuszczą telefon.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LekiButton("Zapisz JSON", saveJson, Modifier.weight(1f), icon = Ph.DownloadSimple)
                        LekiButton("Kopiuj JSON", {
                            Platform.copyText(ctx, "Kopia danych", Backup.export(s.copy(lastBackup = LocalDate.now())))
                            store.update { it.copy(lastBackup = LocalDate.now()) }; toast("Skopiowano dane JSON")
                        }, Modifier.weight(1f), icon = Ph.Copy)
                    }
                    LekiButton("Wczytaj kopię z pliku", importer, Modifier.fillMaxWidth(), icon = Ph.UploadSimple)
                    LinkButton("Usuń wszystkie dane", { confirmClear = true }, danger = true)
                }
            }
        }
        item {
            SectionTitle("O aplikacji")
            BodyText("Asystent leków ${BuildConfig.VERSION_NAME}. Dane zostają na tym urządzeniu, aplikacja nie łączy się z internetem. Ikony: Phosphor Icons (MIT). Aplikacja nie ocenia wyników i nie zastępuje lekarza.")
        }
    }

    medEdit?.let { (id, rep) -> MedEditor(s, store, id, rep, onClose = { medEdit = null }, onReplace = { medEdit = null to it }) }
    if (slotEdit != null || slotNew) SlotEditor(s, slotEdit, onSave = { store.update { st -> st.saveSlot(it) }; slotEdit = null; slotNew = false; toast("Zapisano porę dnia") },
        onDelete = { id ->
            val r = s.deleteSlot(id)
            if (r == null) toast("Musi zostać przynajmniej jedna pora dnia.") else { store.replace(r); toast("Usunięto porę dnia") }
            slotEdit = null; slotNew = false
        }, onDismiss = { slotEdit = null; slotNew = false })
    if (eventEdit != null || eventNew) EventEditor(s, eventEdit, onSave = { store.update { st -> st.saveEvent(it) }; eventEdit = null; eventNew = false; toast("Zapisano kontrolę") },
        onDelete = { id -> store.update { it.deleteEvent(id) }; eventEdit = null; eventNew = false; toast("Usunięto kontrolę") }, onDismiss = { eventEdit = null; eventNew = false })
    if (markerEdit != null || markerNew) MarkerEditor(s, markerEdit, onSave = { store.update { st -> st.saveMarker(it) }; markerEdit = null; markerNew = false; toast("Zapisano parametr") },
        onDelete = { id -> store.update { it.deleteMarker(id) }; markerEdit = null; markerNew = false; toast("Usunięto parametr") }, onDismiss = { markerEdit = null; markerNew = false })
    if (encrypt) EncryptBackupDialog(s, store) { encrypt = false }
    if (confirmClear) ConfirmDialog(
        "Usunąć wszystkie dane?", "Znikną leki, dawkowanie, odhaczenia, pomiary ciśnienia, wyniki badań i ustawienia z tego urządzenia. Tej operacji nie da się cofnąć, chyba że masz kopię JSON lub zaszyfrowaną kopię.",
        "Usuń wszystko", onConfirm = { store.replace(AppState()); toast("Usunięto wszystkie dane") }, onDismiss = { confirmClear = false }, danger = true,
    )
}

@Composable
private fun MedRow(s: AppState, m: Med, today: LocalDate, onClick: () -> Unit) {
    val p = m.phaseAt(today) ?: m.phases.first()
    var sub = ""
    if (m.phases.first().from > today) sub = "od ${plDate(m.phases.first().from)}: "
    sub += s.phaseText(m, p)
    m.end?.let { sub += ". " + (if (it < today) "Zakończony " else "Do ") + plDate(it) }
    SettingRow(m.name, sub, onClick = onClick)
}

@Composable
private fun SettingRow(title: String, subtitle: String, mono: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(subtitle, style = TextStyle(fontSize = 12.5.sp, color = pal.ink3, fontFamily = if (mono) Mono else null), modifier = Modifier.padding(top = 2.dp))
        }
        PhIcon(Ph.CaretRight, size = 16.dp)
    }
}

@Composable
private fun GeneralSection(s: AppState, store: Store, setCfg: ((AppSettings) -> AppSettings) -> Unit) {
    val c = s.settings
    var patient by remember { mutableStateOf(s.patient) }
    var reportNote by remember { mutableStateOf(c.reportNote) }
    var rules by remember { mutableStateOf(s.rules) }
    SectionTitle("Ogólne")
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LekiField("Imię i nazwisko (do raportu)", patient, { patient = it; store.update { st -> st.copy(patient = it.trim()) } })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntField("Cel wody, szklanek", c.waterGoal, { v -> setCfg { it.copy(waterGoal = v) } }, 0..20, Modifier.weight(1f))
                IntField("Szklanka, ml", c.glassMl, { v -> setCfg { it.copy(glassMl = v) } }, 50..1000, Modifier.weight(1f))
            }
            Hint("Cel wody 0 wyłącza licznik wody.")
            IntField("Dawka spóźniona po (min od pory, 0 = wyłączone)", c.lateMin, { v -> setCfg { it.copy(lateMin = v) } }, 0..600)
            Column {
                Text("Oznaczaj ciśnienie jako podwyższone od", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntField("", c.hiSys, { v -> setCfg { it.copy(hiSys = v) } }, 100..250, Modifier.weight(1f))
                    IntField("", c.hiDia, { v -> setCfg { it.copy(hiDia = v) } }, 50..150, Modifier.weight(1f))
                }
            }
            Column {
                Text("Oznaczaj ciśnienie jako niskie poniżej", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IntField("", c.loSys, { v -> setCfg { it.copy(loSys = v) } }, 60..130, Modifier.weight(1f))
                    IntField("", c.loDia, { v -> setCfg { it.copy(loDia = v) } }, 30..100, Modifier.weight(1f))
                }
                Hint("Progi ustaw według zaleceń lekarza. Aplikacja tylko oznacza wartości.", Modifier.padding(top = 6.dp))
            }
            Column {
                Text("Motyw", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                SegRow(listOf("Auto", "Jasny", "Ciemny"), ThemeMode.values().indexOf(c.theme), { i -> setCfg { it.copy(theme = ThemeMode.values()[i]) } })
            }
            LekiField("Dopisek na raporcie", reportNote, { reportNote = it; setCfg { c2 -> c2.copy(reportNote = it) } }, singleLine = false, minLines = 3, placeholder = "np. rozpoznanie, cel kuracji")
            LekiField("Zasady i notatki (przycisk Zasady u góry)", rules, { rules = it; store.update { st -> st.copy(rules = it) } }, singleLine = false, minLines = 5)
        }
    }
}

@Composable
private fun ReminderSection(s: AppState, store: Store) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val c = s.settings
    var tick by remember { mutableStateOf(0) }

    fun enable() {
        store.update { it.copy(settings = it.settings.copy(remindOn = true)) }
        toast("Przypomnienia włączone")
        if (!Reminders.canScheduleExact(ctx) && Build.VERSION.SDK_INT >= 31) {
            toast("Zezwól aplikacji na dokładne alarmy, inaczej przypomnienia mogą się spóźniać.")
            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (granted) enable() else toast("Brak zgody na powiadomienia. Włącz je w ustawieniach telefonu.")
    }
    fun toggle() {
        if (c.remindOn) { store.update { it.copy(settings = it.settings.copy(remindOn = false)) }; return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else enable()
    }
    val planned = if (c.remindOn) s.reminders(ZoneId.systemDefault(), System.currentTimeMillis(), LocalDate.now()).size else 0
    SectionTitle("Przypomnienia o lekach")
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyText("Powiadomienie o porach dawek z Twojego planu, także przy zamkniętej aplikacji. Pomija pory, które są już odhaczone.")
            LekiButton(if (c.remindOn) "Wyłącz przypomnienia" else "Włącz przypomnienia", { toggle() }, Modifier.fillMaxWidth(), primary = !c.remindOn)
            IntField("Ile minut przed porą dawki (0 = równo o porze)", c.remindLead, { v -> store.update { it.copy(settings = it.settings.copy(remindLead = v)) } }, 0..120)
            LinkButton("Wyślij testowe powiadomienie", {
                if (!Reminders.canNotify(ctx)) toast("Brak zgody na powiadomienia. Włącz je w ustawieniach telefonu.")
                else { Reminders.scheduleTest(ctx); toast("Powiadomienie pojawi się za kilka sekund. Możesz zablokować ekran.") }
            })
            if (c.remindOn) Hint(if (!Reminders.canNotify(ctx)) "Powiadomienia są wyłączone w ustawieniach telefonu." else "Zaplanowane przypomnienia: $planned.")
        }
    }
}

@Composable
private fun SlotEditor(s: AppState, sl: Slot?, onSave: (Slot) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(sl?.name ?: "") }
    var time by remember { mutableStateOf(sl?.time ?: LocalTime.of(12, 0)) }
    var err by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    FullScreenDialog(if (sl != null) "Edycja pory dnia" else "Nowa pora dnia", onDismiss) {
        LekiField("Nazwa", name, { name = it; err = "" }, placeholder = "np. Podwieczorek")
        Column {
            TimeField("Godzina", time, { time = it })
            Hint("Godzina ustala kolejność pór na liście dnia.", Modifier.padding(top = 6.dp))
        }
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onDismiss, Modifier.weight(1f))
            LekiButton("Zapisz", { if (name.isBlank()) err = "Wpisz nazwę pory dnia." else onSave(Slot(sl?.id ?: newId(), name.trim(), time)) }, Modifier.weight(1f), primary = true)
        }
        if (sl != null) LinkButton("Usuń porę dnia", { if (s.slots.size <= 1) err = "Musi zostać przynajmniej jedna pora dnia." else confirm = true }, danger = true)
    }
    if (confirm && sl != null) {
        val used = s.slotUsage(sl.id)
        ConfirmDialog("Usunąć porę dnia?", (if (used > 0) "Pora jest używana przez $used ${if (used == 1) "lek" else "leków"}. Ich dawki w tej porze znikną z planu. " else "") + "Wcześniejsze odhaczenia zostają w danych.", "Usuń",
            onConfirm = { onDelete(sl.id) }, onDismiss = { confirm = false }, danger = true)
    }
}

@Composable
private fun EventEditor(s: AppState, e: Event?, onSave: (Event) -> Unit, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(e?.title ?: "") }
    var date by remember { mutableStateOf(e?.date ?: LocalDate.now()) }
    var note by remember { mutableStateOf(e?.note ?: "") }
    var marker by remember { mutableStateOf(e?.marker ?: "") }
    var field by remember { mutableStateOf(e?.field ?: "") }
    var err by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    FullScreenDialog(if (e != null) "Edycja kontroli" else "Nowa kontrola", onDismiss) {
        LekiField("Nazwa", title, { title = it; err = "" }, placeholder = "np. Wizyta u kardiologa")
        DateField("Data", date, { date = it })
        LekiField("Treść przypomnienia (opcjonalnie)", note, { note = it }, singleLine = false, minLines = 3, placeholder = "np. na czczo, zabierz wyniki")
        Column {
            DropdownField("Parametr badania (opcjonalnie)", listOf("" to "Brak, tylko pole tekstowe") + s.markers.map { it.id to (it.name + if (it.unit.isNotEmpty()) " (${it.unit})" else "") }, marker, { id ->
                marker = id
                val mk = s.marker(id)
                if (mk != null && field.isBlank()) field = mk.name + if (mk.unit.isNotEmpty()) " (${mk.unit})" else ""
            })
            Hint("Wynik wpisany w dniu kontroli trafi do dziennika w zakładce Badania.", Modifier.padding(top = 6.dp))
        }
        Column {
            LekiField("Pole na wynik (opcjonalnie)", field, { field = it }, placeholder = "np. Wynik (jednostka)")
            Hint("W dniu kontroli pojawi się pole do wpisania wyniku.", Modifier.padding(top = 6.dp))
        }
        ErrorText(err)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LekiButton("Anuluj", onDismiss, Modifier.weight(1f))
            LekiButton("Zapisz", {
                if (title.isBlank()) err = "Wpisz nazwę kontroli."
                else onSave(Event(e?.id ?: newId(), date, title.trim(), note.trim(), field.trim(), if (s.marker(marker) != null) marker else ""))
            }, Modifier.weight(1f), primary = true)
        }
        if (e != null) LinkButton("Usuń kontrolę", { confirm = true }, danger = true)
    }
    if (confirm && e != null) ConfirmDialog("Usunąć kontrolę?", "Zniknie przypomnienie. Wpisane wcześniej wyniki zostają w danych dnia.", "Usuń", onConfirm = { onDelete(e.id) }, onDismiss = { confirm = false }, danger = true)
}

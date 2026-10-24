package pl.asystent.leki.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import pl.asystent.leki.LocalLock
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppLock
import pl.asystent.leki.core.Csv
import pl.asystent.leki.core.REMINDER_BACKUP
import pl.asystent.leki.core.REMINDER_STOCK
import pl.asystent.leki.core.Licenses
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.Event
import pl.asystent.leki.core.Marker
import pl.asystent.leki.core.Med
import pl.asystent.leki.core.MedDraft
import pl.asystent.leki.core.Settings as AppSettings
import pl.asystent.leki.core.Slot
import pl.asystent.leki.core.Cykl
import pl.asystent.leki.core.CyklData
import pl.asystent.leki.core.ThemeMode
import pl.asystent.leki.core.deleteEvent
import pl.asystent.leki.core.deleteSlot
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.phaseAt
import pl.asystent.leki.core.phaseText
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.rangeText
import pl.asystent.leki.core.stockForecast
import pl.asystent.leki.core.StockLevel
import pl.asystent.leki.core.stockLevel
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

private enum class Page(val title: String) {
    Home("Ustawienia"), Meds("Leki"), Slots("Pory dnia"), Events("Kontrole i przypomnienia"),
    General("Ogólne"), Reminders("Przypomnienia"), Security("Blokada aplikacji"), Data("Kopie zapasowe i dane"), Cykl("Moduł Cykl"), People("Osoby"), About("O aplikacji"),
}

@Composable
fun SettingsScreen(
    s: AppState, store: Store, importer: () -> Unit, newMedRequest: Boolean, onNewMedHandled: () -> Unit, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(), initialPage: String = "Home",
    showMedsRequest: Boolean = false, onShowMedsHandled: () -> Unit = {}, showEventsRequest: Boolean = false, onShowEventsHandled: () -> Unit = {}, onOpenCykl: () -> Unit = {},
    showPeopleRequest: Boolean = false, onShowPeopleHandled: () -> Unit = {}, onOpenCyklReport: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val today = LocalDate.now()
    var pageName by rememberSaveable { mutableStateOf(initialPage) }
    val page = Page.values().firstOrNull { it.name == pageName } ?: Page.Home
    var medEdit by remember { mutableStateOf<Pair<String?, String?>?>(null) } // (id leku, id leku zastępowanego)
    var medScan by remember { mutableStateOf(false) }
    var medDraft by remember { mutableStateOf<MedDraft?>(null) }
    var medPhoto by remember { mutableStateOf<pl.asystent.leki.ocr.ScanPhoto?>(null) }
    var slotEdit by remember { mutableStateOf<Slot?>(null) }
    var slotNew by remember { mutableStateOf(false) }
    var eventEdit by remember { mutableStateOf<Event?>(null) }
    var eventNew by remember { mutableStateOf(false) }
    var encrypt by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    // zakres kopii: puste = wszystkie osoby
    var backupIds by remember { mutableStateOf(setOf<String>()) }
    val backupOnly: Set<String>? = backupIds.takeIf { it.isNotEmpty() }
    val saveJson = rememberJsonSaver(s, store) { backupIds.takeIf { it.isNotEmpty() } }

    BackHandler(enabled = page != Page.Home) { pageName = Page.Home.name }
    LaunchedEffect(showMedsRequest) { if (showMedsRequest) { pageName = Page.Meds.name; onShowMedsHandled() } }
    LaunchedEffect(showPeopleRequest) { if (showPeopleRequest) { pageName = Page.People.name; onShowPeopleHandled() } }
    LaunchedEffect(showEventsRequest) { if (showEventsRequest) { pageName = Page.Events.name; onShowEventsHandled() } }
    LaunchedEffect(newMedRequest) { if (newMedRequest) { pageName = Page.Meds.name; medEdit = null to null; onNewMedHandled() } }

    val active = s.meds.filter { m -> m.end.let { e -> e == null || e >= today } }
    val ended = s.meds.filter { m -> m.end.let { e -> e != null && e < today } }
    val upcomingEvents = s.events.count { it.date >= today }
    val evs = s.events.sortedWith { a, b ->
        val fa = a.date >= today; val fb = b.date >= today
        if (fa != fb) (if (fa) -1 else 1) else if (fa) a.date.compareTo(b.date) else b.date.compareTo(a.date)
    }
    val c = s.settings
    fun setCfg(f: (AppSettings) -> AppSettings) = store.update { it.copy(settings = f(it.settings)) }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        if (page == Page.Home) {
            item {
                Text("Ustawienia", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                Overline("Osoby")
                LekiCard {
                    NavRow(Ph.User, "Osoby", store.profiles.value.let { l -> "${l.size} ${plN(l.size, "osoba", "osoby", "osób")}: " + l.joinToString(", ") { it.name } }) { pageName = Page.People.name }
                }
                Spacer(Modifier.height(22.dp))
                Overline("Plan")
                LekiCard {
                    NavRow(Ph.Pill, "Leki", if (active.isEmpty()) "Brak aktywnych leków" else "${active.size} ${plN(active.size, "aktywny", "aktywne", "aktywnych")}") { pageName = Page.Meds.name }
                    Divider()
                    NavRow(Ph.Clock, "Pory dnia", s.sortedSlots().joinToString(", ") { it.name }) { pageName = Page.Slots.name }
                    Divider()
                    NavRow(Ph.Stethoscope, "Kontrole i przypomnienia", if (upcomingEvents == 0) "Brak zaplanowanych" else "$upcomingEvents ${plN(upcomingEvents, "zaplanowana", "zaplanowane", "zaplanowanych")}") { pageName = Page.Events.name }
                }
                Spacer(Modifier.height(22.dp))
                Overline("Aplikacja")
                LekiCard {
                    NavRow(Ph.Sliders, "Ogólne", "Imię, woda, progi, motyw") { pageName = Page.General.name }
                    Divider()
                    NavRow(Ph.Bell, "Przypomnienia", (if (c.remindOn) "Dawki włączone" else "Dawki wyłączone") + ", zapas leków, kopia") { pageName = Page.Reminders.name }
                    Divider()
                    NavRow(Ph.LockKey, "Blokada aplikacji", if (LocalLock.current?.enabled == true) "Włączona" else "Wyłączona") { pageName = Page.Security.name }
                    Divider()
                    NavRow(Ph.FloppyDisk, "Kopie zapasowe i dane", "Ostatnia kopia: " + (s.lastBackup?.let { plDate(it) } ?: "jeszcze nie zrobiono")) { pageName = Page.Data.name }
                    Divider()
                    NavRow(Ph.CalendarBlank, "Moduł Cykl", if (!c.cyklOn) "Ukryty" else Cykl.summary(store.cyklOf(store.activeId.value).db)?.let { "Widoczny, ${it.entries} ${plN(it.entries, "wpis", "wpisy", "wpisów")}" } ?: "Widoczny, brak danych") { pageName = Page.Cykl.name }
                    Divider()
                    NavRow(Ph.Info, "O aplikacji", "Wersja ${BuildConfig.VERSION_NAME}") { pageName = Page.About.name }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Ph.ArrowLeft, "Wróć", { pageName = Page.Home.name }, tonal = false)
                    Text(page.title, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.weight(1f).padding(start = 4.dp))
                    when (page) {
                        Page.Meds -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LekiButton("Ze zdjęcia", { medScan = true }, small = true, tonal = true, icon = Ph.Camera)
                            LekiButton("Dodaj lek", { medEdit = null to null }, small = true, tonal = true, icon = Ph.Plus)
                        }
                        Page.Slots -> LekiButton("Dodaj", { slotNew = true }, small = true, tonal = true, icon = Ph.Plus)
                        Page.Events -> LekiButton("Dodaj", { eventNew = true }, small = true, tonal = true, icon = Ph.Plus)
                        else -> {}
                    }
                }
            }
            when (page) {
                Page.Meds -> item {
                    if (active.isEmpty()) EmptyBox("Brak aktywnych leków.", action = {
                        LekiButton("Dodaj lek", { medEdit = null to null }, primary = true, icon = Ph.Plus)
                        Spacer(Modifier.height(8.dp))
                        LekiButton("Dodaj lek ze zdjęcia opakowania", { medScan = true }, tonal = true, icon = Ph.Camera)
                    })
                    else LekiCard { active.forEachIndexed { i, m -> if (i > 0) Divider(); MedRow(s, m, today) { medEdit = m.id to null } } }
                    if (active.any { it.stock != null }) Hint("Pasek zapasu: zielony to wystarczy, pomarańczowy to kończy się (próg w Ustawieniach → Przypomnienia), czerwony to krytycznie mało. Dotknij leku, żeby uzupełnić zapas.", Modifier.padding(top = 8.dp))
                    if (ended.isNotEmpty()) {
                        LinkButton((if (showEnded) "▾ " else "▸ ") + "Zakończone (${ended.size})", { showEnded = !showEnded }, Modifier.padding(top = 8.dp))
                        if (showEnded) LekiCard { ended.forEachIndexed { i, m -> if (i > 0) Divider(); MedRow(s, m, today) { medEdit = m.id to null } } }
                    }
                }
                Page.Slots -> item {
                    LekiCard { s.sortedSlots().forEachIndexed { i, sl -> if (i > 0) Divider(); SettingRow(sl.name, sl.time.hhmm(), mono = true) { slotEdit = sl } } }
                    Hint("Godzina pory ustala kolejność na liście dnia i czas przypomnień.", Modifier.padding(top = 8.dp))
                }
                Page.Events -> item {
                    if (evs.isEmpty()) EmptyBox("Brak kontroli. Dodaj np. badanie krwi lub wizytę u lekarza, a pojawi się w danym dniu.", action = { LekiButton("Dodaj kontrolę", { eventNew = true }, primary = true, icon = Ph.Plus) })
                    else LekiCard { evs.forEachIndexed { i, e -> if (i > 0) Divider(); SettingRow(e.title, plDate(e.date) + if (e.field.isNotEmpty()) ", pole: ${e.field}" else "") { eventEdit = e } } }
                }
                Page.General -> item { GeneralSection(s, store, ::setCfg) }
                Page.Reminders -> item { ReminderSection(s, store) }
                Page.Security -> item { SecuritySection() }
                Page.Cykl -> item { CyklSection(s, store, onOpenCykl, onOpenCyklReport) }
                Page.People -> item { PeopleSection(s, store) }
                Page.Data -> item {
                    LekiCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BodyText("Ostatnia kopia: " + (s.lastBackup?.let { plDate(it) } ?: "jeszcze nie zrobiono") + ". Dane są zapisane tylko na tym urządzeniu, w tej aplikacji. Odinstalowanie aplikacji albo wyczyszczenie jej danych usunie je, dopóki nie wczytasz kopii.")
                            BackupScopePicker(store.profiles.value, backupIds) { backupIds = it }
                            LekiButton("Zaszyfrowana kopia (hasło)", { encrypt = true }, Modifier.fillMaxWidth(), primary = true, icon = Ph.LockKey)
                            Hint("Zalecane do chmury (Dysk Google, Filen). Dane są szyfrowane na tym urządzeniu, zanim opuszczą telefon.")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                LekiButton("Zapisz JSON", saveJson, Modifier.weight(1f), tonal = true, icon = Ph.DownloadSimple)
                                LekiButton("Kopiuj JSON", {
                                    if (Platform.copyText(ctx, "Kopia danych", store.backupText(only = backupOnly))) {
                                        store.markBackup(LocalDate.now(), backupOnly); toast("Skopiowano dane JSON")
                                    } else toast("Za dużo danych na schowek. Użyj „Zapisz JSON” albo zaszyfrowanej kopii.")
                                }, Modifier.weight(1f), tonal = true, icon = Ph.Copy)
                            }
                            LekiButton("Wczytaj kopię z pliku", importer, Modifier.fillMaxWidth(), icon = Ph.UploadSimple)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    AutoBackupSection(s, store)
                    Spacer(Modifier.height(18.dp))
                    CsvSection(s)
                    Spacer(Modifier.height(18.dp))
                    LekiCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Usuwanie danych", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                            BodyText("Usuwa wszystko z tego urządzenia. Zrób wcześniej kopię.")
                            LinkButton("Usuń wszystkie dane", { confirmClear = true }, danger = true)
                        }
                    }
                }
                Page.About -> item {
                    LekiCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Asystent leków ${BuildConfig.VERSION_NAME}", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                            BodyText("Dane zostają na tym urządzeniu. Aplikacja nie ma dostępu do internetu, więc niczego nie wysyła, a rozpoznawanie tekstu ze zdjęć działa na telefonie.")
                            BodyText("Aplikacja tylko zapisuje i porządkuje dane wpisane przez użytkownika. Nie ocenia wyników i nie zastępuje lekarza.")
                            Hint("Ikony: Phosphor Icons (MIT). Rozpoznawanie tekstu: ML Kit Text Recognition (model dołączony do aplikacji).")
                            var showLic by rememberSaveable { mutableStateOf(false) }
                            LinkButton((if (showLic) "▾ " else "▸ ") + "Licencje i źródła algorytmów", { showLic = !showLic })
                            if (showLic) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Licenses.included.forEach { n ->
                                    Column {
                                        Text(n.name, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                                        Text("${n.license}. ${n.role}. ${n.copyright}.", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2))
                                        Text(n.url, style = TextStyle(fontSize = 12.sp, color = pal.ink3))
                                    }
                                }
                                Text("Algorytmy własne, oparte na publikacjach (kod napisany od zera)", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                                Licenses.references.forEach { Text("• $it", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2)) }
                                Hint("Pełne teksty licencji: plik THIRD-PARTY-NOTICES.md w repozytorium projektu.")
                            }
                        }
                    }
                }
                Page.Home -> {}
            }
        }
    }

    if (medScan) MedScanDialog(onDraft = { d, ph -> medScan = false; medDraft = d; medPhoto?.release(); medPhoto = ph; medEdit = null to null }, onClose = { medScan = false })
    medEdit?.let { (id, rep) -> MedEditor(s, store, id, rep, onClose = { medEdit = null; medDraft = null; medPhoto?.release(); medPhoto = null }, onReplace = { medEdit = null to it }, prefill = medDraft, photo = medPhoto) }
    if (slotEdit != null || slotNew) SlotEditor(s, slotEdit, onSave = { store.update { st -> st.saveSlot(it) }; slotEdit = null; slotNew = false; toast("Zapisano porę dnia") },
        onDelete = { id ->
            val r = s.deleteSlot(id)
            if (r == null) toast("Musi zostać przynajmniej jedna pora dnia.") else { store.replace(r); toast("Usunięto porę dnia") }
            slotEdit = null; slotNew = false
        }, onDismiss = { slotEdit = null; slotNew = false })
    if (eventEdit != null || eventNew) EventEditor(s, eventEdit, onSave = { store.update { st -> st.saveEvent(it) }; eventEdit = null; eventNew = false; toast("Zapisano kontrolę") },
        onDelete = { id ->
            s.event(id)?.let { ev -> store.update { it.deleteEvent(id) }; undo("Usunięto kontrolę: ${ev.title}") { store.update { it.saveEvent(ev) } } }
            eventEdit = null; eventNew = false
        }, onDismiss = { eventEdit = null; eventNew = false })
    if (encrypt) EncryptBackupDialog(s, store, backupOnly) { encrypt = false }
    if (confirmClear) ConfirmDialog(
        "Usunąć wszystkie dane?", "Znikną dane wszystkich osób: leki, dawkowanie, odhaczenia, pomiary ciśnienia, wyniki badań, dziennik cyklu (jeśli go używasz) i ustawienia z tego urządzenia. Tej operacji nie da się cofnąć, chyba że masz kopię JSON lub zaszyfrowaną kopię.",
        "Usuń wszystko", onConfirm = { store.wipeAll(); toast("Usunięto wszystkie dane") }, onDismiss = { confirmClear = false }, danger = true,
    )
}

/** Wiersz przejścia do podstrony ustawień: ikona, tytuł i krótkie podsumowanie. */
@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(summary, style = TextStyle(fontSize = 13.sp, color = pal.ink3), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        }
        PhIcon(Ph.CaretRight, size = 16.dp)
    }
}

@Composable
private fun MedRow(s: AppState, m: Med, today: LocalDate, onClick: () -> Unit) {
    val p = m.phaseAt(today) ?: m.phases.first()
    var sub = ""
    if (m.phases.first().from > today) sub = "od ${plDate(m.phases.first().from)}: "
    sub += s.phaseText(m, p)
    m.end?.let { sub += ". " + (if (it < today) "Zakończony " else "Do ") + plDate(it) }
    // jedna lista: nazwa, dawkowanie, a gdy zapas jest śledzony, jego stan z paskiem; dotknięcie otwiera edycję (tam uzupełnisz zapas)
    val finished = m.end?.let { it < today } == true
    val f = if (!finished) s.stockForecast(m, today) else null
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(sub, style = TextStyle(fontSize = 12.5.sp, color = pal.ink3), modifier = Modifier.padding(top = 2.dp))
            if (f != null) {
                val level = stockLevel(f, s.settings.stockWarnDays)
                Text("Zapas: " + stockLine(f, m.unit), style = TextStyle(fontSize = 12.5.sp, fontWeight = if (level == StockLevel.Ok) FontWeight.Normal else FontWeight.SemiBold, color = if (level == StockLevel.Ok) pal.ink2 else stockColor(level)), modifier = Modifier.padding(top = 4.dp))
                StockBar(f, s.settings.stockWarnDays, Modifier.padding(top = 6.dp))
            }
        }
        PhIcon(Ph.CaretRight, size = 16.dp)
    }
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
                SegRow(listOf("Auto", "Jasny", "Ciemny"), ThemeMode.values().indexOf(c.theme), { i -> store.updateAll { it.copy(settings = it.settings.copy(theme = ThemeMode.values()[i])) } })
            }
            LekiField("Dopisek na raporcie", reportNote, { reportNote = it; setCfg { c2 -> c2.copy(reportNote = it) } }, singleLine = false, minLines = 3, placeholder = "np. rozpoznanie, cel kuracji")
            LekiField("Zasady i notatki (przycisk Zasady u góry)", rules, { rules = it; store.update { st -> st.copy(rules = it) } }, singleLine = false, minLines = 5)
        }
    }
}

/** Moduł „Cykl”: pokazanie i ukrycie zakładki, wczytanie i zapis pliku z całą bazą, podsumowanie danych i ich usunięcie. Ukrycie niczego nie kasuje. */
@Composable
private fun CyklSection(s: AppState, store: Store, onOpen: () -> Unit, onOpenReport: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var incoming by remember { mutableStateOf<Cykl.Incoming?>(null) }
    val on = s.settings.cyklOn
    // dziennik może być własny albo wspólny z inną osobą; zmiany cudzego dziennika odświeżają ekran przez revision
    val rev by store.revision.collectAsStateWithLifecycle()
    val activeId = store.activeId.value
    val ownerId = store.cyklOwner(activeId)
    val shared = ownerId != activeId
    val people = store.profiles.value
    val cy = remember(rev, s, activeId) { store.cyklOf(activeId) }
    val sum = remember(cy.db) { Cykl.summary(cy.db) }
    val here = sum?.entries ?: 0

    // wczytanie pliku po stronie aplikacji: nie zależy od okna modułu (WebView), a robi to samo co jego „Import”
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { Platform.readBytesLimited(ctx, uri, Cykl.MAX_CHARS) }.getOrNull() }
            when {
                bytes == null -> toast("Nie można otworzyć pliku.")
                bytes.size > Cykl.MAX_CHARS -> toast("Plik jest za duży na dziennik cyklu.")
                else -> {
                    val text = bytes.toString(Charsets.UTF_8)
                    val r = withContext(Dispatchers.Default) { Cykl.readIncoming(text) }
                    if (r == null) toast("To nie jest plik z dziennikiem cyklu (potrzebna lista wpisów z typem i datą).") else incoming = r
                }
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching { Platform.writeToUri(ctx, uri, Cykl.exportText(store.cyklOf(store.activeId.value).db)) }.isSuccess
            toast(if (ok) "Zapisano dziennik cyklu" else "Nie udało się zapisać pliku.")
        }
    }

    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyText("Dziennik cyklu z prognozą okna płodnego, trendem długości cykli i raportem dla lekarza. Działa w całości na telefonie, bez internetu.")
            if (people.size > 1) Hint("Dotyczy osoby: ${people.firstOrNull { it.id == activeId }?.name ?: ""}.")
            SegRow(listOf("Ukryty", "Widoczny"), if (on) 1 else 0, { i -> store.update { it.copy(settings = it.settings.copy(cyklOn = i == 1)) } })
            Hint(if (on) "Zakładka „Cykl” jest na dolnym pasku, przed Raportem." else "Zakładka jest ukryta. Wpisane dane zostają w aplikacji i wchodzą do kopii zapasowej.")
            if (on) LekiButton("Otwórz moduł", onOpen, Modifier.fillMaxWidth(), primary = true, icon = Ph.CalendarBlank)
            if (!store.cyklOf(store.activeId.value).isEmpty) LekiButton("Raport dla lekarza z wykresami cyklu", onOpenReport, Modifier.fillMaxWidth(), tonal = true, icon = Ph.FileText)
        }
    }
    if (people.size > 1) {
        Spacer(Modifier.height(18.dp))
        Overline("Czyj dziennik")
        LekiCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val pickedByOthers = people.any { p -> p.id != activeId && store.profileState(p.id)?.settings?.cyklFrom == activeId }
                fun pick(id: String) {
                    when {
                        id.isNotEmpty() && store.profileState(id)?.settings?.cyklFrom?.isNotEmpty() == true -> toast("Ta osoba sama korzysta z cudzego dziennika.")
                        id.isNotEmpty() && pickedByOthers -> toast("Z dziennika tej osoby korzysta ktoś inny. Najpierw tamta osoba musi przejść na własny dziennik.")
                        else -> store.update { it.copy(settings = it.settings.copy(cyklFrom = id)) }
                    }
                }
                BodyText("Wspólny dziennik oznacza, że obie osoby widzą i zmieniają te same wpisy (np. gdy dziennik prowadzi jedna z nich). Dane są wtedy zapisane u osoby, od której pochodzą.")
                LekiButton("Własny dziennik tej osoby", { pick("") }, Modifier.fillMaxWidth(), primary = !shared, tonal = shared)
                people.filter { it.id != activeId }.forEach { p ->
                    LekiButton("Wspólny z osobą: ${p.name}", { pick(p.id) }, Modifier.fillMaxWidth(), primary = shared && ownerId == p.id, tonal = !(shared && ownerId == p.id))
                }
                if (shared) Hint("Teraz ten dziennik jest wspólny z osobą: ${people.firstOrNull { it.id == ownerId }?.name ?: ""}. Wpisy, import, eksport i raporty dotyczą jednego dziennika.")
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    Overline("Dziennik cyklu")
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val span = sum?.let { x -> x.first?.let { a -> x.last?.let { b -> ", od ${plDate(LocalDate.parse(a))} do ${plDate(LocalDate.parse(b))}" } } } ?: ""
            BodyText(
                if (sum == null || sum.entries == 0) "Brak zapisanych wpisów."
                else "Zapisano ${sum.entries} ${plN(sum.entries, "wpis", "wpisy", "wpisów")}, w tym ${sum.periods} ${plN(sum.periods, "początek okresu", "początki okresu", "początków okresu")}$span.",
            )
            LekiButton("Wczytaj plik z dziennikiem (JSON)", { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.UploadSimple)
            if (here > 0) LekiButton("Zapisz dziennik cyklu do pliku (JSON)", { saver.launch("cykl-baza-${LocalDate.now()}.json") }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.DownloadSimple)
            Hint("Plik może pochodzić z modułu (Dane → Pobierz kopię) albo z innej aplikacji do cyklu o tym samym formacie. Przy wczytaniu wybierasz: połączyć z istniejącymi wpisami albo zastąpić." + if (!on) " Po wczytaniu włącz widoczność modułu powyżej." else "")
            Hint("To dane zdrowotne. Są tylko na tym urządzeniu i w kopii zapasowej aplikacji (Kopie zapasowe i dane); zaszyfrowana kopia obejmuje także dziennik cyklu.")
            Hint("Eksport CSV nie zawiera dziennika cyklu. Raporty zawierają tylko opcjonalne, opisowe „Podsumowanie cyklu” (domyślnie wyłączone), nigdy wpisów intymnych.")
            if (cy.prev.isNotEmpty()) LekiButton("Przywróć poprzednią wersję dziennika", {
                val old = store.cyklOf(activeId)
                store.updateCykl(activeId) { c -> c.copy(db = c.prev, prev = c.db) }
                undo("Przywrócono poprzednią wersję dziennika") { store.updateCykl(activeId) { old } }
            }, Modifier.fillMaxWidth(), tonal = true)
            if (!cy.isEmpty) LinkButton("Usuń dane modułu Cykl", { confirm = true }, danger = true)
        }
    }
    incoming?.let { inc ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("Wczytać ${inc.entries} ${plN(inc.entries, "wpis", "wpisy", "wpisów")}" + store.profiles.value.let { ps -> if (ps.size > 1) " do dziennika osoby: " + (ps.firstOrNull { it.id == store.activeId.value }?.name ?: "") else "" } + "?", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    BodyText("Teraz w aplikacji: $here ${plN(here, "wpis", "wpisy", "wpisów")}." + if (inc.skipped > 0) " Pominięto ${inc.skipped} ${plN(inc.skipped, "wpis", "wpisy", "wpisów")} ze złą datą lub bez typu." else "")
                    LekiButton("Połącz (dodaj brakujące)", {
                        if (incoming == null) return@LekiButton            // drugie dotknięcie przed zamknięciem okna nic nie robi
                        val old = store.cyklOf(activeId)
                        val m = Cykl.merge(old.db, inc.db)
                        incoming = null
                        if (m == null || m.db.length > Cykl.MAX_CHARS) toast("Nie udało się połączyć danych (po połączeniu baza byłaby za duża).") else {
                            store.updateCykl(activeId) { c -> Cykl.merge(c.db, inc.db)?.let { r -> c.copy(db = r.db) } ?: c }
                            undo("Dodano ${m.added} ${plN(m.added, "nowy wpis", "nowe wpisy", "nowych wpisów")}") { store.updateCykl(activeId) { old } }
                        }
                    }, Modifier.fillMaxWidth(), primary = true)
                    LekiButton("Zastąp wszystko", {
                        if (incoming == null) return@LekiButton
                        val old = store.cyklOf(activeId)
                        incoming = null
                        // poprzednia baza zostaje jako zapas, tak jak w module
                        store.updateCykl(activeId) { c -> CyklData(db = inc.db, prev = c.db.ifEmpty { c.prev }) }
                        undo("Wczytano ${inc.entries} ${plN(inc.entries, "wpis", "wpisy", "wpisów")}") { store.updateCykl(activeId) { old } }
                    }, Modifier.fillMaxWidth(), tonal = true)
                    LinkButton("Anuluj", { incoming = null })
                }
            },
            confirmButton = {},
            containerColor = pal.surface,
        )
    }
    if (confirm) ConfirmDialog(
        "Usunąć dane modułu Cykl?", (if (shared) "Dziennik jest wspólny z inną osobą, więc zniknie także u niej. " else "") + "Znikną wszystkie wpisy dziennika cyklu z tego urządzenia, także poprzednia wersja bazy. Tej operacji nie da się cofnąć, chyba że masz kopię zapasową.",
        "Usuń dane", onConfirm = { store.updateCykl(activeId) { CyklData() }; toast("Usunięto dane modułu Cykl") }, onDismiss = { confirm = false }, danger = true,
    )
}

@Composable
private fun ReminderSection(s: AppState, store: Store) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val c = s.settings
    var tick by remember { mutableStateOf(0) }
    // po powrocie z ustawień systemowych odświeżamy stan zgód
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose {} }

    fun enable() {
        store.update { it.copy(settings = it.settings.copy(remindOn = true)) }
        toast("Przypomnienia włączone")
        if (!Reminders.canScheduleExact(ctx) && Build.VERSION.SDK_INT >= 31) {
            toast("Zezwól aplikacji na dokładne alarmy, inaczej przypomnienia mogą się spóźniać.")
            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    var testAfterPermission by remember { mutableStateOf(false) }
    // próbne powiadomienie: najpierw zgoda (Android 13+ pyta dopiero na żądanie), potem kanał, dopiero wtedy wysyłka
    fun runTest() {
        when {
            !Reminders.canNotify(ctx) || Reminders.channelBlocked(ctx) -> {
                toast("Powiadomienia tej aplikacji są wyłączone w systemie. Otwieram ustawienia, włącz je i spróbuj ponownie.")
                Reminders.openNotificationSettings(ctx)
            }
            else -> { Reminders.sendTest(ctx); toast("Pierwsze powiadomienie widać od razu, drugie (z alarmu) przyjdzie za kilka sekund.") }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (testAfterPermission) {
            testAfterPermission = false
            if (granted) runTest() else { toast("Brak zgody na powiadomienia. Otwieram ustawienia telefonu."); Reminders.openNotificationSettings(ctx) }
        } else if (granted) enable() else { toast("Brak zgody na powiadomienia. Włącz je w ustawieniach telefonu."); Reminders.openNotificationSettings(ctx) }
    }
    fun toggle() {
        if (c.remindOn) { store.update { it.copy(settings = it.settings.copy(remindOn = false)) }; return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else enable()
    }
    val planned = if (c.remindOn) s.reminders(ZoneId.systemDefault(), System.currentTimeMillis(), LocalDate.now()).count { it.id != REMINDER_BACKUP && it.id != REMINDER_STOCK } else 0
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyText("Powiadomienie o porach dawek z Twojego planu, także przy zamkniętej aplikacji. Pomija pory, które są już odhaczone.")
            LekiButton(if (c.remindOn) "Wyłącz przypomnienia" else "Włącz przypomnienia", { toggle() }, Modifier.fillMaxWidth(), primary = !c.remindOn)
            IntField("Ile minut przed porą dawki (0 = równo o porze)", c.remindLead, { v -> store.update { it.copy(settings = it.settings.copy(remindLead = v)) } }, 0..120)
            LinkButton("Wyślij testowe powiadomienie", {
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    testAfterPermission = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else runTest()
            })
            Hint("Test wysyła dwa powiadomienia: od razu i po kilku sekundach z alarmu. Gdy przyjdzie tylko pierwsze, telefon wstrzymuje aplikację w tle (np. Realme, Oppo, Xiaomi): zezwól jej na pracę w tle i autostart.")
            LinkButton("Ustawienia aplikacji w telefonie", { Reminders.openAppSettings(ctx) })
            val notifyOk = tick >= 0 && Reminders.canNotify(ctx)
            if (c.remindOn) Hint(if (!notifyOk) "Powiadomienia są wyłączone w ustawieniach telefonu, więc przypomnienia się nie pokażą." else "Zaplanowane przypomnienia: $planned.")
            if (c.remindOn && !notifyOk) LekiButton("Zezwól na powiadomienia", {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.fillMaxWidth(), tonal = true)
            if (c.remindOn && Build.VERSION.SDK_INT >= 31 && tick >= 0 && !Reminders.canScheduleExact(ctx)) LekiButton("Zezwól na dokładne alarmy", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.fillMaxWidth(), tonal = true)
        }
    }
    Spacer(Modifier.height(18.dp))
    Overline("Zapas leków i kopia zapasowa")
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BodyText("Te powiadomienia działają niezależnie od przypomnień o dawkach (wystarczy zgoda na powiadomienia). Zapas leków aplikacja liczy, gdy wpiszesz w edycji leku, ile zostało w opakowaniu.")
            IntField("Ostrzegaj o kończącym się leku, gdy starczy na tyle dni lub mniej (0 = wyłączone)", c.stockWarnDays, { v -> store.update { it.copy(settings = it.settings.copy(stockWarnDays = v)) } }, 0..60)
            IntField("Przypominaj o kopii zapasowej, gdy ostatnia jest starsza niż tyle dni (0 = wyłączone)", c.backupRemindDays, { v -> store.update { it.copy(settings = it.settings.copy(backupRemindDays = v)) } }, 0..365)
            if (!Reminders.canNotify(ctx)) {
                Hint("Powiadomienia są wyłączone w ustawieniach telefonu, więc te przypomnienia się nie pokażą.")
                LekiButton("Zezwól na powiadomienia", {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.fillMaxWidth(), tonal = true)
            }
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
        if (e != null) LinkButton("Usuń kontrolę", { onDelete(e.id) }, danger = true)
    }
}


/** Eksport do arkusza (CSV: średnik, przecinek dziesiętny, UTF-8 z BOM): ciśnienie, wyniki badań, dawki i podsumowanie EKG. */
@Composable
private fun CsvSection(s: AppState) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    fun share(name: String, csv: String) {
        runCatching { Platform.shareFile(ctx, name, csv, "text/csv", "Eksport CSV") }.onFailure { toast("Nie udało się przygotować pliku.") }
    }
    val today = LocalDate.now()
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Eksport do arkusza (CSV)", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Hint("Plik otworzysz w Excelu, LibreOffice lub Arkuszach Google. Zawiera dane zdrowotne: wysyłaj go tylko tam, gdzie chcesz.")
            LekiButton("Ciśnienie (${s.bp.size})", { share("cisnienie-$today.csv", Csv.bp(s)) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Table, enabled = s.bp.isNotEmpty())
            LekiButton("Wyniki badań (${s.labs.size})", { share("wyniki-badan-$today.csv", Csv.labs(s)) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Table, enabled = s.labs.isNotEmpty())
            LekiButton("Dawki leków (ostatnie 90 dni)", { share("dawki-$today.csv", Csv.doses(s, today.minusDays(89), today, today)) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Table, enabled = s.meds.isNotEmpty())
            LekiButton("EKG, podsumowanie (${s.ecgs.size})", { share("ekg-$today.csv", Csv.ecg(s)) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Table, enabled = s.ecgs.isNotEmpty())
        }
    }
}

/** Blokada aplikacji (odcisk palca, twarz albo blokada ekranu telefonu). */
@Composable
private fun SecuritySection() {
    val lock = LocalLock.current
    val toast = LocalToast.current
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lock == null) { BodyText("Blokada jest niedostępna na tym ekranie."); return@Column }
            BodyText("Blokada chroni dane zdrowotne przed osobą, która ma odblokowany Twój telefon. Odblokowujesz odciskiem palca, twarzą albo blokadą ekranu telefonu (PIN, wzór, hasło). Aplikacja nie widzi ani nie przechowuje Twojego PIN-u ani odcisku.")
            if (!lock.enabled) {
                LekiButton("Włącz blokadę aplikacji", {
                    if (!lock.available()) toast("Najpierw ustaw blokadę ekranu albo odcisk palca w ustawieniach telefonu.")
                    else lock.enable { ok -> toast(if (ok) "Blokada włączona." else "Nie włączono blokady.") }
                }, Modifier.fillMaxWidth(), primary = true, icon = Ph.LockKey)
            } else {
                Text("Blokada jest włączona.", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
                Column {
                    Text("Zablokuj po wyjściu z aplikacji na dłużej niż", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    SegRow(AppLock.GRACE_LABELS, lock.graceIndex, { lock.setGrace(it) })
                    Hint("Wybranie pliku (kopia, wyniki, EKG) czy zrobienie zdjęcia wyników zamyka aplikację na chwilę: przy „Zaraz” trzeba będzie ją odblokować po powrocie.", Modifier.padding(top = 6.dp))
                }
                CheckRow("Ukryj w ostatnich aplikacjach i blokuj zrzuty ekranu", lock.secureWindow, { lock.setSecure(it) }, subtitle = "Zrzut ekranu nie zadziała, dopóki ta opcja jest włączona.")
                LekiButton("Wyłącz blokadę", { lock.disable { ok -> if (ok) toast("Blokada wyłączona.") } }, Modifier.fillMaxWidth())
            }
            Hint("Ustawienia blokady są tylko na tym telefonie i nie trafiają do kopii zapasowej, żeby kopia wczytana na innym telefonie nie mogła zamknąć Ci dostępu.")
        }
    }
}

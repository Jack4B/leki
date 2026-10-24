package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.BackupCrypto
import pl.asystent.leki.core.Cykl
import pl.asystent.leki.core.applyLabsMerge
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.previewLabsMerge
import java.time.LocalDate

private sealed class Pending {
    data class Replace(val all: List<AppState>) : Pending() { val next: AppState get() = all.first() }
    data class Labs(val file: Backup.LabsFile) : Pending()
    data class Enc(val env: JsonElement) : Pending()
}

/**
 * Wybór pliku z kopią i obsługa wszystkich jego rodzajów: zwykła kopia, kopia zaszyfrowana hasłem i plik z samymi wynikami badań.
 * Zwraca funkcję otwierającą wybór pliku; okna potwierdzeń pojawiają się same.
 */
/** Dopisek do wiersza osoby w oknie wczytywania kopii: ile wpisów ma dziennik cyklu w pliku (gdy ma). */
private fun cyklNote(next: AppState): String {
    val inFile = Cykl.summary(next.cykl.db)?.entries ?: 0
    return if (inFile == 0) "" else ", dziennik cyklu: $inFile ${plN(inFile, "wpis", "wpisy", "wpisów")}"
}

@Composable
fun rememberImporter(s: AppState, store: Store): () -> Unit {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun handle(text: String) {
        // plik z dziennikiem cyklu (z modułu albo z innej aplikacji do cyklu) wczytuje się w module, bo tam jest łączenie z istniejącymi wpisami
        if (Cykl.looksLikeDb(text)) {
            toast(if (s.settings.cyklOn) "To plik z dziennikiem cyklu. Wczytaj go w module Cykl: Dane → Import." else "To plik z dziennikiem cyklu. Włącz moduł w Ustawienia → Moduł Cykl i wczytaj go tam.")
            return
        }
        try {
            val env = Backup.parseEnvelope(text)
            pending = when (Backup.kindOf(env)) {
                "enc" -> Pending.Enc(env)
                "labs" -> {
                    val f = Backup.parseLabsFile(env)
                    if (f.labs.isEmpty()) { toast("W pliku nie ma wyników."); null } else Pending.Labs(f)
                }
                else -> Pending.Replace(Backup.parseAll(env))
            }
        } catch (e: Backup.FormatException) {
            toast("To nie jest plik kopii z tej aplikacji.")
        } catch (e: Exception) {
            toast("Nie udało się wczytać pliku.")
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { Platform.readFromUri(ctx, uri) } }
            read.onSuccess { handle(it) }.onFailure { e ->
                toast(if (e is Backup.TooLargeException) e.message.orEmpty() else "Nie można otworzyć pliku.")
            }
        }
    }

    val undo = LocalUndo.current
    /** Wczytanie kopii zamiast wszystkiego, co jest na telefonie, z migawką w pamięci do natychmiastowego cofnięcia. */
    fun replaceWithUndo(list: List<AppState>) {
        val before = store.allStates()
        val snap = store.exportStates().mapIndexed { i, st -> st.copy(lastBackup = before[i].second.lastBackup) }
        store.replaceAll(list)
        undo("Wczytano kopię. Poprzednie dane można jeszcze przywrócić.") { store.replaceAll(snap) }
    }
    when (val p = pending) {
        is Pending.Replace -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Wczytać tę kopię?", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    BodyText("W pliku:")
                    p.all.forEach { a ->
                        BodyText("• ${a.patient.ifBlank { "Osoba bez imienia" }}: ${a.meds.size} ${plN(a.meds.size, "lek", "leki", "leków")}, ${a.bp.size} ${plN(a.bp.size, "pomiar", "pomiary", "pomiarów")} ciśnienia, ${a.labs.size} ${plN(a.labs.size, "wynik", "wyniki", "wyników")} badań${cyklNote(a)}")
                    }
                    // świeża aplikacja bez żadnych danych: zwykłe wczytanie, bez wyboru
                    val emptyHere = store.allStates().size == 1 && s.meds.isEmpty() && s.bp.isEmpty() && s.labs.isEmpty() && s.days.isEmpty() && s.ecgs.isEmpty() && s.analyses.isEmpty() && s.cykl.isEmpty && s.events.isEmpty() && s.patient.isBlank() && s.rules.isBlank()
                    if (emptyHere) {
                        LekiButton("Wczytaj kopię", { replaceWithUndo(p.all); pending = null }, Modifier.fillMaxWidth(), primary = true)
                        LinkButton("Anuluj", { pending = null })
                    } else {
                        val here = store.profiles.value
                        val room = Backup.MAX_PROFILES - here.size
                        val known = here.map { it.name.trim().lowercase() }.toSet()
                        BodyText("„Dodaj osoby z pliku” niczego nie usuwa: osoby z kopii pojawią się obok obecnych, a ich przypomnienia o lekach zaczną działać na tym telefonie. „Zastąp wszystkie dane” usuwa z tego telefonu: ${here.joinToString(", ") { it.name }} (wszystkie leki, pomiary, wyniki${if (store.allStates().any { (Cykl.summary(it.second.cykl.db)?.entries ?: 0) > 0 }) " i dzienniki cyklu" else ""}), a potem wczytuje kopię. Cofnąć można tylko od razu, przyciskiem „Cofnij”. Aby najpierw zrobić kopię, wybierz Anuluj i użyj Ustawienia → Kopie zapasowe i dane.")
                        if (p.all.all { it.patient.trim().lowercase() in known }) Hint("Wszystkie te osoby już są na tym telefonie. „Dodaj” utworzy duplikaty z dopiskiem „(z pliku)”. Aby odtworzyć dane z kopii, użyj „Zastąp”.")
                        else if (p.all.any { it.patient.trim().lowercase() in known }) Hint("Co najmniej jedno imię z pliku jest już na telefonie: taka osoba zostanie dodana z dopiskiem „(z pliku)”.")
                        if (room < p.all.size) Hint("Limit osób to ${Backup.MAX_PROFILES}. Na tym telefonie zostało miejsce dla: $room, a w pliku jest osób: ${p.all.size}. Usuń osobę (Ustawienia → Osoby) albo zastąp dane.")
                        LekiButton("Dodaj osoby z pliku", {
                            val ids = store.addProfilesFrom(p.all)
                            pending = null
                            if (ids.isEmpty()) toast("Nie dodano nikogo: osiągnięto limit osób (${Backup.MAX_PROFILES}).")
                            else {
                                store.switchTo(ids.first())
                                toast("Dodano ${ids.size} ${plN(ids.size, "osobę", "osoby", "osób")} z pliku. Teraz widać dane: ${store.profiles.value.firstOrNull { it.id == ids.first() }?.name.orEmpty()}.")
                            }
                        }, Modifier.fillMaxWidth(), primary = true, enabled = room >= p.all.size)
                        LekiButton("Anuluj", { pending = null }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        LekiButton("Zastąp wszystkie dane", { replaceWithUndo(p.all); pending = null }, Modifier.fillMaxWidth(), danger = true)
                    }
                }
            },
            confirmButton = {},
            containerColor = pal.surface,
        )
        is Pending.Labs -> {
            val pr = s.previewLabsMerge(p.file)
            ConfirmDialog(
                "Dołączyć wyniki badań?",
                "Nowych wyników: ${pr.fresh}" + (if (pr.skipped > 0) ". Pominę ${pr.skipped} już zapisanych (ten sam parametr i dzień)" else "") + (if (pr.newMarkers > 0) ". Nowych parametrów: ${pr.newMarkers}" else "") +
                    ". Zakresy odniesienia parametrów z pliku zostaną ustawione według laboratorium. Twoje pozostałe dane się nie zmienią.",
                "Dołącz", onConfirm = { store.update { it.applyLabsMerge(p.file) }; toast("Dodano ${pr.fresh} wyników") }, onDismiss = { pending = null },
            )
        }
        is Pending.Enc -> DecryptDialog(p.env, onDecrypted = { handle(it) }, onDismiss = { pending = null })
        null -> {}
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
}

@Composable
private fun DecryptDialog(env: JsonElement, onDecrypted: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Odszyfruj kopię", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BodyText("Wpisz hasło użyte przy tworzeniu kopii.")
                LekiField("Hasło", pw, { pw = it; err = "" }, password = true)
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (pw.isEmpty()) { err = "Wpisz hasło."; return@TextButton }
                busy = true; err = ""
                scope.launch {
                    val r = withContext(Dispatchers.Default) { runCatching { BackupCrypto.decrypt(pw, env) } }
                    busy = false
                    r.onSuccess { onDismiss(); onDecrypted(it) }.onFailure { err = "Złe hasło albo uszkodzony plik." }
                }
            }) { Text(if (busy) "Odszyfrowuję…" else "Odszyfruj", color = pal.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

/** Tworzenie zaszyfrowanej kopii: hasło, szyfrowanie na urządzeniu, potem zapis lub udostępnienie pliku. */
@Composable
fun EncryptBackupDialog(s: AppState, store: Store, only: Set<String>? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf<String?>(null) }
    val name = "leki-${LocalDate.now()}${if (only != null) "-wybrane" else ""}.zaszyfrowane.json"
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = out
        if (uri != null && data != null) {
            val ok = runCatching { Platform.writeToUri(ctx, uri, data) }.isSuccess
            toast(if (ok) "Zapisano zaszyfrowaną kopię" else "Nie udało się zapisać pliku.")
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (out == null) "Zaszyfrowana kopia" else "Kopia gotowa", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val who = store.allStates().filter { only == null || it.first.id in only }.joinToString(", ") { it.first.name }
                BodyText("Kopia obejmie: ${if (only == null && store.profiles.value.size > 1) "wszystkie osoby ($who)" else who}.")
                if (only != null && store.allStates().any { (pr, _) -> pr.id in only && store.cyklOwner(pr.id) !in only }) Hint("Do kopii trafi też dziennik cyklu innej osoby, z którego korzysta ktoś z wybranych.")
                if (out == null) {
                    BodyText("Dane zostaną zaszyfrowane na tym urządzeniu hasłem, które wpiszesz poniżej. Plik możesz bezpiecznie zapisać w chmurze (Dysk Google, Filen). Hasła nie da się odzyskać: bez niego plik jest nie do odczytania.")
                    LekiField("Hasło (min. 8 znaków)", pw, { pw = it; err = "" }, password = true)
                    LekiField("Powtórz hasło", pw2, { pw2 = it; err = "" }, password = true)
                    ErrorText(err)
                } else {
                    BodyText("Plik jest zaszyfrowany. Zapisz go w chmurze albo na dysku. Zapamiętaj hasło, bo bez niego plik nie otworzy się nigdzie. Jeśli plik trafia do kogoś innego, hasło przekaż osobno, nie w tej samej wiadomości.")
                    LekiButton("Udostępnij (Dysk Google, Filen…)", { Platform.shareFile(ctx, name, out!!, "application/json", "Kopia Asystenta leków (zaszyfrowana)") }, Modifier.fillMaxWidth(), primary = true)
                    LekiButton("Zapisz w pliku", { saver.launch(name) }, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (out == null) TextButton(enabled = !busy, onClick = {
                when {
                    pw.length < 8 -> err = "Hasło musi mieć co najmniej 8 znaków."
                    pw != pw2 -> err = "Hasła się różnią."
                    else -> {
                        busy = true
                        val snapshot = store.backupText(only = only)
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { runCatching { BackupCrypto.encrypt(pw, snapshot) } }
                            busy = false
                            r.onSuccess { out = it; store.markBackup(LocalDate.now(), only) }.onFailure { err = "Nie udało się zaszyfrować na tym urządzeniu." }
                        }
                    }
                }
            }) { Text(if (busy) "Szyfruję…" else "Zaszyfruj", color = pal.accent, fontWeight = FontWeight.SemiBold) }
            else TextButton(onClick = onDismiss) { Text("Zamknij", color = pal.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { if (out == null) TextButton(enabled = !busy, onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

/** Zapis zwykłej kopii JSON do pliku wybranego przez użytkownika. */
@Composable
fun rememberJsonSaver(s: AppState, store: Store, only: () -> Set<String>? = { null }): () -> Unit {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var content by remember { mutableStateOf<String?>(null) }
    var savedOnly by remember { mutableStateOf<Set<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = content
        if (uri != null && data != null) {
            val ok = runCatching { Platform.writeToUri(ctx, uri, data) }.isSuccess
            toast(if (ok) "Zapisano plik JSON" else "Nie udało się zapisać pliku.")
            if (ok) store.markBackup(LocalDate.now(), savedOnly)
        }
    }
    return {
        savedOnly = only()
        content = store.backupText(only = savedOnly)
        launcher.launch("leki-kopia-${LocalDate.now()}${if (savedOnly != null) "-wybrane" else ""}.json")
    }
}

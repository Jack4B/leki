package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import pl.asystent.leki.core.applyLabsMerge
import pl.asystent.leki.core.previewLabsMerge
import java.time.LocalDate

private sealed class Pending {
    data class Replace(val next: AppState) : Pending()
    data class Labs(val file: Backup.LabsFile) : Pending()
    data class Enc(val env: JsonElement) : Pending()
}

/**
 * Wybór pliku z kopią i obsługa wszystkich jego rodzajów: zwykła kopia, kopia zaszyfrowana hasłem i plik z samymi wynikami badań.
 * Zwraca funkcję otwierającą wybór pliku; okna potwierdzeń pojawiają się same.
 */
@Composable
fun rememberImporter(s: AppState, store: Store): () -> Unit {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun handle(text: String) {
        try {
            val env = Backup.parseEnvelope(text)
            pending = when (Backup.kindOf(env)) {
                "enc" -> Pending.Enc(env)
                "labs" -> {
                    val f = Backup.parseLabsFile(env)
                    if (f.labs.isEmpty()) { toast("W pliku nie ma wyników."); null } else Pending.Labs(f)
                }
                else -> Pending.Replace(Backup.parseState(env))
            }
        } catch (e: Backup.FormatException) {
            toast("To nie jest plik kopii z tej aplikacji.")
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { Platform.readFromUri(ctx, uri) }.getOrNull() }
            if (text == null) toast("Nie można otworzyć pliku.") else handle(text)
        }
    }

    when (val p = pending) {
        is Pending.Replace -> ConfirmDialog(
            "Wczytać tę kopię?",
            "W pliku: ${p.next.meds.size} leków, ${p.next.days.size} dni z zapisami, ${p.next.bp.size} pomiarów ciśnienia. Wczytanie zastąpi wszystkie dane na tym urządzeniu. Jeśli masz tu coś ważnego, najpierw zrób kopię.",
            "Zastąp dane", onConfirm = { store.replace(p.next); toast("Wczytano kopię danych") }, onDismiss = { pending = null },
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
        title = { Text("Zaszyfrowana kopia", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
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
fun EncryptBackupDialog(s: AppState, store: Store, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf<String?>(null) }
    val name = "leki-${LocalDate.now()}.zaszyfrowane.json"
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
                if (out == null) {
                    BodyText("Dane zostaną zaszyfrowane na tym urządzeniu hasłem, które wpiszesz poniżej. Plik możesz bezpiecznie zapisać w chmurze (Dysk Google, Filen). Hasła nie da się odzyskać: bez niego plik jest nie do odczytania.")
                    LekiField("Hasło (min. 8 znaków)", pw, { pw = it; err = "" }, password = true)
                    LekiField("Powtórz hasło", pw2, { pw2 = it; err = "" }, password = true)
                    ErrorText(err)
                } else {
                    BodyText("Plik jest zaszyfrowany. Zapisz go w chmurze albo na dysku. Zapamiętaj hasło, bo bez niego plik nie otworzy się nigdzie.")
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
                        val snapshot = s.copy(lastBackup = LocalDate.now())
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { runCatching { BackupCrypto.encrypt(pw, Backup.export(snapshot)) } }
                            busy = false
                            r.onSuccess { out = it; store.update { st -> st.copy(lastBackup = LocalDate.now()) } }.onFailure { err = "Nie udało się zaszyfrować na tym urządzeniu." }
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
fun rememberJsonSaver(s: AppState, store: Store): () -> Unit {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var content by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = content
        if (uri != null && data != null) {
            val ok = runCatching { Platform.writeToUri(ctx, uri, data) }.isSuccess
            toast(if (ok) "Zapisano plik JSON" else "Nie udało się zapisać pliku.")
            if (ok) store.update { it.copy(lastBackup = LocalDate.now()) }
        }
    }
    return {
        content = Backup.export(s.copy(lastBackup = LocalDate.now()))
        launcher.launch("leki-cisnienie-${LocalDate.now()}.json")
    }
}

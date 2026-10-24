package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import pl.asystent.leki.AutoBackup
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.plDate
import java.time.LocalDate

/** Automatyczna kopia do wybranego folderu (Ustawienia → Kopie zapasowe i dane): wybór folderu, częstotliwość, szyfrowanie hasłem, stan i ręczne uruchomienie. */
@Composable
fun AutoBackupSection(s: AppState, store: Store) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    // w podglądach (testy renderowania) ustawienia systemowe mogą być niedostępne: wtedy pusta konfiguracja
    val cfg = remember(rev, s.lastBackup) { runCatching { AutoBackup.config(ctx) }.getOrDefault(AutoBackup.Config(null, 1, false, false, -1L, "")) }
    var askPassword by remember { mutableStateOf(false) }
    var confirmPlain by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { AutoBackup.setFolder(ctx, uri); rev++; askPassword = true }
    }
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Automatyczna kopia do folderu", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            val folder = cfg.folder
            if (folder == null) {
                BodyText("Aplikacja sama zapisze kopię w wybranym folderze (np. takim, który inna aplikacja synchronizuje z chmurą), w tle po zmianie danych. Aplikacja nadal nie łączy się z internetem. Zostaje 7 ostatnich kopii.")
                LekiButton("Wybierz folder", { picker.launch(null) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.FloppyDisk)
            } else {
                BodyText("Folder: ${AutoBackup.folderLabel(folder)}")
                SegRow(listOf("Codziennie", "Co tydzień"), if (cfg.intervalDays <= 1) 0 else 1, { AutoBackup.setInterval(ctx, if (it == 0) 1 else 7); rev++ })
                Row { ToggleChip("Szyfruj hasłem (zalecane)", cfg.encrypted) { if (cfg.encrypted) confirmPlain = true else askPassword = true } }
                if (!cfg.encrypted) Hint("Bez hasła kopia to zwykły plik z danymi zdrowotnymi. Jeśli folder jest synchronizowany z chmurą, włącz szyfrowanie.")
                else if (!cfg.hasPassword) Hint("Brak zapisanego hasła, więc kopia się nie zrobi. Ustaw je ponownie.")
                Hint(
                    (if (cfg.lastDay >= 0) "Ostatnia automatyczna kopia: " + plDate(LocalDate.ofEpochDay(cfg.lastDay)) + ". " else "Pierwsza kopia powstanie przy najbliższej zmianie danych. ") +
                        "Kopię wczytasz w Ustawienia → Kopie zapasowe i dane → Wczytaj kopię z pliku." + if (cfg.status.isNotEmpty()) "\n" + cfg.status else "",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LekiButton(if (busy) "Zapisuję…" else "Zrób teraz", {
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { AutoBackup.runNow(ctx, store.allStates().map { it.second }) }
                            busy = false
                            r.onSuccess { store.markBackup(LocalDate.now()); toast("Zapisano kopię: $it") }.onFailure { toast("Nie udało się zrobić kopii.") }
                            rev++
                        }
                    }, Modifier.weight(1f), tonal = true, enabled = !busy)
                    LekiButton("Wyłącz", { AutoBackup.disable(ctx); rev++; toast("Wyłączono automatyczną kopię") }, Modifier.weight(1f), danger = true)
                }
            }
        }
    }
    if (confirmPlain) ConfirmDialog(
        "Wyłączyć szyfrowanie?", "Kolejne kopie będą zwykłymi plikami z danymi zdrowotnymi. Jeśli folder jest synchronizowany z chmurą, trafią tam bez zabezpieczenia.", "Wyłącz szyfrowanie",
        onConfirm = { AutoBackup.setEncryption(ctx, null); rev++; confirmPlain = false }, onDismiss = { confirmPlain = false }, danger = true,
    )
    if (askPassword) PasswordDialog(
        onSet = { pw -> AutoBackup.setEncryption(ctx, pw); rev++; askPassword = false; toast("Kopie będą szyfrowane. Zapamiętaj hasło: bez niego nie otworzysz pliku.") },
        onSkip = { askPassword = false },
    )
}

@Composable
private fun PasswordDialog(onSet: (String) -> Unit, onSkip: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        // zamknięcie dotknięciem obok okna nie wybiera „Bez hasła”: rezygnacja z szyfrowania wymaga świadomego przycisku
        onDismissRequest = {},
        title = { Text("Hasło do kopii", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BodyText("Kopie będą szyfrowane tym hasłem na telefonie, zanim trafią do folderu. Hasło jest przechowywane na tym telefonie w zabezpieczonym magazynie systemu. Zapamiętaj je: bez niego plik nie otworzy się nigdzie.")
                LekiField("Hasło (min. 8 znaków)", pw, { pw = it; err = "" }, password = true)
                LekiField("Powtórz hasło", pw2, { pw2 = it; err = "" }, password = true)
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pw.length < 8 -> err = "Hasło musi mieć co najmniej 8 znaków."
                    pw != pw2 -> err = "Hasła się różnią."
                    else -> onSet(pw)
                }
            }) { Text("Włącz szyfrowanie", color = pal.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Bez hasła", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

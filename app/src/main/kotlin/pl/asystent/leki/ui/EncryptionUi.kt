package pl.asystent.leki.ui

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.BackupCrypto
import java.time.LocalDate

/**
 * Szyfrowanie danych na telefonie (opcjonalne, domyślnie wyłączone; plan: docs/plan-szyfrowanie.md). Włączenie wymaga zaliczonej
 * kopii z hasłem zapisanej poza katalogiem aplikacji: aplikacja czyta ją z powrotem, odszyfrowuje hasłem i porównuje ze stanem w pamięci.
 */
@Composable
fun EncryptionSection(store: Store) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val encrypted by store.encrypted.collectAsState()
    var enableDialog by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // w podglądzie (Paparazzi) usługa blokady ekranu bywa niedostępna: wtedy nie pokazujemy ostrzeżenia
    val secure = remember { runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure ?: true }.getOrDefault(true) }
    LekiCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Szyfrowanie danych na tym telefonie (opcjonalne)", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            BodyText(if (encrypted) "Włączone: dane osób są zaszyfrowane kluczem zapisanym w układzie zabezpieczeń telefonu." else "Wyłączone: dane leżą w pamięci aplikacji jako zwykły plik (chroni je system Android).")
            Hint("Funkcja eksperymentalna. Zaszyfrowane dane da się odczytać tylko na tym telefonie: przy zmianie telefonu albo utracie klucza jedyną drogą jest kopia zapasowa z hasłem. Przed włączeniem aplikacja wymaga sprawdzonej kopii z hasłem.")
            if (!secure) Hint("Ten telefon nie ma blokady ekranu, więc ochrona jest słabsza. Ustaw blokadę w ustawieniach telefonu.")
            if (encrypted) LinkButton(if (busy) "Wyłączam…" else "Wyłącz szyfrowanie", { if (!busy) confirmOff = true })
            else LinkButton("Włącz szyfrowanie…", { enableDialog = true })
        }
    }
    if (enableDialog) EnableEncryptionDialog(store) { enableDialog = false }
    if (confirmOff) ConfirmDialog(
        "Wyłączyć szyfrowanie?", "Dane zostaną odszyfrowane i zapisane jako zwykłe pliki w pamięci aplikacji, a klucz zostanie usunięty z telefonu.",
        "Wyłącz", onConfirm = {
            confirmOff = false; busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { store.disableEncryption() }
                busy = false
                toast(when (r) {
                    Store.CryptoResult.OK -> "Szyfrowanie wyłączone"
                    Store.CryptoResult.ROLLED_BACK -> "Nie udało się odszyfrować danych. Dane zostały bez zmian (zaszyfrowane)."
                    else -> "Nie udało się wyłączyć szyfrowania. Uruchom aplikację ponownie; dane są bezpieczne."
                })
            }
        }, onDismiss = { confirmOff = false },
    )
}

private enum class Step { Password, Writing, Verifying }

/** Włączanie: hasło, kopia z hasłem do pliku wybranego przez użytkownika, odczyt zwrotny i porównanie, dopiero potem migracja. */
@Composable
private fun EnableEncryptionDialog(store: Store, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(Step.Password) }
    var snapshot by remember { mutableStateOf<String?>(null) }
    var sealed by remember { mutableStateOf<String?>(null) }
    val busy = step != Step.Password

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val data = sealed; val snap = snapshot
        if (uri == null || data == null || snap == null) { step = Step.Password; err = "Nie wybrano pliku. Szyfrowanie nie zostało włączone."; return@rememberLauncherForActivityResult }
        step = Step.Verifying
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Platform.writeToUri(ctx, uri, data)
                    // odczyt zwrotny z miejsca zapisu: odszyfrowanie hasłem i porównanie ze stanem w pamięci
                    val back = Platform.readFromUri(ctx, uri)
                    val plain = BackupCrypto.decrypt(pw, Backup.parseEnvelope(back))
                    Backup.parseAll(plain) == Backup.parseAll(snap)
                }.getOrDefault(false)
            }
            if (!ok) { step = Step.Password; err = "Nie udało się sprawdzić kopii (odczyt z pliku nie zgadza się ze stanem aplikacji). Szyfrowanie nie zostało włączone."; return@launch }
            store.markBackup(LocalDate.now())
            val r = withContext(Dispatchers.IO) { store.enableEncryption() }
            step = Step.Password
            when (r) {
                Store.CryptoResult.OK -> { toast("Szyfrowanie włączone"); onDismiss() }
                Store.CryptoResult.ROLLED_BACK -> err = "Migracja się nie powiodła i została cofnięta. Dane zostały bez zmian."
                Store.CryptoResult.NOT_ALLOWED -> err = "Szyfrowanie nie może być teraz włączone."
                Store.CryptoResult.FAILED -> err = "Nie udało się włączyć szyfrowania. Jeśli aplikacja jest tylko do odczytu, uruchom ją ponownie; dane są bezpieczne."
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Włącz szyfrowanie", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BodyText("Najpierw powstanie kopia zapasowa zaszyfrowana hasłem, w pliku poza aplikacją (wybierzesz miejsce). Aplikacja odczyta ją z powrotem i sprawdzi. Dopiero wtedy zaszyfruje dane na telefonie.")
                BodyText("Hasła nie da się odzyskać. Zapamiętaj je i zachowaj plik: to jedyna kopia niezależna od klucza w telefonie.")
                LekiField("Hasło do kopii (min. 8 znaków)", pw, { pw = it; err = "" }, password = true)
                LekiField("Powtórz hasło", pw2, { pw2 = it; err = "" }, password = true)
                if (step == Step.Writing) BodyText("Szyfruję kopię…")
                if (step == Step.Verifying) BodyText("Sprawdzam kopię i włączam szyfrowanie…")
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when {
                    pw.length < 8 -> err = "Hasło musi mieć co najmniej 8 znaków."
                    pw != pw2 -> err = "Hasła się różnią."
                    else -> {
                        step = Step.Writing; err = ""
                        val snap = store.backupText()
                        snapshot = snap
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { runCatching { BackupCrypto.encrypt(pw, snap) } }
                            r.onSuccess { sealed = it; saver.launch("leki-kopia-przed-szyfrowaniem-${LocalDate.now()}.zaszyfrowane.json") }
                                .onFailure { step = Step.Password; err = "Nie udało się zaszyfrować kopii na tym urządzeniu." }
                        }
                    }
                }
            }) { Text("Utwórz kopię i włącz", color = pal.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

/** Komunikat o problemie z zaszyfrowanymi danymi (klasy z planu) z przyciskiem „Zaczynam od nowa” tam, gdzie jest sensowny. */
@Composable
fun StorageProblemBanner(store: Store, modifier: Modifier = Modifier) {
    val problem by store.storageProblem.collectAsState()
    val p = problem ?: return
    // uszkodzony plik lub błąd uwierzytelnienia treści (klasy C i E) pokazuje zwykły komunikat o odtworzeniu na ekranie Leki
    if (p == pl.asystent.leki.core.storage.StorageProblem.FILE_CORRUPT || p == pl.asystent.leki.core.storage.StorageProblem.CONTENT_AUTH_FAILED) return
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var confirm by remember { mutableStateOf(false) }
    val canStartOver = p == pl.asystent.leki.core.storage.StorageProblem.KEYS_FILE_BROKEN || p == pl.asystent.leki.core.storage.StorageProblem.KEY_ALIAS_MISSING ||
        p == pl.asystent.leki.core.storage.StorageProblem.KEY_UNAVAILABLE || p == pl.asystent.leki.core.storage.StorageProblem.DEK_ID_MISMATCH
    AlertBox("Problem z zaszyfrowanymi danymi", body = p.message + if (store.readOnly) "\nAplikacja działa tylko do odczytu; kopię zapasową z hasłem możesz zrobić." else "", modifier = modifier) {
        if (canStartOver) LinkButton("Zaczynam od nowa", { confirm = true }, danger = true)
    }
    if (confirm) ConfirmDialog(
        "Zacząć od nowa?", "Powstanie nowy klucz, a dotychczasowe zaszyfrowane pliki zostaną odłożone jako nieczytelne (znikną dopiero przy »Usuń wszystkie dane«). Aplikacja zacznie z pustymi danymi; możesz wczytać kopię zapasową z hasłem.",
        "Zacznij od nowa", onConfirm = {
            confirm = false
            scope.launch { val ok = withContext(Dispatchers.IO) { store.startOver() }; toast(if (ok) "Zaczęto od nowa z nowym kluczem" else "Nie udało się zacząć od nowa") }
        }, onDismiss = { confirm = false }, danger = true,
    )
}

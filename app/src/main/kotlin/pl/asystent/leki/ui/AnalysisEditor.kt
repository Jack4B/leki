package pl.asystent.leki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.asystent.leki.core.AiImport
import pl.asystent.leki.core.Analysis
import pl.asystent.leki.core.Markdown
import pl.asystent.leki.core.deleteAnalysis
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.saveAnalysis
import java.time.LocalDate

/** Treść przekazana do edytora z zewnątrz („Udostępnij → Asystent leków” albo otwarcie pliku .md): tekst i/lub adres pliku. */
class IncomingAi(val text: String?, val uri: android.net.Uri?)

private const val LONG_TEXT = 2500
private const val SEP = "\u0001"

/**
 * Edytor odpowiedzi AI, która ma być uzupełnieniem raportu dla lekarza. Tekst wkleja się ze schowka; długą odpowiedź można skrócić,
 * wyłączając całe sekcje (nagłówki). Zapis trafia do kopii zapasowej razem z resztą danych.
 */
@Composable
fun AnalysisEditor(initial: Analysis?, store: Store, today: LocalDate, onClose: () -> Unit, prefill: IncomingAi? = null) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "Analiza AI") }
    var dateStr by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    // lista wykluczonych sekcji w jednym napisie, żeby przetrwała obrót ekranu
    var exclStr by rememberSaveable { mutableStateOf(initial?.excluded.orEmpty().joinToString(SEP)) }
    val excluded = if (exclStr.isEmpty()) emptyList() else exclStr.split(SEP)
    fun setExcluded(l: List<String>) { exclStr = l.joinToString(SEP) }

    val sections = remember(text) { Markdown.sections(text) }
    val shown = remember(text, excluded) { Markdown.selected(text, excluded) }

    // tekst czekający na potwierdzenie zastąpienia wpisanej treści (z podpisem źródła do komunikatu i propozycją tytułu)
    var incoming by remember { mutableStateOf<Triple<String, String, String?>?>(null) }

    fun acceptText(t: String, source: String, suggestedTitle: String?) {
        text = t
        // tytuł podpowiadamy tylko wtedy, gdy użytkownik go jeszcze nie zmieniał
        if (suggestedTitle != null && (title.isBlank() || title == "Analiza AI")) title = suggestedTitle.take(80)
        // długą odpowiedź od razu skracamy do podsumowania i pytań; użytkownik może to zmienić poniżej
        val ex = if (t.length > LONG_TEXT) Markdown.shortExclusions(t) else emptyList()
        setExcluded(ex)
        toast(if (ex.isEmpty()) "$source: wczytano tekst" else "$source: wczytano i skrócono do podsumowania i pytań")
    }
    fun offer(t: String, source: String, suggestedTitle: String?) {
        if (text.isNotBlank() && text.trim() != t) incoming = Triple(t, source, suggestedTitle) else acceptText(t, source, suggestedTitle)
    }
    fun paste() {
        val t = Platform.pasteText(ctx).trim()
        if (t.isEmpty()) { toast("Schowek jest pusty"); return }
        offer(t, "Ze schowka", null)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun handle(res: Result<AiImport.Result>, source: String) {
        res.onSuccess { r ->
            offer(r.text, source, r.title)
            if (r.fallbackCharset) toast("Plik nie był w UTF-8; odczytano go jako Windows-1250. Sprawdź polskie znaki.")
        }.onFailure { e -> toast(if (e is AiImport.ImportException) e.message.orEmpty() else "Nie udało się wczytać pliku.") }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { AiImport.decode(Platform.readBytesLimited(ctx, uri, AiImport.MAX_BYTES), Platform.displayName(ctx, uri)) }
            }
            handle(res, "Z pliku")
        }
    }
    // treść udostępniona z innej aplikacji: wczytujemy raz po otwarciu edytora (zapis i tak wymaga potwierdzenia przez użytkownika)
    LaunchedEffect(prefill) {
        if (prefill == null) return@LaunchedEffect
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = prefill.text?.toByteArray(Charsets.UTF_8) ?: Platform.readBytesLimited(ctx, prefill.uri ?: error("Brak treści"), AiImport.MAX_BYTES)
                AiImport.decode(bytes, prefill.uri?.let { Platform.displayName(ctx, it) })
            }
        }
        handle(res, "Udostępniono")
    }

    FullScreenDialog(
        if (initial == null) "Analiza AI do raportu" else "Edycja analizy",
        onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiButton("Anuluj", onClose, Modifier.weight(1f))
                LekiButton("Zapisz", {
                    val a = Analysis(initial?.id ?: newId(), LocalDate.parse(dateStr), title.trim().ifEmpty { "Analiza AI" }, text.trim(), excluded)
                    store.update { st -> st.saveAnalysis(a).let { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(analysis = true, analysisId = a.id))) } }
                    toast("Zapisano analizę, trafi do raportu dla lekarza")
                    onClose()
                }, Modifier.weight(1f), primary = true, enabled = text.isNotBlank())
            }
        },
    ) {
        BodyText("Wklej albo wczytaj z pliku odpowiedź modelu AI na raport z karty „Dla AI”. Zostanie dołączona na końcu raportu dla lekarza, wyraźnie opisana jako analiza AI, i zapisze się w kopii zapasowej. Jeśli model trzymał się formatu z polecenia, w raporcie zobaczysz kolorowe ramki (Pilne, Do omówienia, Informacja), tabele, plakietki ważności i wyróżnione sekcje: podsumowanie, sprawy pilne i pytania do lekarza.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LekiButton("Wklej ze schowka", { paste() }, Modifier.weight(1f), tonal = true, icon = Ph.Copy)
            LekiButton("Wczytaj z pliku", { picker.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream", "*/*")) }, Modifier.weight(1f), tonal = true, icon = Ph.UploadSimple)
        }
        Hint("Plik Markdown (.md) albo tekstowy (.txt) z odpowiedzią AI, do 1 MB. Polskie znaki w UTF-8, UTF-16 i Windows-1250 wczytają się poprawnie.")
        LekiField("Tytuł", title, { title = it.take(80) })
        DateField("Data analizy", LocalDate.parse(dateStr), { dateStr = it.toString() })
        LekiField("Treść (Markdown)", text, { text = it }, singleLine = false, minLines = 8, placeholder = "Tu wklej odpowiedź AI albo wczytaj ją z pliku. Wzory LaTeX i znaczniki Markdown zostaną uporządkowane w raporcie.")
        if (sections.size > 1) {
            Overline("Co trafi do raportu", Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Tylko podsumowanie i pytania", excluded.isNotEmpty() && excluded == Markdown.shortExclusions(text)) { setExcluded(Markdown.shortExclusions(text)) }
                ToggleChip("Całość", excluded.isEmpty()) { setExcluded(emptyList()) }
            }
            LekiCard {
                sections.forEachIndexed { i, sec ->
                    if (i > 0) Divider()
                    CheckRow(sec.heading, sec.heading !in excluded, { on -> setExcluded(if (on) excluded - sec.heading else (excluded + sec.heading).distinct()) }, subtitle = "${sec.body.length} znaków")
                }
            }
        }
        if (text.isNotBlank()) Hint("W raporcie: ${shown.length} z ${text.length} znaków.")
        if (initial != null) LinkButton("Usuń tę analizę", {
            store.update { it.deleteAnalysis(initial.id) }
            undo("Usunięto analizę") { store.update { it.saveAnalysis(initial) } }
            onClose()
        }, danger = true)
    }
    incoming?.let { (t, source, suggested) ->
        ConfirmDialog("Zastąpić wpisaną treść?", "W polu jest już tekst (${text.length} znaków). Nowy tekst ($source) ma ${t.length} znaków i go zastąpi.", "Zastąp",
            onConfirm = { acceptText(t, source, suggested); incoming = null }, onDismiss = { incoming = null })
    }
}

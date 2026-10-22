package pl.asystent.leki.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import pl.asystent.leki.core.Analysis
import pl.asystent.leki.core.Markdown
import pl.asystent.leki.core.deleteAnalysis
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.saveAnalysis
import java.time.LocalDate

private const val LONG_TEXT = 2500
private const val SEP = "\u0001"

/**
 * Edytor odpowiedzi AI, która ma być uzupełnieniem raportu dla lekarza. Tekst wkleja się ze schowka; długą odpowiedź można skrócić,
 * wyłączając całe sekcje (nagłówki). Zapis trafia do kopii zapasowej razem z resztą danych.
 */
@Composable
fun AnalysisEditor(initial: Analysis?, store: Store, today: LocalDate, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var title by rememberSaveable { mutableStateOf(initial?.title ?: "Analiza AI") }
    var dateStr by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    // lista wykluczonych sekcji w jednym napisie, żeby przetrwała obrót ekranu
    var exclStr by rememberSaveable { mutableStateOf(initial?.excluded.orEmpty().joinToString(SEP)) }
    val excluded = if (exclStr.isEmpty()) emptyList() else exclStr.split(SEP)
    fun setExcluded(l: List<String>) { exclStr = l.joinToString(SEP) }
    var confirmDelete by remember { mutableStateOf(false) }

    val sections = remember(text) { Markdown.sections(text) }
    val shown = remember(text, excluded) { Markdown.selected(text, excluded) }

    fun paste() {
        val t = Platform.pasteText(ctx).trim()
        if (t.isEmpty()) { toast("Schowek jest pusty"); return }
        text = t
        // długą odpowiedź od razu skracamy do podsumowania i pytań; użytkownik może to zmienić poniżej
        val ex = if (t.length > LONG_TEXT) Markdown.shortExclusions(t) else emptyList()
        setExcluded(ex)
        toast(if (ex.isEmpty()) "Wklejono tekst" else "Wklejono i skrócono do podsumowania i pytań")
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
        BodyText("Wklej odpowiedź modelu AI na raport z karty „Dla AI”. Zostanie dołączona na końcu raportu dla lekarza, wyraźnie opisana jako analiza AI, i zapisze się w kopii zapasowej. Jeśli model trzymał się formatu z polecenia, w raporcie zobaczysz kolorowe ramki (Pilne, Do omówienia, Informacja), tabele, plakietki ważności i wyróżnione sekcje: podsumowanie, sprawy pilne i pytania do lekarza.")
        LekiButton("Wklej ze schowka", { paste() }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Copy)
        LekiField("Tytuł", title, { title = it.take(80) })
        DateField("Data analizy", LocalDate.parse(dateStr), { dateStr = it.toString() })
        LekiField("Treść (Markdown)", text, { text = it }, singleLine = false, minLines = 8, placeholder = "Tu wklej odpowiedź AI. Wzory LaTeX i znaczniki Markdown zostaną uporządkowane w raporcie.")
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
        if (initial != null) LinkButton("Usuń tę analizę", { confirmDelete = true }, danger = true)
    }
    if (confirmDelete && initial != null) {
        ConfirmDialog("Usunąć analizę?", "Zniknie z aplikacji i z przyszłych kopii zapasowych.", "Usuń", onConfirm = { store.update { it.deleteAnalysis(initial.id) }; toast("Usunięto analizę"); onClose() }, onDismiss = { confirmDelete = false }, danger = true)
    }
}

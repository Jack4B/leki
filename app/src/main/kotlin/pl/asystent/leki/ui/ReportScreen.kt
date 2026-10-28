package pl.asystent.leki.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import pl.asystent.leki.BuildConfig
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.AiParts
import pl.asystent.leki.core.AiReport
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.BodyInfo
import pl.asystent.leki.core.DoctorReport
import pl.asystent.leki.core.EcgReport
import pl.asystent.leki.core.EcgSelection
import pl.asystent.leki.core.Markdown
import pl.asystent.leki.core.Period
import pl.asystent.leki.core.ReportParts
import pl.asystent.leki.core.firstDate
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.plDate
import java.time.LocalDate

internal fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(s: AppState, store: Store, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState(), initialMode: Int = 0) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    val undo = LocalUndo.current
    var preset by rememberSaveable { mutableStateOf(7) }
    var fromStr by rememberSaveable { mutableStateOf("") }
    var toStr by rememberSaveable { mutableStateOf("") }
    var customRange by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var preview by remember { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf("") }
    val first = s.firstDate(today)
    val custom = if (fromStr.isNotEmpty() || toStr.isNotEmpty()) (fromStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } to toStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }) else null
    val per = Period.resolve(preset, custom, today, first)
    // wyświetlamy stan z uwzględnieniem ukrytych modułów (ukryty moduł nie pokazuje swoich kart ani nie liczy się do „wybrano sekcje”)
    val rp = s.settings.effectiveRp()
    val ai = s.settings.effectiveAi()

    fun setRp(f: (ReportParts) -> ReportParts) = store.update { it.copy(settings = it.settings.copy(rp = f(it.settings.rp))) }
    fun setAi(f: (AiParts) -> AiParts) = store.update { it.copy(settings = it.settings.copy(ai = f(it.settings.ai))) }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Raport", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(bottom = 14.dp))
            if (mode == 0) {
                // jedno dotknięcie na wizytę: ostatnie 30 dni, ciśnienie, cała historia wyników, leki, kontrole, EKG i analiza AI (jeśli jest), od razu podgląd
                LekiCard(Modifier.padding(bottom = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Wizyta u lekarza", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        BodyText("Ustawia raport na ostatnie 30 dni z " + listOfNotNull("ciśnieniem".takeIf { s.settings.bpOn }, "całą historią wyników".takeIf { s.settings.labsOn }, "przyjmowaniem i listą leków", "kontrolami", "EKG".takeIf { s.settings.labsOn }).joinToString(", ") + (if (s.analyses.isNotEmpty()) " oraz analizą AI" else "") + " i od razu pokazuje podgląd do druku lub zapisu jako PDF.")
                        LekiButton("Przygotuj na wizytę", {
                            preset = 30; fromStr = ""; toStr = ""; customRange = false
                            setRp { it.copy(bp = true, labs = true, labsPeriod = false, meds = true, plan = true, events = true, analysis = s.analyses.isNotEmpty(), ecg = true) }
                            preview = true
                        }, Modifier.fillMaxWidth(), primary = true, icon = Ph.Stethoscope)
                    }
                }
            }
            LekiCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline("Okres", Modifier.padding(bottom = 0.dp))
                    SegRow(listOf("7 dni", "14 dni", "30 dni", "Wszystko"), if (custom != null) -1 else listOf(7, 14, 30, 0).indexOf(preset),
                        { preset = listOf(7, 14, 30, 0)[it]; fromStr = ""; toStr = ""; customRange = false })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(plDate(per.from) + " – " + plDate(per.to), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink), modifier = Modifier.weight(1f))
                        LinkButton(if (customRange) "Ukryj daty" else "Własny zakres", { customRange = !customRange })
                    }
                    if (customRange) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DateField("Od", per.from, { fromStr = it.toString(); if (toStr.isEmpty()) toStr = per.to.toString() }, Modifier.weight(1f))
                        DateField("Do", per.to, { toStr = it.toString(); if (fromStr.isEmpty()) fromStr = per.from.toString() }, Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 16.dp)) {
                SegRow(listOf("Dla lekarza", "Dla AI"), mode, { mode = it })
            }
        }
        if (mode == 0) {
            item {
                Column(Modifier.padding(top = 20.dp)) {
                    BodyText("Raport w formie tabel i wykresów do wydruku lub zapisu jako PDF, np. na wizytę.")
                    Overline("Co dołączyć", Modifier.padding(top = 16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.settings.bpOn) ToggleChip("Ciśnienie", rp.bp) { setRp { it.copy(bp = !it.bp) } }
                        if (s.settings.labsOn) ToggleChip("Wyniki badań", rp.labs) { setRp { it.copy(labs = !it.labs) } }
                        ToggleChip("Przyjmowanie leków", rp.meds) { setRp { it.copy(meds = !it.meds) } }
                        ToggleChip("Lista leków", rp.plan) { setRp { it.copy(plan = !it.plan) } }
                        ToggleChip("Kontrole", rp.events) { setRp { it.copy(events = !it.events) } }
                        ToggleChip("Analiza AI", rp.analysis) { setRp { it.copy(analysis = !it.analysis) } }
                        if (s.settings.labsOn) ToggleChip("EKG z zegarka", rp.ecg) { setRp { it.copy(ecg = !it.ecg) } }
                        if (s.settings.cyklOn && !store.cyklOf(store.activeId.value).isEmpty) ToggleChip("Podsumowanie cyklu", rp.cykl) { setRp { it.copy(cykl = !it.cykl) } }
                    }
                    if (rp.ecg) EcgPicker(s, rp, per, today, now, ctx, EcgSelection.AUTO_DOCTOR) { f -> setRp(f) }
                    if (rp.analysis || s.analyses.isNotEmpty()) {
                        Overline("Analiza AI jako uzupełnienie", Modifier.padding(top = 18.dp))
                        if (!rp.analysis) Hint("Analiza AI jest teraz wyłączona w raporcie (pigułka „Analiza AI” powyżej). Analizy możesz mimo to edytować i usuwać.", Modifier.padding(bottom = 6.dp))
                        val picked = s.reportAnalysis()
                        if (s.analyses.isEmpty()) {
                            BodyText("Wklej odpowiedź AI na raport z karty „Dla AI” albo wczytaj ją z pliku Markdown, a trafi na koniec raportu dla lekarza (opisana jako analiza AI).")
                        } else LekiCard {
                            s.analyses.sortedByDescending { it.date }.forEachIndexed { i, a ->
                                if (i > 0) Divider()
                                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        CheckRow(a.title, picked?.id == a.id, { setRp { it.copy(analysisId = a.id) } }, subtitle = plDate(a.date) + ", " + Markdown.selected(a.text, a.excluded).length + " z " + a.text.length + " znaków")
                                    }
                                    LekiButton("Edytuj", { editId = a.id; editorOpen = true }, small = true)
                                    LekiButton("Usuń", { deleteAnalysisWithUndo(store, a, undo) }, small = true, danger = true)
                                }
                            }
                        }
                        LekiButton(if (s.analyses.isEmpty()) "Wklej lub wczytaj odpowiedź AI" else "Dodaj kolejną analizę", { editId = ""; editorOpen = true }, Modifier.padding(top = 10.dp), tonal = true, icon = Ph.Plus)
                    }
                    if (rp.labs && s.settings.labsOn) {
                        Spacer8()
                        ToggleChip("Badania tylko z wybranego okresu", rp.labsPeriod) { setRp { it.copy(labsPeriod = !it.labsPeriod) } }
                        Hint("Bez tego raport pokazuje całą historię wyników do końca okresu.", Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LekiButton("Podgląd", { preview = true }, Modifier.weight(1f), tonal = true, enabled = rp.any)
                        LekiButton("Drukuj / PDF", {
                            ctx.findActivity()?.let { a -> Platform.printHtml(a, "Raport ${s.patient} $today".trim(), DoctorReport.document(DoctorReport.html(store.withSharedCykl(s, store.activeId.value), per, today, now))) }
                        }, Modifier.weight(1f), primary = true, enabled = rp.any, icon = Ph.Printer)
                    }
                }
            }
        } else {
            item {
                Column(Modifier.padding(top = 20.dp)) {
                    Row(
                        Modifier.fillMaxWidth().background(pal.accentSoft, CardShape).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PhIcon(Ph.Sparkle, size = 22.dp, tint = pal.accent)
                        Text("Gotowy tekst z poleceniem i danymi do wklejenia w dowolny model AI: analiza interakcji, pór dnia, dawek i badań. Powstaje na telefonie i opuszcza go dopiero, gdy go skopiujesz lub udostępnisz.",
                            style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2))
                    }
                    Overline("Co dołączyć", Modifier.padding(top = 18.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip("Leki i harmonogram", ai.meds) { setAi { it.copy(meds = !it.meds) } }
                        ToggleChip("Zmiany dawek", ai.history) { setAi { it.copy(history = !it.history) } }
                        ToggleChip("Zgodność z planem", ai.adherence) { setAi { it.copy(adherence = !it.adherence) } }
                        if (s.settings.bpOn) ToggleChip("Ciśnienie", ai.bp) { setAi { it.copy(bp = !it.bp) } }
                        if (s.settings.labsOn) ToggleChip("Badania", ai.labs) { setAi { it.copy(labs = !it.labs) } }
                        ToggleChip("Kontrole", ai.events) { setAi { it.copy(events = !it.events) } }
                        ToggleChip("Moje notatki", ai.notes) { setAi { it.copy(notes = !it.notes) } }
                        if (s.settings.labsOn) ToggleChip("EKG z zegarka", ai.ecg) { setAi { it.copy(ecg = !it.ecg) } }
                        if (s.settings.cyklOn && !store.cyklOf(store.activeId.value).isEmpty) ToggleChip("Podsumowanie cyklu", ai.cykl) { setAi { it.copy(cykl = !it.cykl) } }
                        if (BodyInfo.describe(s.settings, today) != null) ToggleChip("Wiek, masa, wzrost", ai.profile) { setAi { it.copy(profile = !it.profile) } }
                        ToggleChip("Imię i nazwisko", ai.name) { setAi { it.copy(name = !it.name) } }
                    }
                    if (ai.ecg && s.settings.labsOn) EcgPicker(s, s.settings.rp, per, today, now, ctx, EcgSelection.AUTO_AI) { f -> setRp(f) }
                    Text("Szczegółowość odpowiedzi AI", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
                    SegRow(listOf("Krótko (ok. 1 strony)", "Szczegółowo"), ai.detail, { setAi { a -> a.copy(detail = it) } })
                    Hint("Polecenie prosi model o odpowiedź w stałym formacie (nagłówki, tabele, ramki Pilne i Do omówienia, plakietki ważności), który aplikacja ostyluje w raporcie dla lekarza.", Modifier.padding(top = 6.dp))
                    var context by remember { mutableStateOf(ai.context) }
                    LekiField("Kontekst medyczny (opcjonalnie)", context, { context = it.take(2000); setAi { a -> a.copy(context = it.take(2000)) } },
                        Modifier.padding(top = 18.dp), singleLine = false, minLines = 3, placeholder = "np. płeć, choroby, alergie, leki bez recepty i suplementy, godziny posiłków (wiek, masę ciała i wzrost podaj w Ustawieniach)")
                    Hint("Im więcej kontekstu, tym trafniejsza analiza. Nie wpisuj danych, których nie chcesz ujawniać. Imię jest domyślnie pomijane.", Modifier.padding(top = 6.dp))
                    val text = AiReport.build(store.withSharedCykl(s, store.activeId.value), per, today, now, BuildConfig.VERSION_NAME)
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LekiButton("Kopiuj", { Platform.copyText(ctx, "Raport dla AI", text); toast("Skopiowano raport dla AI") }, Modifier.weight(1f), primary = true, icon = Ph.Copy)
                        LekiButton("Udostępnij", { Platform.shareFile(ctx, "leki-raport-ai-$today.md", text, "text/markdown", "Raport dla AI") }, Modifier.weight(1f), tonal = true, icon = Ph.ShareNetwork)
                    }
                    LinkButton((if (showAi) "▾ " else "▸ ") + "Podgląd treści", { showAi = !showAi }, Modifier.padding(top = 4.dp))
                    if (showAi) LekiField("", text, {}, readOnly = true, singleLine = false, minLines = 8, mono = true)
                    LinkButton("Wklej lub wczytaj odpowiedź AI do raportu dla lekarza", { editId = ""; editorOpen = true }, Modifier.padding(top = 4.dp))
                    Hint("Długość: ${"%,d".format(java.util.Locale.forLanguageTag("pl"), text.length)} znaków (około ${Math.round(text.length / 3.5 / 100) * 100} tokenów). Odpowiedź AI to pomoc w przygotowaniu pytań do lekarza lub farmaceuty, a nie porada medyczna.")
                }
            }
        }
    }

    if (editorOpen) AnalysisEditor(s.analysis(editId), store, today) { editorOpen = false; editId = "" }

    if (preview) {
        val html = remember(s, per, now.toLocalTime().hour, store.revision.value) { DoctorReport.document(DoctorReport.html(store.withSharedCykl(s, store.activeId.value), per, today, now)) }
        FullScreenDialog("Podgląd raportu", { preview = false }) {
            LekiButton("Drukuj / zapisz PDF", { ctx.findActivity()?.let { a -> Platform.printHtml(a, "Raport ${s.patient} $today".trim(), html) } }, Modifier.fillMaxWidth(), primary = true, icon = Ph.Printer)
            ReportWebView(html, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))

/** Podgląd raportu jako strona HTML (ta sama, którą drukujemy). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReportWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(600.dp),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
        onRelease = { it.destroy() },
    )
}

/**
 * Wybór zapisów EKG do raportów (dla lekarza i dla AI): domyślnie najnowsze z okresu, po dotknięciu zapisu przechodzi się do wyboru ręcznego,
 * np. tylko najgorszych zapisów. Przycisk PDF zapisuje pojedynczy zapis osobno.
 */
@Composable
private fun EcgPicker(s: AppState, rp: ReportParts, per: Period, today: LocalDate, now: java.time.LocalDateTime, ctx: Context, autoCount: Int, setRp: ((ReportParts) -> ReportParts) -> Unit) {
    val inPeriod = EcgSelection.inPeriod(s, per)
    if (inPeriod.isEmpty()) return
    val picked = EcgSelection.forReport(s, per, rp.ecgIds, autoCount).chosen.map { it.id }.toSet()
    Overline("EKG do raportu z wybranego okresu", Modifier.padding(top = 18.dp))
    LekiCard {
        inPeriod.forEachIndexed { i, e ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    CheckRow(
                        plDate(e.date) + ", " + e.time.hhmm(), e.id in picked,
                        { setRp { r -> r.copy(ecgIds = EcgSelection.toggled(s, per, r.ecgIds, e.id, autoCount)) } },
                        subtitle = listOf(if (e.watchHr > 0) "tętno ${e.watchHr}/min" else "", e.watchNote).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "zapis EKG" },
                    )
                }
                LekiButton("PDF", {
                    ctx.findActivity()?.let { a -> Platform.printHtml(a, "EKG ${plDate(e.date)} ${e.time.hhmm()}", EcgReport.document(s, e, today, now), landscape = true) }
                }, small = true, tonal = true, icon = Ph.Printer)
            }
        }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LinkButton("Zaznacz wszystkie", { setRp { it.copy(ecgIds = inPeriod.map { e -> e.id }) } })
        if (rp.ecgIds != null) LinkButton("Automatycznie (najnowsze)", { setRp { it.copy(ecgIds = null) } })
    }
    Hint(
        if (rp.ecgIds == null) "Bez wyboru do raportu dla lekarza trafia do ${EcgSelection.AUTO_DOCTOR} najnowszych zapisów z okresu, a do raportu dla AI do ${EcgSelection.AUTO_AI}. Zaznacz zapisy, które mają wejść do obu raportów, np. tylko te najgorsze."
        else "Do obu raportów (dla lekarza i dla AI) trafiają tylko zaznaczone zapisy z okresu: ${picked.size} z ${inPeriod.size}. Przycisk PDF zapisuje pojedyncze EKG osobno, w skali papieru (A4 poziomo, 25 mm/s i 10 mm/mV).",
        Modifier.padding(top = 6.dp),
    )
}

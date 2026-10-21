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
import pl.asystent.leki.core.DoctorReport
import pl.asystent.leki.core.Period
import pl.asystent.leki.core.ReportParts
import pl.asystent.leki.core.firstDate
import pl.asystent.leki.core.plDate
import java.time.LocalDate

private fun Context.findActivity(): Activity? {
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
    var preset by rememberSaveable { mutableStateOf(7) }
    var fromStr by rememberSaveable { mutableStateOf("") }
    var toStr by rememberSaveable { mutableStateOf("") }
    var customRange by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var preview by remember { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    val first = s.firstDate(today)
    val custom = if (fromStr.isNotEmpty() || toStr.isNotEmpty()) (fromStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } to toStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }) else null
    val per = Period.resolve(preset, custom, today, first)
    val rp = s.settings.rp
    val ai = s.settings.ai

    fun setRp(f: (ReportParts) -> ReportParts) = store.update { it.copy(settings = it.settings.copy(rp = f(it.settings.rp))) }
    fun setAi(f: (AiParts) -> AiParts) = store.update { it.copy(settings = it.settings.copy(ai = f(it.settings.ai))) }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Raport", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(bottom = 14.dp))
            LekiCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline("Okres", Modifier.padding(bottom = 0.dp))
                    SegRow(listOf("7 dni", "14 dni", "30 dni", "Wszystko"), if (custom != null) -1 else listOf(7, 14, 30, 0).indexOf(preset),
                        { preset = listOf(7, 14, 30, 0)[it]; fromStr = ""; toStr = ""; customRange = false })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(plDate(per.from) + " – " + plDate(per.to), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.ink))
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
                        ToggleChip("Ciśnienie", rp.bp) { setRp { it.copy(bp = !it.bp) } }
                        ToggleChip("Wyniki badań", rp.labs) { setRp { it.copy(labs = !it.labs) } }
                        ToggleChip("Przyjmowanie leków", rp.meds) { setRp { it.copy(meds = !it.meds) } }
                        ToggleChip("Lista leków", rp.plan) { setRp { it.copy(plan = !it.plan) } }
                        ToggleChip("Kontrole", rp.events) { setRp { it.copy(events = !it.events) } }
                    }
                    if (rp.labs) {
                        Spacer8()
                        ToggleChip("Badania tylko z wybranego okresu", rp.labsPeriod) { setRp { it.copy(labsPeriod = !it.labsPeriod) } }
                        Hint("Bez tego raport pokazuje całą historię wyników do końca okresu.", Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LekiButton("Podgląd", { preview = true }, Modifier.weight(1f), tonal = true, enabled = rp.any)
                        LekiButton("Drukuj / PDF", {
                            ctx.findActivity()?.let { a -> Platform.printHtml(a, "Raport ${s.patient} $today".trim(), DoctorReport.document(DoctorReport.html(s, per, today, now))) }
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
                        ToggleChip("Ciśnienie", ai.bp) { setAi { it.copy(bp = !it.bp) } }
                        ToggleChip("Badania", ai.labs) { setAi { it.copy(labs = !it.labs) } }
                        ToggleChip("Kontrole", ai.events) { setAi { it.copy(events = !it.events) } }
                        ToggleChip("Moje notatki", ai.notes) { setAi { it.copy(notes = !it.notes) } }
                        ToggleChip("Imię i nazwisko", ai.name) { setAi { it.copy(name = !it.name) } }
                    }
                    var context by remember { mutableStateOf(ai.context) }
                    LekiField("Kontekst medyczny (opcjonalnie)", context, { context = it.take(2000); setAi { a -> a.copy(context = it.take(2000)) } },
                        Modifier.padding(top = 18.dp), singleLine = false, minLines = 3, placeholder = "np. wiek, płeć, choroby, alergie, leki bez recepty i suplementy, godziny posiłków")
                    Hint("Im więcej kontekstu, tym trafniejsza analiza. Nie wpisuj danych, których nie chcesz ujawniać. Imię jest domyślnie pomijane.", Modifier.padding(top = 6.dp))
                    val text = AiReport.build(s, per, today, now, BuildConfig.VERSION_NAME)
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LekiButton("Kopiuj", { Platform.copyText(ctx, "Raport dla AI", text); toast("Skopiowano raport dla AI") }, Modifier.weight(1f), primary = true, icon = Ph.Copy)
                        LekiButton("Udostępnij", { Platform.shareFile(ctx, "leki-raport-ai-$today.md", text, "text/markdown", "Raport dla AI") }, Modifier.weight(1f), tonal = true, icon = Ph.ShareNetwork)
                    }
                    LinkButton((if (showAi) "▾ " else "▸ ") + "Podgląd treści", { showAi = !showAi }, Modifier.padding(top = 4.dp))
                    if (showAi) LekiField("", text, {}, readOnly = true, singleLine = false, minLines = 8, mono = true)
                    Hint("Długość: ${"%,d".format(java.util.Locale.forLanguageTag("pl"), text.length)} znaków (około ${Math.round(text.length / 3.5 / 100) * 100} tokenów). Odpowiedź AI to pomoc w przygotowaniu pytań do lekarza lub farmaceuty, a nie porada medyczna.")
                }
            }
        }
    }

    if (preview) {
        val html = remember(s, per, now.toLocalTime().hour) { DoctorReport.document(DoctorReport.html(s, per, today, now)) }
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
    )
}

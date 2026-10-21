package pl.asystent.leki.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import java.time.LocalDate

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun ReportScreen(s: AppState, store: Store, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    var preset by rememberSaveable { mutableStateOf(7) }
    var fromStr by rememberSaveable { mutableStateOf("") }
    var toStr by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    val first = s.firstDate(today)
    val custom = if (fromStr.isNotEmpty() || toStr.isNotEmpty()) (fromStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } to toStr.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }) else null
    val per = Period.resolve(preset, custom, today, first)
    val rp = s.settings.rp
    val ai = s.settings.ai

    fun setRp(f: (ReportParts) -> ReportParts) = store.update { it.copy(settings = it.settings.copy(rp = f(it.settings.rp))) }
    fun setAi(f: (AiParts) -> AiParts) = store.update { it.copy(settings = it.settings.copy(ai = f(it.settings.ai))) }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp)) {
        item {
            Text("Raport", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            BodyText("Wybierz okres i to, co ma się znaleźć w raporcie. Wybór jest zapamiętany.", Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text("Okres", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                    SegRow(listOf("7 dni", "14 dni", "30 dni", "Wszystko"), if (custom != null) -1 else listOf(7, 14, 30, 0).indexOf(preset),
                        { preset = listOf(7, 14, 30, 0)[it]; fromStr = ""; toStr = "" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField("Od", per.from, { fromStr = it.toString(); if (toStr.isEmpty()) toStr = per.to.toString() }, Modifier.weight(1f))
                    DateField("Do", per.to, { toStr = it.toString(); if (fromStr.isEmpty()) fromStr = per.from.toString() }, Modifier.weight(1f))
                }
            }
        }
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Raport dla lekarza", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                Text("Co dołączyć", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                LekiCard {
                    CheckRow("Pomiary ciśnienia", rp.bp, { v -> setRp { it.copy(bp = v) } }); Divider()
                    CheckRow("Wyniki badań", rp.labs, { v -> setRp { it.copy(labs = v) } })
                    if (rp.labs) CheckRow("Tylko wyniki z wybranego okresu", rp.labsPeriod, { v -> setRp { it.copy(labsPeriod = v) } }, "Bez tego: cała historia wyników do końca okresu", indent = true)
                    Divider()
                    CheckRow("Przyjmowanie leków", rp.meds, { v -> setRp { it.copy(meds = v) } }, "dni, zgodność z planem, pominięte dawki"); Divider()
                    CheckRow("Lista leków i dawkowanie", rp.plan, { v -> setRp { it.copy(plan = v) } }); Divider()
                    CheckRow("Kontrole i terminy", rp.events, { v -> setRp { it.copy(events = v) } })
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LekiButton("Podgląd", { preview = true }, Modifier.weight(1f), enabled = rp.any)
                    LekiButton("Drukuj lub zapisz PDF", {
                        ctx.findActivity()?.let { a -> Platform.printHtml(a, "Raport ${s.patient} $today".trim(), DoctorReport.document(DoctorReport.html(s, per, today, now))) }
                    }, Modifier.weight(1f), primary = true, enabled = rp.any, icon = Ph.Printer)
                }
            }
        }
        item {
            Column(Modifier.padding(top = 30.dp)) {
                Text("Raport dla AI", style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                BodyText("Tekst z poleceniem i danymi do wklejenia w dowolny model AI (analiza interakcji, pór dnia, dawek i badań). Obejmuje okres wybrany wyżej. Powstaje na tym urządzeniu i opuszcza je dopiero, gdy go skopiujesz, udostępnisz lub zapiszesz.", Modifier.padding(top = 4.dp, bottom = 10.dp))
                Text("Co dołączyć", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2), modifier = Modifier.padding(bottom = 6.dp))
                LekiCard {
                    CheckRow("Leki i harmonogram dnia", ai.meds, { v -> setAi { it.copy(meds = v) } }); Divider()
                    CheckRow("Zmiany dawkowania w czasie", ai.history, { v -> setAi { it.copy(history = v) } }); Divider()
                    CheckRow("Zgodność z planem", ai.adherence, { v -> setAi { it.copy(adherence = v) } }, "pominięte, spóźnione dawki, godziny przyjęcia"); Divider()
                    CheckRow("Ciśnienie i tętno", ai.bp, { v -> setAi { it.copy(bp = v) } }); Divider()
                    CheckRow("Wyniki badań", ai.labs, { v -> setAi { it.copy(labs = v) } }); Divider()
                    CheckRow("Kontrole i terminy", ai.events, { v -> setAi { it.copy(events = v) } }); Divider()
                    CheckRow("Moje notatki", ai.notes, { v -> setAi { it.copy(notes = v) } }, "notatki dni, zasady, dopisek do raportu"); Divider()
                    CheckRow("Imię i nazwisko", ai.name, { v -> setAi { it.copy(name = v) } }, "domyślnie pominięte")
                }
                var context by remember { mutableStateOf(ai.context) }
                LekiField("Kontekst medyczny (opcjonalnie)", context, { context = it.take(2000); setAi { a -> a.copy(context = it.take(2000)) } },
                    Modifier.padding(top = 14.dp), singleLine = false, minLines = 3, placeholder = "np. wiek, płeć, choroby, alergie, leki bez recepty i suplementy, godziny posiłków")
                Hint("Im więcej kontekstu, tym trafniejsza analiza. Nie wpisuj danych, których nie chcesz ujawniać.", Modifier.padding(top = 6.dp))
                val text = AiReport.build(s, per, today, now, BuildConfig.VERSION_NAME)
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LekiButton("Kopiuj", { Platform.copyText(ctx, "Raport dla AI", text); toast("Skopiowano raport dla AI") }, Modifier.weight(1f), primary = true, icon = Ph.Copy)
                    LekiButton("Udostępnij", { Platform.shareFile(ctx, "leki-raport-ai-$today.md", text, "text/markdown", "Raport dla AI") }, Modifier.weight(1f), icon = Ph.ShareNetwork)
                }
                LinkButton((if (showAi) "▾ " else "▸ ") + "Podgląd treści", { showAi = !showAi })
                if (showAi) LekiField("", text, {}, readOnly = true, singleLine = false, minLines = 8, mono = true)
                Hint("Długość: ${"%,d".format(java.util.Locale("pl"), text.length)} znaków (około ${Math.round(text.length / 3.5 / 100) * 100} tokenów).")
                Hint("Odpowiedź AI to pomoc w przygotowaniu pytań do lekarza lub farmaceuty, a nie porada medyczna.", Modifier.padding(top = 4.dp))
            }
        }
    }

    if (preview) {
        val html = remember(s, per, now.toLocalTime().hour) { DoctorReport.document(DoctorReport.html(s, per, today, now)) }
        FullScreenDialog("Podgląd raportu", { preview = false }) {
            LekiButton("Drukuj lub zapisz PDF", { ctx.findActivity()?.let { a -> Platform.printHtml(a, "Raport ${s.patient} $today".trim(), html) } }, Modifier.fillMaxWidth(), primary = true, icon = Ph.Printer)
            ReportWebView(html, Modifier.fillMaxWidth())
        }
    }
}

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

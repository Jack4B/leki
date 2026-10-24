package pl.asystent.leki.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.asystent.leki.Store
import pl.asystent.leki.Platform
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Ecg
import pl.asystent.leki.core.EcgAnalysis
import pl.asystent.leki.core.EcgDigitizer
import pl.asystent.leki.core.EcgEvent
import pl.asystent.leki.core.EcgInfo
import pl.asystent.leki.core.EcgMeta
import pl.asystent.leki.core.EcgResult
import pl.asystent.leki.core.EcgViewport
import pl.asystent.leki.core.EcgReport
import pl.asystent.leki.core.EcgVector
import pl.asystent.leki.core.EcgSeverity
import pl.asystent.leki.core.EcgTrend
import pl.asystent.leki.core.EcgTrendPoint
import pl.asystent.leki.core.minutes
import pl.asystent.leki.core.shortDate
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.deleteEcg
import pl.asystent.leki.core.ecgGroupSummary
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.saveEcg
import pl.asystent.leki.ocr.OcrEngine
import pl.asystent.leki.ocr.ScanImages
import java.time.LocalDate
import java.time.LocalTime

/** Rozdzielczość renderowania strony PDF: 300 dpi daje ponad dwa razy mniejszy błąd odtworzenia niż 200 (patrz README); przy braku pamięci aplikacja wraca do 200. */
private const val DPI = 300
private const val DPI_FALLBACK = 200

/** Lista zapisów EKG w zakładce Badania. */
fun LazyListScope.ecgSection(s: AppState, hist: HistoryState, today: java.time.LocalDate, onAdd: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit = {}) {
    item {
        Column(Modifier.padding(top = 14.dp)) {
            BodyText("Zapisy EKG z zegarka lub opaski (jedno odprowadzenie). Wczytaj wydruk PDF z aplikacji zegarka: aplikacja odtworzy przebieg, policzy tętno i odstępy między uderzeniami oraz wskaże ewentualne nieregularności do omówienia z lekarzem.")
            LekiButton("Dodaj EKG z pliku PDF", onAdd, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true, icon = Ph.Heartbeat)
        }
    }
    val trend = s.ecgs.size.let { if (it >= 2) EcgTrend.points(s.ecgs) else emptyList() }
    if (trend.count { it.hr != null } >= 2) item {
        Column(Modifier.padding(top = 16.dp)) {
            Overline("Trend tętna z zapisów EKG")
            LekiCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EcgTrendChart(trend)
                    Hint(EcgTrend.summary(trend) + " Pomarańczowe punkty: zapisy z oznaczeniami do omówienia z lekarzem. Przesuń wykres palcem, żeby zobaczyć wcześniejsze zapisy.")
                }
            }
        }
    }
    item { EcgMethods() }
    if (s.ecgs.isEmpty()) item { EmptyBox("Brak zapisów EKG.", Modifier.padding(top = 16.dp)) }
    else historyList(
        "ecg", "Zapisy EKG", s.ecgs.sortedWith(compareByDescending<Ecg> { it.date }.thenByDescending { it.time }).let { l -> if (hist.flagOnly) l.filter { ecgAttention(it) > 0 } else l },
        hist, today, { it.date }, "Tylko z oznaczeniami", "Pokaż wszystkie", "Brak zapisów z oznaczeniami do omówienia z lekarzem.",
        { ecgGroupSummary(it) },
    ) { rows -> EcgRows(rows, onOpen, onDelete) }
}

private fun ecgAttention(e: Ecg): Int = EcgAnalysis.analyze(e).flags.count { it.severity == EcgSeverity.Attention }

/** Zapisy EKG jednego dnia: godzina, tętno, liczba oznaczeń; dotknięcie otwiera zapis, „Usuń” kasuje go po potwierdzeniu. */
@Composable
private fun EcgRows(rows: List<Ecg>, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    rows.forEachIndexed { i, e ->
        if (i > 0) Divider()
        val r = remember(e) { EcgAnalysis.analyze(e) }
        val attention = r.flags.count { it.severity == EcgSeverity.Attention }
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(e.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(plDate(e.date) + ", " + e.time.hhmm(), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                val hr = r.hrMedian?.let { "tętno ${Math.round(it)}/min" } ?: (if (e.watchHr > 0) "tętno ${e.watchHr}/min" else "")
                Text(listOf(hr, e.watchNote).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "zapis EKG" }, style = TextStyle(fontSize = 13.sp, color = pal.ink3))
                if (attention > 0) Text("Do omówienia z lekarzem: $attention", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
            }
            LekiButton("Usuń", { onDelete(e.id) }, danger = true, small = true)
        }
    }
}

/** Wynik wczytania PDF: zapis i informacja, czy data i godzina zostały odczytane z nagłówka wydruku (w przeciwnym razie wstawiono bieżące). */
private class EcgImport(val ecg: Ecg, val dateRead: Boolean)

/** Renderuje stronę, odtwarza przebieg i odczytuje nagłówek; null, gdy w pliku nie ma przebiegu EKG (albo jest krótszy niż 3 s, czyli nie do analizy). */
private suspend fun readPdfEcg(ctx: android.content.Context, uri: android.net.Uri, dpi: Int): EcgImport? {
    // przebieg: strona w pełnej rozdzielczości jest od razu zamieniana na maskę jednobajtową (czwarta część pamięci bitmapy) i zwalniana;
    // skala czasu i napięcia pochodzi z faktycznej rozdzielczości obrazu (strona pozioma A4 w 300 dpi ma 3508 px szerokości)
    val dig = run {
        val rendered = ScanImages.renderFirstPdfPageScaled(ctx, uri, dpi, maxW = 3700, maxH = 4400)
        val bmp = rendered.bitmap
        try {
            val w = bmp.width; val h = bmp.height
            val m = ByteArray(w * h); val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val o = y * w
                for (x in 0 until w) if (EcgDigitizer.isTrace(row[x])) m[o + x] = 1
            }
            EcgDigitizer.digitizeMask(m, w, h, rendered.dpi / 25.4)
        } finally { bmp.recycle() }
    } ?: return null
    if (dig.mv.size / dig.fs < 3.0) return null
    // nagłówek wydruku (data, tętno, ocena rytmu): osobny, mały render 200 dpi, na którym OCR był sprawdzony
    val meta = runCatching {
        val small = ScanImages.renderFirstPdfPage(ctx, uri, DPI_FALLBACK)
        val upright = if (dig.vertical) Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(-90f) }, true).also { small.recycle() } else small
        OcrEngine().use { eng -> EcgMeta.parse(LabScan.rows(eng.recognize(upright))) }
    }.getOrNull()
    val now = java.time.LocalDateTime.now()
    val ecg = Ecg(
        newId(), meta?.date ?: now.toLocalDate(), meta?.time ?: now.toLocalTime().withSecond(0).withNano(0),
        watchHr = meta?.hr ?: 0, watchNote = meta?.note.orEmpty(), fs = 250, cmv = EcgVector.normalize(EcgAnalysis.toRecord(dig.mv, dig.fs, 250)),
    )
    return EcgImport(ecg, meta?.date != null)
}

/** Wczytywanie PDF z zegarka: render strony, odtworzenie przebiegu, analiza i odczyt daty oraz tętna z nagłówka (OCR na urządzeniu). */
@Composable
fun rememberEcgImporter(onDraft: (Ecg) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val draft = withContext(Dispatchers.Default) {
                    try { readPdfEcg(ctx, uri, DPI) } catch (e: OutOfMemoryError) { System.gc(); readPdfEcg(ctx, uri, DPI_FALLBACK) }
                }
                if (draft == null) toast("Nie znaleziono przebiegu EKG w tym pliku. Wczytaj PDF wyeksportowany z aplikacji zegarka.")
                else {
                    onDraft(draft.ecg)
                    if (!draft.dateRead) toast("Nie odczytano daty z wydruku. Ustaw datę i godzinę ręcznie.")
                }
            } catch (e: ScanImages.PdfException) {
                toast(e.message ?: "Nie można otworzyć PDF.")
            } catch (e: OutOfMemoryError) {
                toast("Za mało pamięci, żeby przetworzyć ten plik.")
            } catch (e: Exception) {
                toast("Nie udało się przetworzyć pliku EKG.")
            } finally {
                busy = false
            }
        }
    }
    if (busy) AlertDialog(
        onDismissRequest = {}, containerColor = pal.surface,
        title = { Text("Odczytuję EKG…", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { CircularProgressIndicator(color = pal.accent); BodyText("Plik jest przetwarzany na telefonie.") } },
        confirmButton = {},
    )
    return { launcher.launch(arrayOf("application/pdf")) }
}

/** Przebieg w trzech pasach po 10 s, na tle siatki jak na papierze; trójkąty to wykryte uderzenia, szare tło to pominięte zakłócenia. */
@Composable
fun EcgWaveView(e: Ecg, r: EcgResult, modifier: Modifier = Modifier) {
    val sig = remember(e) { e.signalMv() }
    val grid = pal.line; val ink = pal.ink; val accent = pal.accent; val warn = pal.warn
    val rows = ((e.cmv.size + e.fs * 10 - 1) / (e.fs * 10)).coerceIn(1, 3)
    Canvas(modifier.fillMaxWidth().height((rows * 104).dp).semantics { contentDescription = "Przebieg EKG w ${rows} pasach po 10 sekund" }) {
        val rowH = size.height / rows
        val perRow = e.fs * 10
        val mvPx = rowH / 3.6f
        for (k in 0 until rows) {
            val top = k * rowH; val base = top + rowH * 0.62f
            var t = 0.0
            while (t <= 10.0) { val x = (t / 10.0 * size.width).toFloat(); drawLine(grid.copy(alpha = if ((t * 5).toInt() % 5 == 0) .9f else .45f), Offset(x, top), Offset(x, top + rowH - 2f), strokeWidth = 1f); t += 0.2 }
            var mv = -1.5f
            while (mv <= 2.0f) { val y = base - mv * mvPx; if (y in top..(top + rowH)) drawLine(grid.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f); mv += 0.5f }
            for (a in r.artifacts) {
                val a0 = a.start - k * 10.0; val a1 = a.endInclusive - k * 10.0
                if (a1 < 0 || a0 > 10) continue
                val x0 = (maxOf(0.0, a0) / 10 * size.width).toFloat(); val x1 = (minOf(10.0, a1) / 10 * size.width).toFloat()
                drawRect(warn.copy(alpha = .14f), Offset(x0, top), Size(x1 - x0, rowH - 2f))
            }
            val path = Path()
            val from = k * perRow; val to = minOf(sig.size, from + perRow)
            for (i in from until to) {
                val x = (i - from).toFloat() / perRow * size.width
                val y = base - sig[i].coerceIn(-1.9, 2.4).toFloat() * mvPx
                if (i == from) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink, style = Stroke(width = 1.6f))
            for (b in r.beats) {
                val t2 = b.tSec - k * 10.0
                if (t2 < 0 || t2 >= 10) continue
                val x = (t2 / 10 * size.width).toFloat()
                val tri = Path().apply { moveTo(x - 6f, top + 4f); lineTo(x + 6f, top + 4f); lineTo(x, top + 15f); close() }
                drawPath(tri, accent)
            }
        }
    }
}

/** Wskaźniki i oznaczenia z analizy zapisu, w czytelnych blokach. */
/**
 * Podgląd z powiększaniem: ciągły pas całego zapisu. Okno ([EcgViewport]) to stan: przeciągnięcie palcem przesuwa je, dwa palce powiększają wokół punktu dotyku,
 * a mapa zapisu pod wykresem pokazuje położenie okna i zdarzeń (dotknięcie mapy przesuwa okno). Rysowana jest tylko widoczna część.
 * Siatka jak na papierze: mała kratka to 0,04 s i 0,1 mV (25 mm/s i 10 mm/mV), duża to 0,2 s i 0,5 mV.
 */
@Composable
fun EcgZoomView(e: Ecg, r: EcgResult, startEvent: Int? = null) {
    val sig = remember(e) { e.signalMv() }
    val total = e.durationS.toFloat().coerceAtLeast(EcgViewport.MIN_VISIBLE)
    // startEvent: numer zdarzenia, przy którym widok ma się otworzyć (do zrzutów ekranu); normalnie widok zaczyna od początku zapisu
    val first = startEvent?.let { r.events.getOrNull(it) }
    val firstWindow = first?.let { EcgViewport.forEvent(it, total) }
    var startSec by rememberSaveable { mutableStateOf(firstWindow?.start ?: 0f) }
    var visibleSec by rememberSaveable { mutableStateOf(firstWindow?.visible ?: minOf(10f, total)) }
    var focus by remember { mutableStateOf(first) }
    fun window() = EcgViewport.Window(startSec, visibleSec)
    fun show(w: EcgViewport.Window) { val c = EcgViewport.clamp(w, total); startSec = c.start; visibleSec = c.visible }
    fun jump(ev: EcgEvent) { focus = ev; show(EcgViewport.forEvent(ev, total)) }
    fun preset(sec: Float) {
        val mid = focus?.let { ((it.tSec + (it.endSec ?: it.tSec)) / 2).toFloat() } ?: (window().start + window().visible / 2)
        if (sec >= total) show(EcgViewport.whole(total)) else show(EcgViewport.centerOn(mid, sec, total))
    }
    val grid = pal.line; val ink = pal.ink; val accent = pal.accent; val warn = pal.warn; val ink3 = pal.ink3
    // zdarzenie jest „pokazane”, tylko gdy faktycznie mieści się w oknie (po ręcznym przesunięciu widoku opis nie może kłamać)
    fun shown(ev: EcgEvent) = ev.tSec <= startSec + visibleSec && (ev.endSec ?: ev.tSec) >= startSec
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Całość" to total, "10 s" to 10f, "5 s" to 5f, "2 s" to 2f).forEach { (label, v) ->
                val target = v.coerceIn(minOf(EcgViewport.MIN_VISIBLE, total), total)
                LekiButton(label, { preset(v) }, Modifier.weight(1f), small = true, tonal = Math.abs(visibleSec - target) < 0.05f)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(250.dp)
                .semantics { contentDescription = "Przebieg EKG, widoczne ${"%.1f".format(visibleSec)} sekund od ${"%.1f".format(startSec)} sekundy z ${"%.0f".format(total)}" }
                // jeden palec: przesuwanie poziome (z progiem ruchu, więc dotknięcie i pionowe przewijanie ekranu nic nie zmieniają)
                .pointerInput(total) {
                    detectHorizontalDragGestures { change, dx -> change.consume(); show(EcgViewport.pan(window(), -dx / size.width, total)) }
                }
                // dwa palce: powiększanie wokół punktu dotyku (ten detektor widzi zdarzenia pierwszy i je zużywa, więc przesuwanie jednym palcem go nie dubluje)
                .pointerInput(total) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size >= 2) {
                                val z = ev.calculateZoom()
                                if (z != 1f) {
                                    show(EcgViewport.zoom(window(), z, (ev.calculateCentroid(useCurrent = true).x / size.width).coerceIn(0f, 1f), total))
                                    ev.changes.forEach { it.consume() }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                },
        ) {
            val k = density
            val w = size.width; val h = size.height
            val secPx = w / visibleSec
            val t0 = startSec.toDouble(); val t1 = (startSec + visibleSec).toDouble()
            fun x(t: Double) = ((t - t0) * secPx).toFloat()
            val plotH = h - 18f * k
            val base = h * 0.6f; val mvPx = h / 4.6f
            val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink3.toArgb(); textSize = 10f * k }
            // siatka
            var i = Math.floor(t0 / 0.04).toInt()
            while (i * 0.04 <= t1) {
                val gx = x(i * 0.04)
                if (i % 5 == 0) drawLine(grid.copy(alpha = .9f), Offset(gx, 0f), Offset(gx, plotH), strokeWidth = 1f)
                else if (secPx * 0.04f >= 5f * k) drawLine(grid.copy(alpha = .4f), Offset(gx, 0f), Offset(gx, plotH), strokeWidth = 1f)
                i++
            }
            var mvv = -19
            while (mvv <= 27) { val y = base - mvv / 10f * mvPx; if (y in 0f..plotH) drawLine(grid.copy(alpha = if (mvv % 5 == 0) .9f else .35f), Offset(0f, y), Offset(w, y), strokeWidth = 1f); mvv++ }
            for (a in r.artifacts) if (a.endInclusive >= t0 && a.start <= t1) drawRect(warn.copy(alpha = .14f), Offset(x(a.start), 0f), Size(((a.endInclusive - a.start) * secPx).toFloat(), plotH))
            focus?.let { f ->
                // zaznaczone zdarzenie: pas (dla punktowego +-0,3 s) z pionowymi liniami
                val fe = f.endSec
                val a0 = x(if (fe == null) f.tSec - 0.3 else f.tSec); val a1 = x(fe ?: (f.tSec + 0.3))
                drawRect(accent.copy(alpha = .16f), Offset(a0, 0f), Size(a1 - a0, plotH))
                drawLine(accent, Offset(a0, 0f), Offset(a0, plotH), strokeWidth = 2f * k); drawLine(accent, Offset(a1, 0f), Offset(a1, plotH), strokeWidth = 2f * k)
            }
            // przebieg: tylko widoczne próbki (plus jedna z każdej strony)
            val path = Path()
            val i0 = Math.max(0, Math.floor(t0 * e.fs).toInt() - 1); val i1 = Math.min(sig.size - 1, Math.ceil(t1 * e.fs).toInt() + 1)
            for (n in i0..i1) { val px = x(n.toDouble() / e.fs); val py = base - sig[n].coerceIn(-1.9, 2.7).toFloat() * mvPx; if (n == i0) path.moveTo(px, py) else path.lineTo(px, py) }
            drawPath(path, ink, style = Stroke(width = 1.8f * k))
            for (b in r.beats) if (b.tSec >= t0 && b.tSec <= t1) { val bx = x(b.tSec); drawPath(Path().apply { moveTo(bx - 6f * k, 4f * k); lineTo(bx + 6f * k, 4f * k); lineTo(bx, 15f * k); close() }, accent) }
            // podpisy sekund: krok dobrany tak, żeby podpisy nie nachodziły na siebie
            val step = listOf(1, 2, 5, 10).firstOrNull { it * secPx >= 40f * k } ?: 10
            var sec = (Math.ceil(t0 / step) * step).toInt()
            while (sec <= t1) { val label = "$sec s"; val lx = x(sec.toDouble()) + 3f * k; if (lx + txt.measureText(label) <= w) drawContext.canvas.nativeCanvas.drawText(label, lx, h - 4f * k, txt); sec += step }
        }
        focus?.let { f -> if (!shown(f)) LekiButton("Wróć do zdarzenia: ${f.title}", { jump(f) }, Modifier.fillMaxWidth(), small = true, tonal = true) }
        // mapa zapisu: położenie okna i zdarzeń; przeciągnięcie przesuwa okno
        Canvas(
            Modifier.fillMaxWidth().height(40.dp).pointerInput(total) {
                // przeciąganie (nie samo dotknięcie), żeby przypadkowy dotyk przy przewijaniu ekranu nie przestawiał widoku
                detectHorizontalDragGestures(onDragStart = { off -> show(EcgViewport.centerOn(off.x / size.width * total, visibleSec, total)) }) { change, _ ->
                    change.consume(); show(EcgViewport.centerOn(change.position.x / size.width * total, visibleSec, total))
                }
            }.semantics { contentDescription = "Mapa zapisu: przeciągnij, aby przesunąć widok" },
        ) {
            val k = density; val w = size.width; val h = size.height
            drawRect(grid.copy(alpha = .5f), Offset(0f, h * 0.3f), Size(w, h * 0.4f))
            for (ev in r.events) {
                val a = (ev.tSec / total * w).toFloat(); val b = ((ev.endSec ?: ev.tSec) / total * w).toFloat()
                drawRect(if (ev.severity == EcgSeverity.Attention) warn else ink3, Offset(a - 1f * k, 0f), Size(Math.max(2f * k, b - a), h))
            }
            drawRect(accent.copy(alpha = .3f), Offset(startSec / total * w, 0f), Size(visibleSec / total * w, h))
            drawRect(accent, Offset(startSec / total * w, 0f), Size(visibleSec / total * w, h), style = Stroke(width = 2f * k))
        }
        Hint("Przeciągnij wykres palcem, dwoma palcami powiększaj i zmniejszaj, a po mapie pod nim przeciągnij, żeby przeskoczyć w inne miejsce zapisu. Mała kratka to 0,04 s i 0,1 mV (jak na papierze 25 mm/s i 10 mm/mV). Trójkąty to wykryte uderzenia, szare tło to pominięte zakłócenia.")

        // lista nieprawidłowości: dotknięcie pozycji pokazuje to miejsce na wykresie
        val located = setOf("premature", "premature_many", "pause", "artifacts")
        val general = r.flags.filter { it.code !in located && it.code != "none" }
        Overline("Nieprawidłowości w zapisie", Modifier.padding(top = 6.dp))
        if (r.events.isEmpty() && general.isEmpty()) {
            Hint("Aplikacja nie wykryła nieprawidłowości w czytelnych fragmentach. To nie wyklucza choroby serca.")
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.events.forEach { ev ->
                val attention = ev.severity == EcgSeverity.Attention
                val selected = focus == ev && shown(ev)
                Row(
                    Modifier.fillMaxWidth().clip(CardShape).border(if (selected) 2.dp else 1.dp, if (selected) pal.accent else pal.line, CardShape).background(pal.surface)
                        .clickable(role = Role.Button) { jump(ev) }.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhIcon(if (attention) Ph.Warning else Ph.Info, size = 22.dp, tint = if (attention) pal.warn else pal.accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(ev.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (attention) pal.warn else pal.ink))
                        Text(ev.text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2))
                        Text(if (selected) "Pokazane na wykresie" else "Pokaż na wykresie ›", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            general.forEach { f ->
                val attention = f.severity == EcgSeverity.Attention
                Row(
                    Modifier.fillMaxWidth().clip(CardShape).border(1.dp, pal.line, CardShape).background(pal.surface)
                        .clickable(role = Role.Button) { focus = null; show(EcgViewport.whole(total)) }.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhIcon(if (attention) Ph.Warning else Ph.Info, size = 22.dp, tint = if (attention) pal.warn else pal.accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(f.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (attention) pal.warn else pal.ink))
                        Text(f.text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2))
                        Text("Dotyczy całego zapisu: pokaż całość ›", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun EcgFindings(r: EcgResult) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(r.hrMedian?.let { "${Math.round(it)}" } ?: "brak", "tętno, /min", Modifier.weight(1f))
            Stat(r.hrMin?.let { "${Math.round(it)}–${Math.round(r.hrMax ?: it)}" } ?: "brak", "zakres tętna", Modifier.weight(1f))
            Stat(r.quality, "jakość zapisu", Modifier.weight(1f), mono = false)
        }
        if (r.sdnnMs != null) Hint("Zmienność odstępów RR: SDNN ${Math.round(r.sdnnMs ?: 0.0)} ms, RMSSD ${Math.round(r.rmssdMs ?: 0.0)} ms. Wykryte uderzenia: ${r.beats.size}.")
        val nr = r.nRmssd; val tp = r.tpr; val se = r.sampEn
        if (nr != null && tp != null && se != null) Hint("Losowość odstępów: RMSSD/RR ${"%.2f".format(java.util.Locale.ROOT, nr).replace('.', ',')}, punkty zwrotne ${Math.round(tp * 100)}%, entropia ${"%.1f".format(java.util.Locale.ROOT, se).replace('.', ',')}.")
        val qw = r.qrsWidthMs; val sc = r.shapeCorr
        if (qw != null && sc != null) Hint("Kształt uderzeń: zgodność z szablonem ${"%.2f".format(java.util.Locale.ROOT, sc).replace('.', ',')}, szerokość QRS ok. ${Math.round(qw)} ms (orientacyjnie, do porównań między uderzeniami).")
        Column {
            Overline("Co wykryto")
            LekiCard {
                r.flags.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val attention = f.severity == EcgSeverity.Attention
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhIcon(if (attention) Ph.Warning else Ph.Info, size = 22.dp, tint = if (attention) pal.warn else pal.accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(f.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (attention) pal.warn else pal.ink))
                            Text(f.text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(CardShape).background(pal.accentSoft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ważne", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
            listOf(
                "To narzędzie pomocnicze, a nie diagnoza.",
                "Aplikacja nie mierzy odstępu QT ani odcinka ST i nie rozpoznaje niedokrwienia ani zawału. Zegarek zapisuje tylko jedno odprowadzenie.",
                "Przy bólu w klatce piersiowej, duszności, omdleniu lub kołataniu nie czekaj na analizę: skontaktuj się z lekarzem lub zadzwoń pod 112.",
                "Przy lekach wydłużających odstęp QT albo zaburzeniach potasu poproś lekarza o pełne EKG.",
            ).forEach { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = TextStyle(fontSize = 13.5.sp, color = pal.accent, fontWeight = FontWeight.Bold))
                    Text(t, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Rozwijany opis algorytmów i obliczeń (ten sam tekst jest w README i w skróconej formie w raportach). */
@Composable
fun EcgMethods() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        LinkButton((if (open) "▾ " else "▸ ") + "Jak to jest liczone (algorytmy)", { open = !open })
        if (open) LekiCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EcgInfo.STEPS.forEachIndexed { i, st ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${i + 1}. ${st.title}", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        Text(st.text, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2))
                    }
                }
                Hint("Obliczenia wykonuje sama aplikacja na telefonie: bez bibliotek zewnętrznych, bez internetu i bez modeli AI. Kod: core/…/Ecg.kt.")
            }
        }
    }
}

/** Edytor nowego zapisu (po wczytaniu PDF) albo istniejącego (zmiana daty, godziny, notatki). */
@Composable
fun EcgEditor(initial: Ecg, isNew: Boolean, store: Store, onClose: () -> Unit) {
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time) }
    var hr by remember { mutableStateOf(if (initial.watchHr > 0) initial.watchHr.toString() else "") }
    var watchNote by remember { mutableStateOf(initial.watchNote) }
    var note by remember { mutableStateOf(initial.note) }
    var zoom by remember { mutableStateOf(false) }
    val result = remember(initial) { EcgAnalysis.analyze(initial) }
    FullScreenDialog(
        if (isNew) "Nowe EKG z zegarka" else "EKG z ${plDate(initial.date)}",
        onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiButton("Anuluj", onClose, Modifier.weight(1f))
                LekiButton("Zapisz", {
                    store.update { it.saveEcg(initial.copy(date = date, time = time, watchHr = hr.toIntOrNull()?.coerceIn(0, 300) ?: 0, watchNote = watchNote.trim(), note = note.trim())) }
                    toast("Zapisano EKG")
                    onClose()
                }, Modifier.weight(1f), primary = true)
            }
        },
    ) {
        EcgWaveView(initial, result)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LekiButton("Powiększ przebieg", { zoom = true }, Modifier.weight(1f), tonal = true, icon = Ph.Heartbeat)
            LekiButton("Eksport PDF", {
                // bieżące wartości z formularza (data, godzina, notatki), także dla zapisu jeszcze niezapisanego
                val cur = initial.copy(date = date, time = time, watchHr = hr.toIntOrNull()?.coerceIn(0, 300) ?: 0, watchNote = watchNote.trim(), note = note.trim())
                val now = java.time.LocalDateTime.now()
                ctx.findActivity()?.let { a -> Platform.printHtml(a, "EKG ${plDate(cur.date)} ${cur.time.hhmm()}", EcgReport.document(store.state.value, cur, now.toLocalDate(), now), landscape = true) }
            }, Modifier.weight(1f), tonal = true, icon = Ph.Printer)
        }
        Hint("Przebieg odtworzony z wydruku. Sprawdź, czy wygląda tak jak na wydruku z zegarka; jeśli nie, nie opieraj się na analizie." +
            (if (initial.durationS > 30.5) " Na wykresie widać pierwsze 30 s; analiza obejmuje cały zapis, a całość jest w widoku powiększonym." else ""))
        val watchBpm = hr.toIntOrNull() ?: 0
        val ownBpm = result.hrMedian
        if (watchBpm > 0 && ownBpm != null && Math.abs(ownBpm - watchBpm) / watchBpm > 0.15)
            Notice("Tętno z przebiegu (${Math.round(ownBpm)}/min) wyraźnie różni się od tętna z zegarka ($watchBpm/min). Przebieg mógł zostać odczytany w złej skali albo z błędem: porównaj go z wydrukiem i nie opieraj się na analizie.")
        if (isNew && store.state.value.ecgs.any { it.date == date && it.time == time })
            Notice("Zapis EKG z tą datą i godziną już istnieje. Jeśli to ten sam wydruk, anuluj, żeby nie dublować zapisu.")
        EcgFindings(result)
        EcgMethods()
        Overline("Dane zapisu", Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField("Data", date, { date = it }, Modifier.weight(1f))
            TimeField("Godzina", time, { time = it }, Modifier.weight(1f))
        }
        LekiField("Tętno wg zegarka, /min (opcjonalnie)", hr, { hr = it.filter { c -> c.isDigit() }.take(3) }, keyboard = KeyboardType.Number, mono = true)
        LekiField("Ocena zegarka (opcjonalnie)", watchNote, { watchNote = it.take(120) }, placeholder = "np. Rytm zatokowy")
        LekiField("Notatka (opcjonalnie)", note, { note = it.take(500) }, singleLine = false, minLines = 2, placeholder = "np. po spacerze, odczuwane kołatanie")
        if (!isNew) LinkButton("Usuń to EKG", {
            store.update { it.deleteEcg(initial.id) }
            undo("Usunięto EKG") { store.update { it.saveEcg(initial) } }
            onClose()
        }, danger = true)
    }
    if (zoom) FullScreenDialog("Przebieg EKG", { zoom = false }) { EcgZoomView(initial, result) }
}


/**
 * Tętno z kolejnych zapisów EKG w czasie (mediana z przebiegu, a gdy jej nie ma, tętno z zegarka), jak wykres ciśnienia: zakres (30 dni, 90 dni, rok, wszystko),
 * stała skala czasu (co najmniej 7 dp na dzień), więc przy wielu zapisach wykres jest szerszy i przewija się w bok (domyślnie na najnowszych), a oś tętna zostaje po lewej.
 * Pomarańczowe punkty to zapisy z oznaczeniami do omówienia z lekarzem.
 */
@Composable
fun EcgTrendChart(points: List<EcgTrendPoint>, modifier: Modifier = Modifier, today: java.time.LocalDate = java.time.LocalDate.now()) {
    val ranges = listOf(30, 90, 365, 0)
    var range by rememberSaveable { mutableStateOf(3) }
    val days = ranges[range]
    val shown = points.filter { it.hr != null && (days == 0 || it.date >= today.minusDays(days - 1L)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegRow(listOf("30 dni", "90 dni", "Rok", "Wszystko"), range, { range = it })
        if (shown.size < 2) {
            Hint("W tym okresie jest za mało zapisów EKG, żeby narysować trend.")
            return@Column
        }
        val grid = pal.line; val ink3 = pal.ink3; val accent = pal.accent; val warn = pal.warn; val surface = pal.surface
        val values = shown.map { it.hr ?: 0.0 }
        val lo = Math.floor((values.min() - 5) / 10) * 10
        val hi = Math.max(Math.ceil((values.max() + 5) / 10) * 10, lo + 30)
        fun t(p: EcgTrendPoint) = p.date.toEpochDay() + p.time.minutes / 1440.0
        val t0 = t(shown.first()); val span = Math.max(1.0, t(shown.last()) - t0)
        val axisW = 34
        val chartH = 160
        val scroll = rememberScrollState()
        LaunchedEffect(shown.size, range) {
            withTimeoutOrNull(600) { snapshotFlow { scroll.maxValue }.first { it > 0 } }?.let { scroll.scrollTo(it) }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewport = maxWidth.value - axisW
            val padL = 8f; val padR = 14f
            val width = Math.max(viewport, span.toFloat() * 7f + padL + padR)
            val pxPerDay = (width - padL - padR) / span.toFloat()
            Row(Modifier.fillMaxWidth().semantics { contentDescription = "Wykres tętna z zapisów EKG, przewijany w poziomie: " + EcgTrend.summary(shown) }) {
                Canvas(Modifier.width(axisW.dp).height(chartH.dp)) {
                    val k = density
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink3.toArgb(); textSize = 10f * k; textAlign = android.graphics.Paint.Align.RIGHT }
                    var g = lo
                    while (g <= hi) {
                        val y = (8f + ((hi - g) / (hi - lo)).toFloat() * (chartH - 8f - 24f)) * k
                        drawContext.canvas.nativeCanvas.drawText(g.toInt().toString(), (axisW - 4) * k, y + 3.5f * k, paint)
                        g += 10.0
                    }
                }
                Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                    Canvas(Modifier.width(width.dp).height(chartH.dp)) {
                        val k = density
                        fun x(p: EcgTrendPoint) = (padL + ((t(p) - t0) / span).toFloat() * (width - padL - padR)) * k
                        fun y(v: Double) = (8f + ((hi - v) / (hi - lo)).toFloat() * (chartH - 8f - 24f)) * k
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink3.toArgb(); textSize = 10f * k }
                        var g = lo
                        while (g <= hi) { drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), strokeWidth = 1f); g += 10.0 }
                        // podpisy dat: krok dobrany do skali (co najmniej 56 dp między podpisami)
                        val step = listOf(1, 2, 7, 14, 30, 61, 91, 182, 365).firstOrNull { it * pxPerDay >= 56f } ?: 365
                        val first = shown.first().date
                        var i = 0
                        while (i * step <= span + 1) {
                            val d = first.plusDays(i * step.toLong())
                            // pierwszy podpis stoi przy pierwszym zapisie (zapis ma godzinę, więc północ jego dnia wypadałaby tuż przed osią)
                            val px = if (i == 0) padL * k else (padL + ((d.toEpochDay() - t0) / span).toFloat() * (width - padL - padR)) * k
                            if (px >= 0f && px <= size.width) {
                                drawLine(grid.copy(alpha = .5f), Offset(px, y(hi)), Offset(px, y(lo)), strokeWidth = 1f)
                                paint.textAlign = if (i == 0) android.graphics.Paint.Align.LEFT else android.graphics.Paint.Align.CENTER
                                drawContext.canvas.nativeCanvas.drawText(shortDate(d) + if (step >= 91) "." + d.year % 100 else "", px, size.height - 6f * k, paint)
                            }
                            i++
                        }
                        val path = Path()
                        shown.forEachIndexed { n, p -> val px = x(p); val py = y(p.hr ?: lo); if (n == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                        drawPath(path, accent, style = Stroke(width = 2f * k))
                        shown.forEach { p ->
                            val c = Offset(x(p), y(p.hr ?: lo))
                            drawCircle(if (p.attention > 0) warn else accent, 5f * k, c)
                            drawCircle(surface, 5f * k, c, style = Stroke(width = 1.5f * k))
                        }
                    }
                }
            }
        }
    }
}

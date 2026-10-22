package pl.asystent.leki.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculatePan
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
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.deleteEcg
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
fun LazyListScope.ecgSection(s: AppState, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    item {
        Column(Modifier.padding(top = 14.dp)) {
            BodyText("Zapisy EKG z zegarka lub opaski (jedno odprowadzenie). Wczytaj wydruk PDF z aplikacji zegarka: aplikacja odtworzy przebieg, policzy tętno i odstępy między uderzeniami oraz wskaże ewentualne nieregularności do omówienia z lekarzem.")
            LekiButton("Dodaj EKG z pliku PDF", onAdd, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true, icon = Ph.Heartbeat)
        }
    }
    item { EcgMethods() }
    if (s.ecgs.isEmpty()) item { EmptyBox("Brak zapisów EKG.", Modifier.padding(top = 16.dp)) }
    else item {
        LekiCard(Modifier.padding(top = 16.dp)) {
            s.ecgs.sortedWith(compareByDescending<Ecg> { it.date }.thenByDescending { it.time }).forEachIndexed { i, e ->
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
                    PhIcon(Ph.CaretRight, size = 18.dp, tint = pal.ink3)
                }
            }
        }
    }
}

/** Renderuje stronę, odtwarza przebieg i odczytuje nagłówek; null, gdy w pliku nie ma przebiegu EKG. */
private suspend fun readPdfEcg(ctx: android.content.Context, uri: android.net.Uri, dpi: Int): Ecg? {
    // przebieg: strona w pełnej rozdzielczości jest od razu zamieniana na maskę jednobajtową (czwarta część pamięci bitmapy) i zwalniana
    val dig = run {
        val bmp = ScanImages.renderFirstPdfPage(ctx, uri, dpi)
        try {
            val w = bmp.width; val h = bmp.height
            val m = ByteArray(w * h); val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                val o = y * w
                for (x in 0 until w) if (EcgDigitizer.isTrace(row[x])) m[o + x] = 1
            }
            EcgDigitizer.digitizeMask(m, w, h, dpi / 25.4)
        } finally { bmp.recycle() }
    } ?: return null
    // nagłówek wydruku (data, tętno, ocena rytmu): osobny, mały render 200 dpi, na którym OCR był sprawdzony
    val meta = runCatching {
        val small = ScanImages.renderFirstPdfPage(ctx, uri, DPI_FALLBACK)
        val upright = if (dig.vertical) Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(-90f) }, true).also { small.recycle() } else small
        OcrEngine().use { eng -> EcgMeta.parse(LabScan.rows(eng.recognize(upright))) }
    }.getOrNull()
    val now = java.time.LocalDateTime.now()
    return Ecg(
        newId(), meta?.date ?: now.toLocalDate(), meta?.time ?: now.toLocalTime().withSecond(0).withNano(0),
        watchHr = meta?.hr ?: 0, watchNote = meta?.note.orEmpty(), fs = 250, cmv = EcgVector.normalize(EcgAnalysis.toRecord(dig.mv, dig.fs, 250)),
    )
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
                if (draft == null) toast("Nie znaleziono przebiegu EKG w tym pliku. Wczytaj PDF wyeksportowany z aplikacji zegarka.") else onDraft(draft)
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
fun EcgZoomView(e: Ecg, r: EcgResult) {
    val sig = remember(e) { e.signalMv() }
    val total = e.durationS.toFloat().coerceAtLeast(EcgViewport.MIN_VISIBLE)
    var startSec by rememberSaveable { mutableStateOf(0f) }
    var visibleSec by rememberSaveable { mutableStateOf(minOf(10f, total)) }
    var focus by remember { mutableStateOf<EcgEvent?>(null) }
    fun window() = EcgViewport.Window(startSec, visibleSec)
    fun show(w: EcgViewport.Window) { val c = EcgViewport.clamp(w, total); startSec = c.start; visibleSec = c.visible }
    fun jump(ev: EcgEvent) { focus = ev; show(EcgViewport.forEvent(ev, total)) }
    fun preset(sec: Float) {
        val mid = focus?.let { ((it.tSec + (it.endSec ?: it.tSec)) / 2).toFloat() } ?: (window().start + window().visible / 2)
        if (sec >= total) show(EcgViewport.whole(total)) else show(EcgViewport.centerOn(mid, sec, total))
    }
    val grid = pal.line; val ink = pal.ink; val accent = pal.accent; val warn = pal.warn; val ink3 = pal.ink3
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
                .pointerInput(total) {
                    // jeden detektor na jeden i dwa palce: przesuwanie poziome oraz powiększanie wokół punktu dotyku; ruch pionowy zostaje dla przewijania ekranu
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            val z = ev.calculateZoom(); val pan = ev.calculatePan()
                            val pinch = ev.changes.size >= 2
                            if ((pinch && z != 1f) || Math.abs(pan.x) > Math.abs(pan.y)) {
                                var win = window()
                                if (pinch && z != 1f) win = EcgViewport.zoom(win, z, (ev.calculateCentroid(useCurrent = true).x / size.width).coerceIn(0f, 1f), total)
                                show(EcgViewport.pan(win, -pan.x / size.width, total))
                                ev.changes.forEach { it.consume() }
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
            while (sec <= t1) { drawContext.canvas.nativeCanvas.drawText("$sec s", x(sec.toDouble()) + 3f * k, h - 4f * k, txt); sec += step }
        }
        // mapa zapisu: położenie okna i zdarzeń; dotknięcie przesuwa okno
        Canvas(
            Modifier.fillMaxWidth().height(26.dp).pointerInput(total) {
                detectTapGestures { off -> show(EcgViewport.centerOn(off.x / size.width * total, visibleSec, total)) }
            }.semantics { contentDescription = "Mapa zapisu: dotknij, aby przesunąć widok" },
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
        Hint("Przeciągnij wykres palcem, dwoma palcami powiększaj i zmniejszaj, a mapą pod nim przeskakuj po zapisie. Mała kratka to 0,04 s i 0,1 mV (jak na papierze 25 mm/s i 10 mm/mV). Trójkąty to wykryte uderzenia, szare tło to pominięte zakłócenia.")

        // lista nieprawidłowości: dotknięcie pozycji pokazuje to miejsce na wykresie
        val located = setOf("premature", "premature_many", "pause", "artifacts")
        val general = r.flags.filter { it.code !in located && it.code != "none" }
        Overline("Nieprawidłowości w zapisie", Modifier.padding(top = 6.dp))
        if (r.events.isEmpty() && general.isEmpty()) {
            Hint("Aplikacja nie wykryła nieprawidłowości w czytelnych fragmentach. To nie wyklucza choroby serca.")
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            r.events.forEach { ev ->
                val attention = ev.severity == EcgSeverity.Attention
                val selected = focus == ev
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
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time) }
    var hr by remember { mutableStateOf(if (initial.watchHr > 0) initial.watchHr.toString() else "") }
    var watchNote by remember { mutableStateOf(initial.watchNote) }
    var note by remember { mutableStateOf(initial.note) }
    var confirmDelete by remember { mutableStateOf(false) }
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
        Hint("Przebieg odtworzony z wydruku. Sprawdź, czy wygląda tak jak na wydruku z zegarka; jeśli nie, nie opieraj się na analizie.")
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
        if (!isNew) LinkButton("Usuń to EKG", { confirmDelete = true }, danger = true)
    }
    if (zoom) FullScreenDialog("Przebieg EKG", { zoom = false }) { EcgZoomView(initial, result) }
    if (confirmDelete) ConfirmDialog("Usunąć EKG?", "Zapis zniknie z aplikacji, raportów i przyszłych kopii zapasowych.", "Usuń",
        onConfirm = { store.update { it.deleteEcg(initial.id) }; toast("Usunięto EKG"); onClose() }, onDismiss = { confirmDelete = false }, danger = true)
}

package pl.asystent.leki.ui

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.OcrLine
import pl.asystent.leki.ocr.OcrEngine

/**
 * Ręczne zaznaczanie tekstu na zdjęciu, gdy automatyczny odczyt się pomylił: dotknięcie obrysowanego napisu bierze ten napis, a narysowanie prostokąta
 * palcem odczytuje zaznaczony obszar jeszcze raz, z powiększeniem (dokładniej niż przy całym zdjęciu). [targets] to pola, do których można wstawić tekst
 * (przy jednym polu wybór się nie pokazuje). [onResult] dostaje numer pola i wiersze tekstu.
 */
@Composable
fun PhotoPickDialog(
    title: String, hint: String, bitmap: Bitmap, lines: List<OcrLine>, targets: List<String>, initialTarget: Int,
    closeOnResult: Boolean, onResult: (target: Int, rows: List<String>) -> Unit, onClose: () -> Unit,
) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(initialTarget.coerceIn(0, maxOf(0, targets.lastIndex))) }
    var a by remember { mutableStateOf<Offset?>(null) }
    var b by remember { mutableStateOf<Offset?>(null) }
    var busy by remember { mutableStateOf(false) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val line = pal.accent; val fill = pal.accent.copy(alpha = .22f); val boxes = pal.accent.copy(alpha = .75f)

    fun deliver(rows: List<String>) {
        if (rows.all { it.isBlank() }) { toast("Nie rozpoznano tekstu w tym miejscu. Zaznacz obszar nieco szerzej."); return }
        onResult(target, rows)
        if (closeOnResult) onClose()
    }

    FullScreenDialog(title, onClose) {
        BodyText(hint)
        if (targets.size > 1) {
            Text("Wstaw do pola", style = TextStyle(fontSize = 13.sp, color = pal.ink2))
            SegRow(targets, target, { target = it })
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .semantics { contentDescription = "Zdjęcie do zaznaczania tekstu: dotknij napisu albo narysuj prostokąt" }
                .pointerInput(bitmap, lines) {
                    detectTapGestures { off ->
                        val sx = bitmap.width / size.width.toFloat()
                        val hit = lines.filter { off.x * sx in it.left.toFloat()..it.right.toFloat() && off.y * sx in it.top.toFloat()..it.bottom.toFloat() }.minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                        if (hit != null && !busy) deliver(listOf(hit.text))
                    }
                }
                .pointerInput(bitmap) {
                    detectDragGestures(
                        onDragStart = { if (!busy) { a = it; b = it } },
                        onDrag = { change, d -> change.consume(); if (!busy) b = (b ?: change.position) + d },
                        onDragCancel = { a = null; b = null },
                        onDragEnd = {
                            val p = a; val q = b
                            a = null; b = null
                            if (p == null || q == null || busy) return@detectDragGestures
                            val sx = bitmap.width / size.width.toFloat()
                            val r = Rect((minOf(p.x, q.x) * sx).toInt(), (minOf(p.y, q.y) * sx).toInt(), (maxOf(p.x, q.x) * sx).toInt(), (maxOf(p.y, q.y) * sx).toInt())
                            if (r.width() < 24 || r.height() < 12) return@detectDragGestures
                            busy = true
                            scope.launch {
                                try {
                                    val rows = OcrEngine().use { eng -> LabScan.rows(eng.recognizeCrop(bitmap, r)) }
                                    deliver(rows)
                                } catch (e: Exception) {
                                    toast("Nie udało się odczytać zaznaczenia.")
                                } finally { busy = false }
                            }
                        },
                    )
                },
        ) {
            drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            val k = size.width / bitmap.width
            lines.forEach { l -> drawRect(boxes, Offset(l.left * k, l.top * k), Size((l.right - l.left) * k, (l.bottom - l.top) * k), style = Stroke(width = 1.5f)) }
            val p = a; val q = b
            if (p != null && q != null) {
                val tl = Offset(minOf(p.x, q.x), minOf(p.y, q.y)); val sz = Size(Math.abs(p.x - q.x), Math.abs(p.y - q.y))
                drawRect(fill, tl, sz); drawRect(line, tl, sz, style = Stroke(width = 3f))
            }
        }
        if (busy) Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(color = pal.accent)
            Hint("Odczytuję zaznaczony obszar…")
        }
        LekiButton("Gotowe", onClose, Modifier.fillMaxWidth(), tonal = true)
    }
}

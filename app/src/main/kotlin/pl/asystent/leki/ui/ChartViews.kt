package pl.asystent.leki.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.asystent.leki.core.Anchor
import pl.asystent.leki.core.TrendGeo
import pl.asystent.leki.core.LabChartGeo
import pl.asystent.leki.core.Pt

private fun DrawScope.text(t: String, x: Float, y: Float, size: Float, color: Color, align: Paint.Align, bold: Boolean = false) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = color.toArgb(); p.textSize = size; p.textAlign = align
    p.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    drawContext.canvas.nativeCanvas.drawText(t, x, y, p)
}

private fun anchor(a: Anchor) = when (a) { Anchor.Start -> Paint.Align.LEFT; Anchor.End -> Paint.Align.RIGHT; Anchor.Middle -> Paint.Align.CENTER }

/** Wykres ciśnienia w czasie: oś pionowa stała po lewej, wykres przewijany w poziomie (domyślnie na końcu, czyli przy najnowszych dniach). */
@Composable
fun BpTrendView(g: TrendGeo, description: String, modifier: Modifier = Modifier) {
    val line = pal.line; val ink3 = pal.ink3; val accent = pal.accent; val surface = pal.surface; val warn = pal.warn; val danger = pal.danger
    val scroll = rememberScrollState()
    LaunchedEffect(g) {
        withTimeoutOrNull(600) { snapshotFlow { scroll.maxValue }.first { it > 0 } }?.let { scroll.scrollTo(it) }
    }
    Row(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.width(AXIS_W.dp).height(g.height.dp)) {
            val k = density
            g.yTicks.forEach { text(it.label, (AXIS_W - 4) * k, (it.pos + 3.5f) * k, 10f * k, ink3, Paint.Align.RIGHT) }
        }
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Canvas(Modifier.width(g.width.dp).height(g.height.dp)) {
                val k = density
                fun xy(x: Float, y: Float) = Offset(x * k, y * k)
                g.yTicks.forEach { drawLine(line, xy(0f, it.pos), xy(g.width, it.pos), strokeWidth = 1f) }
                val dash = PathEffect.dashPathEffect(floatArrayOf(4f * k, 3f * k))
                g.refs.forEach { drawLine(warn.copy(alpha = .8f), xy(0f, it), xy(g.width, it), strokeWidth = 1.2f * k, pathEffect = dash) }
                g.marks.forEach { m ->
                    if (m.line) drawLine(line.copy(alpha = .7f), xy(m.x, g.plotTop), xy(m.x, g.plotBottom), strokeWidth = 1f)
                    m.label?.let { text(it, m.x * k + 2f * k, (g.height - 8) * k, 10f * k, ink3, Paint.Align.LEFT) }
                }
                fun poly(pts: List<Offset>, color: Color) {
                    if (pts.size < 2) return
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, color, style = Stroke(width = 2f * k))
                }
                if (g.detailed) {
                    poly(g.readings.map { xy(it.x, it.dia) }, ink3); poly(g.readings.map { xy(it.x, it.sys) }, accent)
                    g.readings.forEach {
                        drawCircle(surface, 4f * k, xy(it.x, it.dia)); drawCircle(ink3, 4f * k, xy(it.x, it.dia), style = Stroke(width = 2f * k))
                        // pomiar na progu lub powyżej niego (ustawianym w Ustawieniach) jest czerwony i większy
                        val c = if (it.high) danger else accent
                        drawCircle(c, (if (it.high) 5.2f else 4f) * k, xy(it.x, it.sys)); drawCircle(surface, (if (it.high) 5.2f else 4f) * k, xy(it.x, it.sys), style = Stroke(width = 1.5f * k))
                        drawCircle(c, (if (it.high) 4.2f else 3.2f) * k, xy(it.x, it.sys))
                    }
                } else {
                    val bw = (g.dayWidth * 0.55f).coerceIn(1.2f, 6f) * k
                    g.days.forEach {
                        drawLine(ink3.copy(alpha = .35f), xy(it.x, it.diaMin), xy(it.x, it.diaMax), strokeWidth = bw)
                        drawLine(accent.copy(alpha = .35f), xy(it.x, it.sysMin), xy(it.x, it.sysMax), strokeWidth = bw)
                    }
                    poly(g.days.map { xy(it.x, it.dia) }, ink3); poly(g.days.map { xy(it.x, it.sys) }, accent)
                    if (g.dayWidth >= 7f) g.days.forEach { drawCircle(if (it.high) danger else accent, (if (it.high) 2.6f else 1.8f) * k, xy(it.x, it.sys)); drawCircle(ink3, 1.8f * k, xy(it.x, it.dia)) }
                }
            }
        }
    }
}

private const val AXIS_W = 34

@Composable
fun LabChartView(g: LabChartGeo, description: String, modifier: Modifier = Modifier) {
    val line = pal.line; val ink3 = pal.ink3; val accent = pal.accent; val soft = pal.accentSoft; val danger = pal.danger; val ink = pal.ink; val surface = pal.surface
    Canvas(modifier.fillMaxWidth().aspectRatio(g.width.toFloat() / g.height).semantics { contentDescription = description }) {
        val k = size.width / g.width
        g.band?.let { drawRect(soft, Offset(g.plotLeft * k, it.first * k), Size((g.plotRight - g.plotLeft) * k, (it.second - it.first) * k)) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(3f * k, 3f * k))
        g.limitLines.forEach { drawLine(accent.copy(alpha = .7f), Offset(g.plotLeft * k, it * k), Offset(g.plotRight * k, it * k), strokeWidth = 1f, pathEffect = dash) }
        g.yTicks.forEach {
            drawLine(line.copy(alpha = .55f), Offset(g.plotLeft * k, it.pos * k), Offset(g.plotRight * k, it.pos * k), strokeWidth = 1f)
            text(it.label, (g.plotLeft - 6) * k, (it.pos + 3.5f) * k, 10f * k, ink3, Paint.Align.RIGHT)
        }
        g.xLabels.forEach { text(it.label, it.x * k, (g.height - 8) * k, 10f * k, ink3, anchor(it.anchor)) }
        if (g.points.size > 1) {
            val path = Path().apply { moveTo(g.points[0].x * k, g.points[0].y * k); g.points.drop(1).forEach { lineTo(it.x * k, it.y * k) } }
            drawPath(path, accent, style = Stroke(width = 2f * k))
        }
        g.points.forEach { p ->
            drawCircle(if (p.out) danger else accent, 4.5f * k, Offset(p.x * k, p.y * k))
            drawCircle(surface, 4.5f * k, Offset(p.x * k, p.y * k), style = Stroke(width = 1.5f * k))
            p.label?.let { l ->
                val tx = p.x.coerceIn(g.plotLeft + 8, g.plotRight - 8)
                text(l, tx * k, (p.y - 9) * k, 10f * k, ink, Paint.Align.CENTER, bold = true)
            }
        }
    }
}

/** Mały wykres trendu przy parametrze badania (ostatnie wyniki). */
@Composable
fun Sparkline(values: List<Double>, out: Boolean, modifier: Modifier = Modifier.width(56.dp).height(22.dp)) {
    val stroke = pal.ink3; val dot = if (out) pal.warn else pal.accent
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val pts = values.takeLast(8)
        val mn = pts.min(); val mx = pts.max()
        val pd = 3.dp.toPx()
        fun x(i: Int) = pd + i * (size.width - 2 * pd) / (pts.size - 1)
        fun y(v: Double) = if (mx == mn) size.height / 2 else (pd + (mx - v) / (mx - mn) * (size.height - 2 * pd)).toFloat()
        val path = Path().apply { moveTo(x(0), y(pts[0])); for (i in 1 until pts.size) lineTo(x(i), y(pts[i])) }
        drawPath(path, stroke, style = Stroke(width = 1.6.dp.toPx()))
        drawCircle(dot, 2.6.dp.toPx(), Offset(x(pts.size - 1), y(pts.last())))
    }
}

package pl.asystent.leki.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.asystent.leki.core.Anchor
import pl.asystent.leki.core.Trend
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

/**
 * Wykres ciśnienia w czasie: oś pionowa stała po lewej (wartości progów wyróżnione), wykres przewijany w poziomie (domyślnie na końcu, czyli przy najnowszych dniach).
 * Pole między skurczowym a rozkurczowym jest lekko zacienione, a przerwy od [Trend.GAP_DAYS] dni bez pomiaru są zaznaczone jako „brak pomiarów” i linia się w nich urywa.
 */
@Composable
fun BpTrendView(g: TrendGeo, description: String, modifier: Modifier = Modifier) {
    val line = pal.line; val ink3 = pal.ink3; val accent = pal.accent; val soft = pal.accentSoft; val surface = pal.surface; val warn = pal.warn; val danger = pal.danger
    val scroll = rememberScrollState()
    LaunchedEffect(g) {
        withTimeoutOrNull(600) { snapshotFlow { scroll.maxValue }.first { it > 0 } }?.let { scroll.scrollTo(it) }
    }
    Row(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.width(AXIS_W.dp).height(g.height.dp)) {
            val k = density
            Trend.axisLabels(g).forEach { (t, ref) -> text(t.label, (AXIS_W - 4) * k, (t.pos + 3.5f) * k, 10f * k, if (ref) warn else ink3, Paint.Align.RIGHT, bold = ref) }
        }
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Canvas(Modifier.width(g.width.dp).height(g.height.dp)) {
                val k = density
                fun xy(x: Float, y: Float) = Offset(x * k, y * k)
                g.gaps.forEach { gp ->
                    drawRect(line.copy(alpha = .3f), xy(gp.x1, g.plotTop), Size((gp.x2 - gp.x1) * k, (g.plotBottom - g.plotTop) * k))
                    if (gp.x2 - gp.x1 >= Trend.GAP_LABEL_MIN) text("brak pomiarów", (gp.x1 + gp.x2) / 2 * k, (g.plotTop + 13f) * k, 10f * k, ink3, Paint.Align.CENTER)
                }
                Trend.axisLabels(g).filter { !it.second }.forEach { (t, _) -> drawLine(line.copy(alpha = .6f), xy(0f, t.pos), xy(g.width, t.pos), strokeWidth = 1f) }
                drawLine(line, xy(0f, g.plotBottom), xy(g.width, g.plotBottom), strokeWidth = 1f * k)
                g.marks.forEach { m ->
                    if (m.line) drawLine(line.copy(alpha = .6f), xy(m.x, g.plotTop), xy(m.x, g.plotBottom), strokeWidth = 1f)
                    m.label?.let { text(it, m.x * k + 2f * k, (g.height - 8) * k, 10f * k, ink3, Paint.Align.LEFT) }
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(5f * k, 4f * k))
                g.refs.forEach { drawLine(warn.copy(alpha = .75f), xy(0f, it), xy(g.width, it), strokeWidth = 1.2f * k, pathEffect = dash) }
                fun band(sys: List<Offset>, dia: List<Offset>) {
                    if (sys.size < 2) return
                    val all = sys + dia.reversed()
                    drawPath(Path().apply { moveTo(all[0].x, all[0].y); all.drop(1).forEach { lineTo(it.x, it.y) }; close() }, soft.copy(alpha = .55f))
                }
                fun poly(pts: List<Offset>, color: Color, w: Float) {
                    if (pts.size < 2) return
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, color, style = Stroke(width = w * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                fun sysDot(o: Offset, high: Boolean, r: Float) {
                    val rr = if (high) r + 1f else r
                    drawCircle(if (high) danger else accent, rr * k, o); drawCircle(surface, rr * k, o, style = Stroke(width = 1.5f * k))
                }
                fun diaDot(o: Offset, high: Boolean, r: Float) {
                    drawCircle(surface, r * k, o); drawCircle(if (high) danger else ink3, r * k, o, style = Stroke(width = (if (high) 2.4f else 2f) * k))
                }
                if (g.detailed) {
                    g.readingRuns.forEach { r -> val l = g.readings.slice(r); band(l.map { xy(it.x, it.sys) }, l.map { xy(it.x, it.dia) }) }
                    g.readingRuns.forEach { r -> val l = g.readings.slice(r); poly(l.map { xy(it.x, it.dia) }, ink3, 1.8f); poly(l.map { xy(it.x, it.sys) }, accent, 2.2f) }
                    // pomiar na progu lub powyżej niego (ustawianym w Ustawieniach) jest czerwony: skurczowe pełne, rozkurczowe z czerwoną obwódką
                    g.readings.forEach { diaDot(xy(it.x, it.dia), it.diaHigh, 3.6f); sysDot(xy(it.x, it.sys), it.sysHigh, 4f) }
                } else {
                    val bw = (g.dayWidth * 0.55f).coerceIn(1.2f, 6f) * k
                    g.dayRuns.forEach { r -> val l = g.days.slice(r); band(l.map { xy(it.x, it.sys) }, l.map { xy(it.x, it.dia) }) }
                    g.days.filter { it.n > 1 }.forEach {
                        if (it.diaMin - it.diaMax >= .5f) drawLine(ink3.copy(alpha = .3f), xy(it.x, it.diaMin), xy(it.x, it.diaMax), strokeWidth = bw, cap = StrokeCap.Round)
                        if (it.sysMin - it.sysMax >= .5f) drawLine(accent.copy(alpha = .3f), xy(it.x, it.sysMin), xy(it.x, it.sysMax), strokeWidth = bw, cap = StrokeCap.Round)
                    }
                    g.dayRuns.forEach { r -> val l = g.days.slice(r); poly(l.map { xy(it.x, it.dia) }, ink3, 1.8f); poly(l.map { xy(it.x, it.sys) }, accent, 2.2f) }
                    val lone = g.dayRuns.filter { it.first == it.last }.map { it.first }.toSet()
                    g.days.forEachIndexed { i, d ->
                        if (d.diaHigh || i in lone || g.dayWidth >= 12f) diaDot(xy(d.x, d.dia), d.diaHigh, 2.4f)
                        if (d.sysHigh || i in lone || g.dayWidth >= 12f) sysDot(xy(d.x, d.sys), d.sysHigh, 2.6f)
                    }
                }
            }
        }
    }
}

/** Legenda wykresu ciśnienia: znaczniki narysowane tak samo jak na wykresie, zawijane na wąskim ekranie. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpLegend(hiSys: Int, hiDia: Int, modifier: Modifier = Modifier) {
    val accent = pal.accent; val ink3 = pal.ink3; val danger = pal.danger; val warn = pal.warn; val surface = pal.surface
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem("skurczowe") { drawCircle(accent, 4.5.dp.toPx(), center) }
        LegendItem("rozkurczowe") { drawCircle(surface, 4.dp.toPx(), center); drawCircle(ink3, 4.dp.toPx(), center, style = Stroke(width = 2.dp.toPx())) }
        LegendItem("na progu lub powyżej") { drawCircle(danger, 4.5.dp.toPx(), center) }
        LegendItem("próg $hiSys/$hiDia") { drawLine(warn, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))) }
        Text("mmHg", style = TextStyle(fontSize = 12.5.sp, color = pal.ink3))
    }
}

@Composable
private fun LegendItem(label: String, draw: DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(18.dp).height(12.dp).clearAndSetSemantics { }, onDraw = draw)
        Text(label, style = TextStyle(fontSize = 12.5.sp, color = pal.ink2))
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
        if (g.ghost.isNotEmpty()) {
            // rok wcześniej: jaśniejsza przerywana linia i puste kółka
            if (g.ghost.size > 1) drawPath(Path().apply { moveTo(g.ghost[0].x * k, g.ghost[0].y * k); g.ghost.drop(1).forEach { lineTo(it.x * k, it.y * k) } }, ink3.copy(alpha = .8f), style = Stroke(width = 1.5f * k, pathEffect = dash))
            g.ghost.forEach { drawCircle(ink3, 3.5f * k, Offset(it.x * k, it.y * k), style = Stroke(width = 1.4f * k)) }
        }
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

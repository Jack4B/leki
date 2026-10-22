package pl.asystent.leki.core

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Geometria wykresów we współrzędnych pudełka [width] x [height]; rysują ją i SVG (raport), i Compose (aplikacja). */
data class Pt(val x: Float, val y: Float)
data class Tick(val pos: Float, val label: String)
data class XLabel(val x: Float, val label: String, val anchor: Anchor)
enum class Anchor { Start, Middle, End }

/** Jeden dzień na osi czasu: pionowa kreska oddzielająca od poprzedniego dnia i/lub podpis daty. */
data class DayMark(val separator: Float?, val label: XLabel?)

data class BpChartGeo(
    val width: Int, val height: Int, val plotLeft: Float, val plotRight: Float, val plotTop: Float, val plotBottom: Float,
    val yTicks: List<Tick>, val dayMarks: List<DayMark>,
    val sys: List<Pt>, val dia: List<Pt>, val radius: Float,
)

fun bpChart(list: List<Bp>): BpChartGeo? {
    if (list.isEmpty()) return null
    val W = 360; val H = 200; val pl = 34f; val pr = 10f; val pt = 10f; val pb = 26f
    fun ms(d: LocalDate, t: java.time.LocalTime? = null): Long = (if (t == null) d.atStartOfDay() else d.atTime(t)).toEpochSecond(ZoneOffset.UTC) * 1000
    val p = list.map { Triple(ms(it.date, it.time), it.sys, it.dia) }.sortedBy { it.first }
    val dMin = list.minOf { it.date }; val dMax = list.maxOf { it.date }
    val nDays = daysBetween(dMin, dMax) + 1
    val t0 = ms(dMin).toDouble(); val t1 = ms(dMax.plusDays(1)).toDouble()
    val lo = floor((p.minOf { it.third } - 10) / 10.0) * 10
    val hi = ceil((p.maxOf { it.second } + 10) / 10.0) * 10
    fun x(t: Double) = (pl + (t - t0) / (t1 - t0) * (W - pl - pr)).toFloat()
    fun y(v: Double) = (pt + (hi - v) / (hi - lo) * (H - pt - pb)).toFloat()
    val step = if (hi - lo > 80) 20 else 10
    val ticks = ArrayList<Tick>()
    var v = ceil(lo / step) * step
    while (v <= hi && ticks.size < 40) { ticks.add(Tick(y(v), v.toInt().toString())); v += step }
    val every = ceil(nDays / 7.0).toInt()
    val marks = ArrayList<DayMark>()
    for (i in 0 until nDays) {
        val day = dMin.plusDays(i.toLong())
        val xs = x(ms(day).toDouble()); val xe = x(ms(day.plusDays(1)).toDouble())
        marks.add(DayMark(if (i > 0 && nDays <= 45) xs else null, if (i % every == 0) XLabel((xs + xe) / 2, shortDate(day), Anchor.Middle) else null))
    }
    return BpChartGeo(W, H, pl, W - pr, pt, H - pb, ticks, marks,
        p.map { Pt(x(it.first.toDouble()), y(it.second.toDouble())) }, p.map { Pt(x(it.first.toDouble()), y(it.third.toDouble())) },
        if (p.size > 60) 2f else 3.5f)
}

data class LabPoint(val x: Float, val y: Float, val value: Double, val out: Boolean, val label: String?)

data class LabChartGeo(
    val width: Int, val height: Int, val plotLeft: Float, val plotRight: Float,
    val band: Pair<Float, Float>?, val limitLines: List<Float>, val yTicks: List<Tick>, val xLabels: List<XLabel>, val points: List<LabPoint>,
)

fun labChart(list: List<Lab>, m: Marker): LabChartGeo? {
    if (list.isEmpty()) return null
    val W = 360; val H = 190; val pl = 42f; val pr = 14f; val pt = 14f; val pb = 26f
    val vals = list.map { it.value }.toMutableList()
    m.lo?.let { vals.add(it) }; m.hi?.let { vals.add(it) }
    val mn = vals.min(); val mx = vals.max()
    val pad = ((mx - mn) * 0.18).takeIf { it != 0.0 } ?: (abs(mx) * 0.1).takeIf { it != 0.0 } ?: 1.0
    var lo = mn - pad; var hi = mx + pad
    val raw = (hi - lo) / 4
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw - 1e-12 }
    lo = floor(lo / step + 1e-9) * step; hi = ceil(hi / step - 1e-9) * step
    if (mn >= 0 && lo < 0) lo = 0.0
    val d0 = list.first().date; val d1 = list.last().date
    val span = max(1, daysBetween(d0, d1)); val iw = W - pl - pr
    val single = list.size == 1 || d0 == d1
    fun x(d: LocalDate) = if (single) pl + iw / 2f else (pl + daysBetween(d0, d).toDouble() / span * iw).toFloat()
    fun y(v: Double) = (pt + (hi - v) / (hi - lo) * (H - pt - pb)).toFloat()
    var band: Pair<Float, Float>? = null
    val lines = ArrayList<Float>()
    if (m.lo != null || m.hi != null) {
        val yt = y(if (m.hi != null) min(m.hi, hi) else hi)
        val yb = y(if (m.lo != null) max(m.lo, lo) else lo)
        band = yt to max(yt, yb)
        m.hi?.let { lines.add(y(it)) }; m.lo?.let { lines.add(y(it)) }
    }
    val ticks = ArrayList<Tick>()
    var v = lo
    while (v <= hi + step / 1000) { ticks.add(Tick(y(v), lnum(Math.round(v * 1000) / 1000.0))); v += step }
    val nt = if (single) 1 else min(5, span + 1)
    val xl = ArrayList<XLabel>()
    for (i in 0 until nt) {
        val dt = if (nt == 1) d0 else d0.plusDays(Math.round(i.toDouble() * span / (nt - 1)))
        xl.add(XLabel(if (nt == 1) x(d0) else x(dt), shortDate(dt), if (nt == 1) Anchor.Middle else if (i == 0) Anchor.Start else if (i == nt - 1) Anchor.End else Anchor.Middle))
    }
    val pts = list.map { l ->
        val px = x(l.date)
        LabPoint(px, y(l.value), l.value, labFlag(l.value, m).isNotEmpty(), if (list.size <= 10) lnum(l.value) else null)
    }
    return LabChartGeo(W, H, pl, W - pr, band, lines, ticks, xl, pts)
}

// ---------- SVG (raport do wydruku) ----------

private fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
/** Stałe współrzędne wypisujemy jak liczby całkowite, gdy takie są (34 zamiast 34.0). */
private fun fr(v: Float) = if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()

fun bpChartSvg(g: BpChartGeo): String {
    val sb = StringBuilder()
    g.yTicks.forEach {
        sb.append("<line class=\"gr\" x1=\"${fr(g.plotLeft)}\" x2=\"${fr(g.plotRight)}\" y1=\"${f1(it.pos)}\" y2=\"${f1(it.pos)}\"/>")
        sb.append("<text class=\"ax\" x=\"${fr(g.plotLeft - 6)}\" y=\"${f1(it.pos + 3.5f)}\" text-anchor=\"end\">${it.label}</text>")
    }
    g.dayMarks.forEach { m ->
        m.separator?.let { sb.append("<line class=\"sepv\" x1=\"${f1(it)}\" x2=\"${f1(it)}\" y1=\"${fr(g.plotTop)}\" y2=\"${fr(g.plotBottom)}\"/>") }
        m.label?.let { sb.append("<text class=\"ax\" x=\"${f1(it.x)}\" y=\"${g.height - 8}\" text-anchor=\"middle\">${it.label}</text>") }
    }
    fun poly(pts: List<Pt>) = pts.joinToString(" ") { "${f1(it.x)},${f1(it.y)}" }
    if (g.sys.size > 1) sb.append("<polyline class=\"l-dia\" points=\"${poly(g.dia)}\"/><polyline class=\"l-sys\" points=\"${poly(g.sys)}\"/>")
    for (i in g.sys.indices) {
        sb.append("<circle class=\"p-dia\" cx=\"${f1(g.dia[i].x)}\" cy=\"${f1(g.dia[i].y)}\" r=\"${g.radius}\"/><circle class=\"p-sys\" cx=\"${f1(g.sys[i].x)}\" cy=\"${f1(g.sys[i].y)}\" r=\"${g.radius}\"/>")
    }
    return "<svg class=\"chart\" viewBox=\"0 0 ${g.width} ${g.height}\" role=\"img\" aria-label=\"Wykres ciśnienia skurczowego i rozkurczowego w czasie\">$sb</svg>"
}

fun labChartSvg(g: LabChartGeo, name: String): String {
    val sb = StringBuilder()
    g.band?.let { sb.append("<rect class=\"band\" x=\"${fr(g.plotLeft)}\" y=\"${f1(it.first)}\" width=\"${fr(g.plotRight - g.plotLeft)}\" height=\"${f1(it.second - it.first)}\"/>") }
    g.limitLines.forEach { sb.append("<line class=\"bl\" x1=\"${fr(g.plotLeft)}\" x2=\"${fr(g.plotRight)}\" y1=\"${f1(it)}\" y2=\"${f1(it)}\"/>") }
    g.yTicks.forEach {
        sb.append("<line class=\"gr\" x1=\"${fr(g.plotLeft)}\" x2=\"${fr(g.plotRight)}\" y1=\"${f1(it.pos)}\" y2=\"${f1(it.pos)}\" style=\"opacity:.55\"/>")
        sb.append("<text class=\"ax\" x=\"${fr(g.plotLeft - 6)}\" y=\"${f1(it.pos + 3.5f)}\" text-anchor=\"end\">${it.label}</text>")
    }
    g.xLabels.forEach {
        val a = when (it.anchor) { Anchor.Start -> "start"; Anchor.End -> "end"; Anchor.Middle -> "middle" }
        sb.append("<text class=\"ax\" x=\"${f1(it.x)}\" y=\"${g.height - 8}\" text-anchor=\"$a\">${it.label}</text>")
    }
    if (g.points.size > 1) sb.append("<polyline class=\"l-lab\" points=\"${g.points.joinToString(" ") { "${f1(it.x)},${f1(it.y)}" }}\"/>")
    g.points.forEach {
        sb.append("<circle class=\"p-lab${if (it.out) " out" else ""}\" cx=\"${f1(it.x)}\" cy=\"${f1(it.y)}\" r=\"4.5\"/>")
        it.label?.let { l ->
            val tx = min(g.plotRight - 8, max(g.plotLeft + 8, it.x))
            sb.append("<text class=\"vl\" x=\"${f1(tx)}\" y=\"${f1(it.y - 9)}\" text-anchor=\"middle\">$l</text>")
        }
    }
    return "<svg class=\"chart\" viewBox=\"0 0 ${g.width} ${g.height}\" role=\"img\" aria-label=\"Wykres: ${esc(name)} w czasie, z zakresem odniesienia\">$sb</svg>"
}

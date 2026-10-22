package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Podsumowanie pomiarów z jednego dnia. */
data class DayAgg(val date: LocalDate, val n: Int, val sys: Double, val dia: Double, val sysMin: Int, val sysMax: Int, val diaMin: Int, val diaMax: Int)

fun bpDaily(list: List<Bp>): List<DayAgg> = list.groupBy { it.date }.toSortedMap().map { (d, l) ->
    DayAgg(d, l.size, l.map { it.sys }.average(), l.map { it.dia }.average(), l.minOf { it.sys }, l.maxOf { it.sys }, l.minOf { it.dia }, l.maxOf { it.dia })
}

/** Podsumowanie pomiarów z tygodnia lub miesiąca (tabela w raporcie z długich okresów). */
data class BpPeriodRow(
    val from: LocalDate, val to: LocalDate, val n: Int, val sys: Int, val dia: Int, val pulse: Int?,
    val sysMin: Int, val sysMax: Int, val diaMin: Int, val diaMax: Int, val high: Int, val low: Int,
)

fun bpPeriods(list: List<Bp>, monthly: Boolean): List<BpPeriodRow> {
    fun key(d: LocalDate): LocalDate = if (monthly) d.withDayOfMonth(1) else d.minusDays((d.dayOfWeek.value - 1).toLong())
    return list.groupBy { key(it.date) }.toSortedMap().map { (start, l) ->
        val end = if (monthly) start.plusMonths(1).minusDays(1) else start.plusDays(6)
        val pulses = l.mapNotNull { it.pulse }
        BpPeriodRow(start, end, l.size, Math.round(l.map { it.sys }.average()).toInt(), Math.round(l.map { it.dia }.average()).toInt(),
            if (pulses.isEmpty()) null else Math.round(pulses.average()).toInt(), l.minOf { it.sys }, l.maxOf { it.sys }, l.minOf { it.dia }, l.maxOf { it.dia }, 0, 0)
    }
}

/** To samo z liczbą pomiarów podwyższonych i niskich według progów z ustawień. */
fun bpPeriods(list: List<Bp>, monthly: Boolean, c: Settings): List<BpPeriodRow> {
    val base = bpPeriods(list, monthly)
    fun key(d: LocalDate): LocalDate = if (monthly) d.withDayOfMonth(1) else d.minusDays((d.dayOfWeek.value - 1).toLong())
    val byKey = list.groupBy { key(it.date) }
    return base.map { r ->
        val l = byKey[r.from].orEmpty()
        r.copy(high = l.count { c.bpFlag(it) == "podwyższone" }, low = l.count { c.bpFlag(it) == "niskie" })
    }
}

data class TrendDay(val x: Float, val sys: Float, val dia: Float, val sysMin: Float, val sysMax: Float, val diaMin: Float, val diaMax: Float, val n: Int)
data class ReadingPt(val x: Float, val sys: Float, val dia: Float)
data class TrendMark(val x: Float, val line: Boolean, val label: String?)

/**
 * Geometria wykresu ciśnienia w czasie ze stałą skalą (jednostek na dzień), tak by długi okres (nawet rok) był czytelny i przewijalny.
 * Do 45 dni rysujemy pojedyncze pomiary; dłuższe okresy to średnie dzienne z pionowym zakresem min–max.
 */
data class TrendGeo(
    val width: Float, val height: Float, val padLeft: Float, val plotTop: Float, val plotBottom: Float,
    val yTicks: List<Tick>, val refs: List<Float>, val marks: List<TrendMark>,
    val days: List<TrendDay>, val readings: List<ReadingPt>, val detailed: Boolean, val dayWidth: Float,
)

object Trend {
    const val DETAIL_MAX_DAYS = 45
    private const val PAD_L = 8f
    private const val PAD_R = 10f
    private const val TOP = 10f
    private const val BOTTOM = 26f

    /** Szerokość jednego dnia w jednostkach wykresu zależnie od długości okresu. */
    fun dayWidthFor(nDays: Int): Float = when {
        nDays <= 14 -> 0f // dopasuj do szerokości
        nDays <= DETAIL_MAX_DAYS -> 26f
        nDays <= 120 -> 12f
        nDays <= 400 -> 7f
        else -> 4.5f
    }

    fun build(list: List<Bp>, from: LocalDate, to: LocalDate, minWidth: Float, hiSys: Int, hiDia: Int, height: Float = 200f): TrendGeo? {
        val inRange = list.filter { it.date >= from && it.date <= to }.sortedWith(bpOrder)
        if (inRange.isEmpty()) return null
        val nDays = daysBetween(from, to) + 1
        val detailed = nDays <= DETAIL_MAX_DAYS
        var dw = dayWidthFor(nDays)
        val fit = (minWidth - PAD_L - PAD_R) / nDays
        if (dw == 0f || dw * nDays < minWidth - PAD_L - PAD_R) dw = fit
        val width = max(minWidth, nDays * dw + PAD_L + PAD_R)
        val lo = floor((inRange.minOf { it.dia } - 10) / 10.0) * 10
        val hi = ceil((inRange.maxOf { it.sys } + 10) / 10.0) * 10
        fun y(v: Double) = (TOP + (hi - v) / (hi - lo) * (height - TOP - BOTTOM)).toFloat()
        fun x(d: LocalDate, t: LocalTime?) = (PAD_L + (daysBetween(from, d) + (t?.minutes ?: 720) / 1440.0) * dw).toFloat()
        val step = if (hi - lo > 80) 20 else 10
        val ticks = ArrayList<Tick>()
        var v = ceil(lo / step) * step
        while (v <= hi && ticks.size < 40) { ticks.add(Tick(y(v), v.toInt().toString())); v += step }
        val refs = listOf(hiSys.toDouble(), hiDia.toDouble()).filter { it in lo..hi }.map { y(it) }

        // podpisy osi: dni (krótkie okresy), tygodnie (średnie) albo początki miesięcy (długie okresy)
        val marks = ArrayList<TrendMark>()
        val gap = ceil(46f / dw).toInt().coerceAtLeast(1)
        for (i in 0 until nDays) {
            val d = from.plusDays(i.toLong())
            val xs = PAD_L + i * dw
            if (gap >= 7) {
                if (i == 0 || d.dayOfMonth == 1) marks.add(TrendMark(xs, d.dayOfMonth == 1 && i > 0, Pl.monthsShort[d.monthValue - 1] + if (d.monthValue == 1 || i == 0) " " + d.year % 100 else ""))
            } else {
                val monday = d.dayOfWeek.value == 1
                val line = i > 0 && (dw >= 14f || monday)
                val labelDay = when {
                    gap <= 1 -> true
                    gap <= 6 -> i % gap == 0
                    else -> monday && (i / 7) % ceil(gap / 7.0).toInt() == 0
                }
                if (line || labelDay) marks.add(TrendMark(xs, line, if (labelDay) shortDate(d) else null))
            }
        }
        val agg = bpDaily(inRange).map { a ->
            TrendDay(x(a.date, null), y(a.sys), y(a.dia), y(a.sysMin.toDouble()), y(a.sysMax.toDouble()), y(a.diaMin.toDouble()), y(a.diaMax.toDouble()), a.n)
        }
        val readings = if (detailed) inRange.map { ReadingPt(x(it.date, it.time), y(it.sys.toDouble()), y(it.dia.toDouble())) } else emptyList()
        return TrendGeo(width, height, PAD_L, TOP, height - BOTTOM, ticks, refs, marks, agg, readings, detailed, dw)
    }

    // ---------- SVG (raport do wydruku) ----------

    private fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)

    fun svg(g: TrendGeo): String {
        val sb = StringBuilder()
        g.yTicks.forEach {
            sb.append("<line class=\"gr\" x1=\"0\" x2=\"${f1(g.width)}\" y1=\"${f1(it.pos)}\" y2=\"${f1(it.pos)}\"/>")
        }
        g.refs.forEach { sb.append("<line class=\"ref\" x1=\"0\" x2=\"${f1(g.width)}\" y1=\"${f1(it)}\" y2=\"${f1(it)}\"/>") }
        g.marks.forEach { m ->
            if (m.line) sb.append("<line class=\"sepv\" x1=\"${f1(m.x)}\" x2=\"${f1(m.x)}\" y1=\"${f1(g.plotTop)}\" y2=\"${f1(g.plotBottom)}\"/>")
            m.label?.let { sb.append("<text class=\"ax\" x=\"${f1(m.x)}\" y=\"${f1(g.height - 8)}\" text-anchor=\"start\">$it</text>") }
        }
        if (g.detailed) {
            fun poly(sel: (ReadingPt) -> Float) = g.readings.joinToString(" ") { "${f1(it.x)},${f1(sel(it))}" }
            if (g.readings.size > 1) sb.append("<polyline class=\"l-dia\" points=\"${poly { it.dia }}\"/><polyline class=\"l-sys\" points=\"${poly { it.sys }}\"/>")
            g.readings.forEach { sb.append("<circle class=\"p-dia\" cx=\"${f1(it.x)}\" cy=\"${f1(it.dia)}\" r=\"3\"/><circle class=\"p-sys\" cx=\"${f1(it.x)}\" cy=\"${f1(it.sys)}\" r=\"3\"/>") }
        } else {
            val bw = (g.dayWidth * 0.55f).coerceIn(1.2f, 6f)
            g.days.forEach {
                sb.append("<line class=\"rng-sys\" x1=\"${f1(it.x)}\" x2=\"${f1(it.x)}\" y1=\"${f1(it.sysMin)}\" y2=\"${f1(it.sysMax)}\" stroke-width=\"${f1(bw)}\"/>")
                sb.append("<line class=\"rng-dia\" x1=\"${f1(it.x)}\" x2=\"${f1(it.x)}\" y1=\"${f1(it.diaMin)}\" y2=\"${f1(it.diaMax)}\" stroke-width=\"${f1(bw)}\"/>")
            }
            fun poly(sel: (TrendDay) -> Float) = g.days.joinToString(" ") { "${f1(it.x)},${f1(sel(it))}" }
            if (g.days.size > 1) sb.append("<polyline class=\"l-dia\" points=\"${poly { it.dia }}\"/><polyline class=\"l-sys\" points=\"${poly { it.sys }}\"/>")
        }
        // oś pionowa jako osobny pasek po lewej, żeby wykres miał stałą szerokość 100%
        val axis = g.yTicks.joinToString("") { "<text class=\"ax\" x=\"30\" y=\"${f1(it.pos + 3.5f)}\" text-anchor=\"end\">${it.label}</text>" }
        val total = g.width + 34
        return "<svg class=\"chart\" viewBox=\"0 0 ${f1(total)} ${f1(g.height)}\" role=\"img\" aria-label=\"Wykres ciśnienia w czasie\">$axis<g transform=\"translate(34 0)\">$sb</g></svg>"
    }
}

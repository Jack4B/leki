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

/** [sysHigh]/[diaHigh]: średnia dnia skurczowego (rozkurczowego) na progu lub powyżej; [high]: którekolwiek z nich. */
data class TrendDay(
    val x: Float, val sys: Float, val dia: Float, val sysMin: Float, val sysMax: Float, val diaMin: Float, val diaMax: Float, val n: Int, val high: Boolean = false,
    val sysHigh: Boolean = false, val diaHigh: Boolean = false,
)
/** [high]: pomiar na progu podwyższonego ciśnienia lub powyżej (skurczowe >= hiSys albo rozkurczowe >= hiDia); [sysHigh]/[diaHigh] mówią, która wartość. */
data class ReadingPt(val x: Float, val sys: Float, val dia: Float, val high: Boolean = false, val sysHigh: Boolean = false, val diaHigh: Boolean = false)
data class TrendMark(val x: Float, val line: Boolean, val label: String?)
/** Przedział osi poziomej bez pomiarów (co najmniej [Trend.GAP_DAYS] dni), od [x1] do [x2]; linie wykresu nie są przez niego prowadzone. */
data class TrendGap(val x1: Float, val x2: Float, val days: Int)

/**
 * Geometria wykresu ciśnienia w czasie ze stałą skalą (jednostek na dzień), tak by długi okres (nawet rok) był czytelny i przewijalny.
 * Do 45 dni rysujemy pojedyncze pomiary; dłuższe okresy to średnie dzienne z pionowym zakresem min–max.
 */
data class TrendGeo(
    val width: Float, val height: Float, val padLeft: Float, val plotTop: Float, val plotBottom: Float,
    val yTicks: List<Tick>, val refs: List<Float>, val marks: List<TrendMark>,
    val days: List<TrendDay>, val readings: List<ReadingPt>, val detailed: Boolean, val dayWidth: Float,
    /** Wartości progów odpowiadające [refs] (ten sam porządek), do podpisów na wykresie w raporcie. */
    val refValues: List<Int> = emptyList(),
    /** Ciągłe odcinki pomiarów (indeksy w [readings]); między odcinkami jest luka bez pomiarów, więc linia się urywa. */
    val readingRuns: List<IntRange> = listOf(readings.indices),
    /** Ciągłe odcinki dni (indeksy w [days]). */
    val dayRuns: List<IntRange> = listOf(days.indices),
    /** Luki bez pomiarów (także na początku i końcu okresu), do zaznaczenia na wykresie. */
    val gaps: List<TrendGap> = emptyList(),
)

object Trend {
    const val DETAIL_MAX_DAYS = 45
    /** Od tylu dni bez pomiarów linia wykresu się urywa, a przerwa jest zaznaczona jako „brak pomiarów” (krótsze przerwy, np. weekend, są łączone). */
    const val GAP_DAYS = 3
    /** Najmniejsza szerokość przerwy (w jednostkach wykresu), w której mieści się podpis „brak pomiarów”; wspólna dla wydruku i aplikacji. */
    const val GAP_LABEL_MIN = 70f
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

    /** [aggregate]: zawsze średnie dzienne z zakresem (także w krótkim fragmencie długiego okresu), żeby wykresy w raporcie wyglądały tak samo. [fit]: dzień ma taką szerokość, żeby okres zajął całą [minWidth] (raport do wydruku: wykres nie jest zmniejszany, więc podpisy zostają czytelne). */
    fun build(list: List<Bp>, from: LocalDate, to: LocalDate, minWidth: Float, hiSys: Int, hiDia: Int, height: Float = 200f, fit: Boolean = false, aggregate: Boolean = false): TrendGeo? {
        val inRange = list.filter { it.date >= from && it.date <= to }.sortedWith(bpOrder)
        if (inRange.isEmpty()) return null
        val nDays = daysBetween(from, to) + 1
        val detailed = !aggregate && nDays <= DETAIL_MAX_DAYS
        var dw = dayWidthFor(if (aggregate) max(nDays, DETAIL_MAX_DAYS + 1) else nDays)
        val fitW = (minWidth - PAD_L - PAD_R) / nDays
        if (fit || dw == 0f || dw * nDays < minWidth - PAD_L - PAD_R) dw = fitW
        val width = max(minWidth, nDays * dw + PAD_L + PAD_R)
        val lo = floor((inRange.minOf { it.dia } - 10) / 10.0) * 10
        val hi = ceil((inRange.maxOf { it.sys } + 10) / 10.0) * 10
        fun y(v: Double) = (TOP + (hi - v) / (hi - lo) * (height - TOP - BOTTOM)).toFloat()
        fun x(d: LocalDate, t: LocalTime?) = (PAD_L + (daysBetween(from, d) + (t?.minutes ?: 720) / 1440.0) * dw).toFloat()
        val step = if (hi - lo > 80) 20 else 10
        val ticks = ArrayList<Tick>()
        var v = ceil(lo / step) * step
        while (v <= hi && ticks.size < 40) { ticks.add(Tick(y(v), v.toInt().toString())); v += step }
        val refVals = listOf(hiSys, hiDia).filter { it.toDouble() in lo..hi }
        val refs = refVals.map { y(it.toDouble()) }

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
                // kreska co dzień tylko przy szerokich dniach (krótki okres); inaczej co tydzień, żeby siatka nie zagłuszała danych
                val line = i > 0 && (dw >= 40f || monday)
                val labelDay = when {
                    gap <= 1 -> true
                    gap <= 6 -> i % gap == 0
                    else -> monday && (i / 7) % ceil(gap / 7.0).toInt() == 0
                }
                if (line || labelDay) marks.add(TrendMark(xs, line, if (labelDay) shortDate(d) else null))
            }
        }
        val daily = bpDaily(inRange)
        val agg = daily.map { a ->
            TrendDay(x(a.date, null), y(a.sys), y(a.dia), y(a.sysMin.toDouble()), y(a.sysMax.toDouble()), y(a.diaMin.toDouble()), y(a.diaMax.toDouble()), a.n,
                a.sys >= hiSys || a.dia >= hiDia, a.sys >= hiSys, a.dia >= hiDia)
        }
        val readings = if (detailed) inRange.map {
            ReadingPt(x(it.date, it.time), y(it.sys.toDouble()), y(it.dia.toDouble()), it.sys >= hiSys || it.dia >= hiDia, it.sys >= hiSys, it.dia >= hiDia)
        } else emptyList()
        // luki: co najmniej GAP_DAYS pełnych dni bez pomiaru między pomiarami, przed pierwszym i po ostatnim
        val dates = daily.map { it.date }
        val gaps = ArrayList<TrendGap>()
        fun gap(a: Int, b: Int) { if (b - a >= GAP_DAYS) gaps.add(TrendGap(PAD_L + a * dw, PAD_L + b * dw, b - a)) }
        gap(0, daysBetween(from, dates.first()))
        dates.zipWithNext().forEach { (p, q) -> gap(daysBetween(from, p) + 1, daysBetween(from, q)) }
        gap(daysBetween(from, dates.last()) + 1, nDays)
        return TrendGeo(width, height, PAD_L, TOP, height - BOTTOM, ticks, refs, marks, agg, readings, detailed, dw, refVals,
            if (detailed) runs(inRange.map { it.date }) else emptyList(), runs(dates), gaps)
    }

    /** Dzieli kolejne daty na odcinki: nowy odcinek zaczyna się po przerwie co najmniej [GAP_DAYS] dni bez pomiaru. */
    fun runs(dates: List<LocalDate>): List<IntRange> {
        if (dates.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until dates.size) if (daysBetween(dates[i - 1], dates[i]) - 1 >= GAP_DAYS) { out.add(start until i); start = i }
        out.add(start until dates.size)
        return out
    }

    /** Podpisy osi pionowej: wartości progów zastępują pobliskie podziałki (bliżej niż [minGap] jednostek), żeby się nie nakładały. */
    fun axisLabels(g: TrendGeo, minGap: Float = 11f): List<Pair<Tick, Boolean>> {
        val refs = g.refs.zip(g.refValues).map { (y, v) -> Tick(y, v.toString()) to true }
        return g.yTicks.filter { t -> refs.none { kotlin.math.abs(it.first.pos - t.pos) < minGap } }.map { it to false } + refs
    }

    // ---------- SVG (raport do wydruku) ----------

    private fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)

    fun svg(g: TrendGeo): String {
        val sb = StringBuilder()
        // luki bez pomiarów: jasne tło z podpisem, żeby przerwa nie wyglądała jak równy przebieg
        g.gaps.forEach { gp ->
            sb.append("<rect class=\"gap\" x=\"${f1(gp.x1)}\" y=\"${f1(g.plotTop)}\" width=\"${f1(gp.x2 - gp.x1)}\" height=\"${f1(g.plotBottom - g.plotTop)}\"/>")
            if (gp.x2 - gp.x1 >= GAP_LABEL_MIN) sb.append("<text class=\"gaptx\" x=\"${f1((gp.x1 + gp.x2) / 2)}\" y=\"${f1(g.plotTop + 12)}\" text-anchor=\"middle\">brak pomiarów</text>")
        }
        // siatka tylko przy podpisanych podziałkach (podziałka zastąpiona wartością progu nie ma linii, bo próg ma własną)
        axisLabels(g).filter { !it.second }.map { it.first }.forEach {
            sb.append("<line class=\"gr\" x1=\"0\" x2=\"${f1(g.width)}\" y1=\"${f1(it.pos)}\" y2=\"${f1(it.pos)}\"/>")
        }
        g.marks.forEach { m ->
            if (m.line) sb.append("<line class=\"sepv\" x1=\"${f1(m.x)}\" x2=\"${f1(m.x)}\" y1=\"${f1(g.plotTop)}\" y2=\"${f1(g.plotBottom)}\"/>")
            m.label?.let { sb.append("<text class=\"ax\" x=\"${f1(m.x + 2)}\" y=\"${f1(g.height - 8)}\" text-anchor=\"start\">$it</text>") }
        }
        sb.append("<line class=\"base\" x1=\"0\" x2=\"${f1(g.width)}\" y1=\"${f1(g.plotBottom)}\" y2=\"${f1(g.plotBottom)}\"/>")
        fun pts(l: List<Pair<Float, Float>>) = l.joinToString(" ") { "${f1(it.first)},${f1(it.second)}" }
        // pole między skurczowym a rozkurczowym (ciśnienie tętna) jako jasne tło, osobno dla każdego ciągłego odcinka
        fun band(sys: List<Pair<Float, Float>>, dia: List<Pair<Float, Float>>) {
            if (sys.size > 1) sb.append("<polygon class=\"fill\" points=\"${pts(sys + dia.reversed())}\"/>")
        }
        fun lines(sys: List<Pair<Float, Float>>, dia: List<Pair<Float, Float>>) {
            if (sys.size > 1) sb.append("<polyline class=\"l-dia\" points=\"${pts(dia)}\"/><polyline class=\"l-sys\" points=\"${pts(sys)}\"/>")
        }
        // progi: przerywana linia; wartość progu jest wyróżniona na osi
        g.refs.forEach { y -> sb.append("<line class=\"ref\" x1=\"0\" x2=\"${f1(g.width)}\" y1=\"${f1(y)}\" y2=\"${f1(y)}\"/>") }
        if (g.detailed) {
            g.readingRuns.forEach { r -> val l = g.readings.slice(r); band(l.map { it.x to it.sys }, l.map { it.x to it.dia }) }
            g.readingRuns.forEach { r -> val l = g.readings.slice(r); lines(l.map { it.x to it.sys }, l.map { it.x to it.dia }) }
            g.readings.forEach {
                sb.append("<circle class=\"p-dia${if (it.diaHigh) " out" else ""}\" cx=\"${f1(it.x)}\" cy=\"${f1(it.dia)}\" r=\"3\"/>")
                sb.append("<circle class=\"p-sys${if (it.sysHigh) " out" else ""}\" cx=\"${f1(it.x)}\" cy=\"${f1(it.sys)}\" r=\"${if (it.sysHigh) "3.8" else "3.2"}\"/>")
            }
        } else {
            val bw = (g.dayWidth * 0.55f).coerceIn(1.2f, 6f)
            g.dayRuns.forEach { r -> val l = g.days.slice(r); band(l.map { it.x to it.sys }, l.map { it.x to it.dia }) }
            // zakres dnia tylko tam, gdzie było kilka różnych pomiarów (pojedynczy pomiar nie ma zakresu)
            g.days.filter { it.n > 1 }.forEach {
                if (it.sysMin - it.sysMax >= 0.5f) sb.append("<line class=\"rng-sys\" x1=\"${f1(it.x)}\" x2=\"${f1(it.x)}\" y1=\"${f1(it.sysMin)}\" y2=\"${f1(it.sysMax)}\" stroke-width=\"${f1(bw)}\"/>")
                if (it.diaMin - it.diaMax >= 0.5f) sb.append("<line class=\"rng-dia\" x1=\"${f1(it.x)}\" x2=\"${f1(it.x)}\" y1=\"${f1(it.diaMin)}\" y2=\"${f1(it.diaMax)}\" stroke-width=\"${f1(bw)}\"/>")
            }
            g.dayRuns.forEach { r -> val l = g.days.slice(r); lines(l.map { it.x to it.sys }, l.map { it.x to it.dia }) }
            // dni ze średnią na progu lub powyżej: czerwony punkt; pojedynczy dzień między lukami też dostaje punkt, żeby był widoczny
            val lone = g.dayRuns.filter { it.first == it.last }.map { it.first }.toSet()
            g.days.forEachIndexed { i, d ->
                if (d.diaHigh || i in lone) sb.append("<circle class=\"p-dia${if (d.diaHigh) " out" else ""}\" cx=\"${f1(d.x)}\" cy=\"${f1(d.dia)}\" r=\"2.4\"/>")
                if (d.sysHigh || i in lone) sb.append("<circle class=\"p-sys${if (d.sysHigh) " out" else ""}\" cx=\"${f1(d.x)}\" cy=\"${f1(d.sys)}\" r=\"2.8\"/>")
            }
        }
        // oś pionowa jako osobny pasek po lewej, żeby wykres miał stałą szerokość 100%; wartości progów wyróżnione
        val axis = axisLabels(g).joinToString("") { (t, ref) -> "<text class=\"${if (ref) "axref" else "ax"}\" x=\"30\" y=\"${f1(t.pos + 3.5f)}\" text-anchor=\"end\">${t.label}</text>" }
        val total = g.width + 34
        return "<svg class=\"chart\" viewBox=\"0 0 ${f1(total)} ${f1(g.height)}\" role=\"img\" aria-label=\"Wykres ciśnienia w czasie\">$axis<g transform=\"translate(34 0)\">$sb</g></svg>"
    }
}

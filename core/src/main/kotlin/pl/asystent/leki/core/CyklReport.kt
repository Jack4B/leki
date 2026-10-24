package pl.asystent.leki.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Część raportu dla lekarza o dzienniku cyklu: tabela cykli i trzy wykresy (długość cyklu na tle zakresu 24–38 dni, czas krwawienia,
 * różnica względem poprzedniego cyklu). Wyłącznie opis liczbowy z dat początku i końca okresu; wpisy intymne nie są czytane,
 * oceny i rozpoznania nie ma (zakresy odniesienia są tylko do oznaczenia wartości).
 */
object CyklReport {
    /** Jeden cykl: od początku okresu do początku następnego. */
    data class Cycle(val start: LocalDate, val next: LocalDate, val days: Int, val bleed: Int?)

    const val NORM_LO = 24
    const val NORM_HI = 38
    const val BLEED_MAX = 8
    const val JUMP = 7

    /** Cykle z dziennika (15–90 dni; krótsze i dłuższe odstępy to zwykle brak wpisu), od najstarszego. */
    fun cycles(db: String, today: LocalDate): List<Cycle> {
        val (starts, ends) = Cykl.periodDates(db) ?: return emptyList()
        val st = starts.filter { !it.isAfter(today) }
        return st.zipWithNext().mapNotNull { (a, b) ->
            val d = ChronoUnit.DAYS.between(a, b).toInt()
            if (d !in 15..90) return@mapNotNull null
            val end = ends.firstOrNull { !it.isBefore(a) && ChronoUnit.DAYS.between(a, it) <= 14 }
            Cycle(a, b, d, end?.let { ChronoUnit.DAYS.between(a, it).toInt() + 1 })
        }
    }

    private fun f(x: Double) = String.format(Locale.ROOT, "%.1f", x)
    private fun n(x: Double) = String.format(Locale.ROOT, "%.0f", x)
    private fun dm(d: LocalDate) = String.format(Locale.ROOT, "%02d.%02d", d.dayOfMonth, d.monthValue)

    private class Geo(val w: Int = 640, val h: Int = 210, val l: Int = 34, val r: Int = 10, val t: Int = 16, val b: Int = 38) {
        val pw get() = w - l - r
        val ph get() = h - t - b
    }

    private fun xLabels(g: Geo, rows: List<Cycle>, slot: Double, sb: StringBuilder) {
        var lastYear = -1
        rows.forEachIndexed { i, c ->
            val cx = g.l + slot * (i + 0.5)
            sb.append("<text class=\"ax\" x=\"${f(cx)}\" y=\"${g.h - 22}\" text-anchor=\"middle\">${dm(c.start)}</text>")
            if (c.start.year != lastYear) { sb.append("<text class=\"ax\" x=\"${f(cx)}\" y=\"${g.h - 10}\" text-anchor=\"middle\">${c.start.year}</text>"); lastYear = c.start.year }
        }
    }

    private fun yAxis(g: Geo, ymax: Double, step: Int, sb: StringBuilder, y: (Double) -> Double) {
        var v = 0
        while (v <= ymax) {
            sb.append("<line class=\"gr\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(v.toDouble()))}\" y2=\"${f(y(v.toDouble()))}\"/>")
            sb.append("<text class=\"ax\" x=\"${g.l - 5}\" y=\"${f(y(v.toDouble()) + 3.5)}\" text-anchor=\"end\">$v</text>")
            v += step
        }
    }

    /** Długość kolejnych cykli (słupki) na tle zakresu 24–38 dni, z medianą. Słupki poza zakresem są czerwone. */
    fun lengthChart(rows: List<Cycle>): String {
        val g = Geo()
        val ymax = maxOf(45.0, (rows.maxOf { it.days } + 6).toDouble()).let { Math.ceil(it / 10) * 10 }
        fun y(v: Double) = g.t + g.ph * (1 - v / ymax)
        val slot = g.pw.toDouble() / rows.size
        val bw = slot * 0.6
        val sb = StringBuilder("<svg class=\"chart\" viewBox=\"0 0 ${g.w} ${g.h}\" role=\"img\" aria-label=\"Długość cykli w dniach na tle zakresu $NORM_LO–$NORM_HI dni\">")
        sb.append("<rect class=\"band\" x=\"${g.l}\" y=\"${f(y(NORM_HI.toDouble()))}\" width=\"${g.pw}\" height=\"${f(y(NORM_LO.toDouble()) - y(NORM_HI.toDouble()))}\"/>")
        yAxis(g, ymax, 10, sb, ::y)
        rows.forEachIndexed { i, c ->
            val out = c.days < NORM_LO || c.days > NORM_HI
            val x = g.l + slot * i + (slot - bw) / 2
            sb.append("<rect class=\"bar${if (out) " out" else ""}\" x=\"${f(x)}\" y=\"${f(y(c.days.toDouble()))}\" width=\"${f(bw)}\" height=\"${f(y(0.0) - y(c.days.toDouble()))}\" rx=\"2\"/>")
            sb.append("<text class=\"vl\" x=\"${f(x + bw / 2)}\" y=\"${f(y(c.days.toDouble()) - 4)}\" text-anchor=\"middle\">${c.days}</text>")
        }
        val sorted = rows.map { it.days }.sorted()
        val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        sb.append("<line class=\"bl\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(med))}\" y2=\"${f(y(med))}\"/>")
        xLabels(g, rows, slot, sb)
        return sb.append("</svg>").toString()
    }

    /** Czas krwawienia w kolejnych cyklach (dni) z linią 8 dni. */
    fun bleedChart(rows: List<Cycle>): String {
        val g = Geo()
        val maxB = rows.mapNotNull { it.bleed }.maxOrNull() ?: BLEED_MAX
        val ymax = maxOf(10.0, Math.ceil((maxB + 2) / 2.0) * 2)
        fun y(v: Double) = g.t + g.ph * (1 - v / ymax)
        val slot = g.pw.toDouble() / rows.size
        val bw = slot * 0.6
        val sb = StringBuilder("<svg class=\"chart\" viewBox=\"0 0 ${g.w} ${g.h}\" role=\"img\" aria-label=\"Czas krwawienia w dniach, linia odniesienia $BLEED_MAX dni\">")
        yAxis(g, ymax, 2, sb, ::y)
        rows.forEachIndexed { i, c ->
            val b = c.bleed ?: return@forEachIndexed
            val x = g.l + slot * i + (slot - bw) / 2
            sb.append("<rect class=\"bar${if (b > BLEED_MAX) " out" else ""}\" x=\"${f(x)}\" y=\"${f(y(b.toDouble()))}\" width=\"${f(bw)}\" height=\"${f(y(0.0) - y(b.toDouble()))}\" rx=\"2\"/>")
            sb.append("<text class=\"vl\" x=\"${f(x + bw / 2)}\" y=\"${f(y(b.toDouble()) - 4)}\" text-anchor=\"middle\">$b</text>")
        }
        sb.append("<line class=\"ref\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(BLEED_MAX.toDouble()))}\" y2=\"${f(y(BLEED_MAX.toDouble()))}\"/>")
        xLabels(g, rows, slot, sb)
        return sb.append("</svg>").toString()
    }

    /** Różnica długości względem poprzedniego cyklu (dni); linie ±7 dni, słupki o większej różnicy są czerwone. */
    fun deltaChart(rows: List<Cycle>): String {
        val d = rows.zipWithNext { a, b -> b.days - a.days }
        val g = Geo()
        val m = maxOf(10.0, Math.ceil(((d.maxOfOrNull { Math.abs(it) } ?: 0) + 2) / 5.0) * 5)
        fun y(v: Double) = g.t + g.ph * (1 - (v + m) / (2 * m))
        val slot = g.pw.toDouble() / rows.size
        val bw = slot * 0.6
        val sb = StringBuilder("<svg class=\"chart\" viewBox=\"0 0 ${g.w} ${g.h}\" role=\"img\" aria-label=\"Różnica długości cyklu względem poprzedniego, linie odniesienia plus i minus $JUMP dni\">")
        var v = -m
        while (v <= m) {
            sb.append("<line class=\"gr\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(v))}\" y2=\"${f(y(v))}\"/>")
            sb.append("<text class=\"ax\" x=\"${g.l - 5}\" y=\"${f(y(v) + 3.5)}\" text-anchor=\"end\">${if (v > 0) "+" else ""}${n(v)}</text>")
            v += 5
        }
        sb.append("<line class=\"zero\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(0.0))}\" y2=\"${f(y(0.0))}\"/>")
        for (s in listOf(JUMP.toDouble(), -JUMP.toDouble())) sb.append("<line class=\"ref\" x1=\"${g.l}\" x2=\"${g.w - g.r}\" y1=\"${f(y(s))}\" y2=\"${f(y(s))}\"/>")
        d.forEachIndexed { i, x ->
            val cx = g.l + slot * (i + 1) + (slot - bw) / 2
            val top = y(maxOf(x.toDouble(), 0.0)); val bot = y(minOf(x.toDouble(), 0.0))
            sb.append("<rect class=\"bar${if (Math.abs(x) >= JUMP) " out" else ""}\" x=\"${f(cx)}\" y=\"${f(top)}\" width=\"${f(bw)}\" height=\"${f(maxOf(1.0, bot - top))}\" rx=\"2\"/>")
            val ly = if (x >= 0) top - 4 else bot + 11
            sb.append("<text class=\"vl\" x=\"${f(cx + bw / 2)}\" y=\"${f(ly)}\" text-anchor=\"middle\">${if (x > 0) "+" else ""}$x</text>")
        }
        xLabels(g, rows, slot, sb)
        return sb.append("</svg>").toString()
    }

    /** Fragment HTML raportu (bez nagłówka sekcji) albo null, gdy w dzienniku nie ma ani jednego policzalnego cyklu. */
    fun html(db: String, today: LocalDate): String? {
        val all = cycles(db, today)
        if (all.isEmpty()) return null
        val rows = all.takeLast(12)
        val h = StringBuilder()
        val days = rows.map { it.days }
        val sorted = days.sorted()
        val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val year = all.filter { it.start >= today.minusDays(365) }.map { it.days }
        val spread = if (year.size >= 2) year.max() - year.min() else null
        val outCount = rows.count { it.days < NORM_LO || it.days > NORM_HI }
        h.append("<div class=\"rp-sum\"><div><b>${rows.size}</b><span>${plN(rows.size, "cykl", "cykle", "cykli")} w zestawieniu (z ${all.size} policzonych)</span></div>")
        h.append("<div><b>${fmtNum(med, 1)} d</b><span>mediana długości, zakres ${sorted.first()}–${sorted.last()} d</span></div>")
        h.append("<div><b>${spread?.let { "$it d" } ?: "–"}</b><span>rozrzut w ostatnich 12 miesiącach (najdłuższy minus najkrótszy)</span></div></div>")
        h.append("<p class=\"muted\">Poza zakresem $NORM_LO–$NORM_HI dni: $outCount z ${rows.size}.</p>")
        if (rows.size >= 2) {
            h.append("<div class=\"rp-lab\">Długość cyklu (dni)</div><div class=\"legend\"><span><i class=\"sw bandsw\"></i>zakres $NORM_LO–$NORM_HI d</span><span><i class=\"sw barsw\"></i>w zakresie</span><span><i class=\"sw outsw\"></i>poza zakresem</span><span>linia przerywana: mediana ${fmtNum(med, 1)} d</span></div>")
            h.append(lengthChart(rows))
            if (rows.any { it.bleed != null }) { h.append("<div class=\"rp-lab\">Czas krwawienia (dni)</div><div class=\"legend\"><span>linia przerywana: $BLEED_MAX dni (górna granica zakresu), słupki powyżej są czerwone</span></div>"); h.append(bleedChart(rows)) }
            h.append("<div class=\"rp-lab\">Zmiana względem poprzedniego cyklu (dni)</div><div class=\"legend\"><span>linie przerywane: ±$JUMP dni, słupki o większej zmianie są czerwone</span></div>"); h.append(deltaChart(rows))
        }
        h.append("<div class=\"tbl\"><table><thead><tr><th>Początek okresu</th><th>Następny początek</th><th>Cykl (dni)</th><th>Krwawienie (dni)</th><th>Zmiana</th></tr></thead><tbody>")
        rows.forEachIndexed { i, c ->
            val out = c.days < NORM_LO || c.days > NORM_HI
            val delta = if (i > 0) c.days - rows[i - 1].days else null
            h.append("<tr><td class=\"num\">${plDate(c.start)}</td><td class=\"num\">${plDate(c.next)}</td>")
            h.append("<td class=\"num\">${c.days}${if (out) " <span class=\"bd bd-hi\">poza $NORM_LO–$NORM_HI</span>" else ""}</td>")
            h.append("<td class=\"num\">${c.bleed?.let { b -> "$b${if (b > BLEED_MAX) " <span class=\"bd bd-hi\">&gt; $BLEED_MAX</span>" else ""}" } ?: "brak"}</td>")
            h.append("<td class=\"num\">${delta?.let { x -> (if (x > 0) "+" else "") + x + if (Math.abs(x) >= JUMP) " <span class=\"bd bd-hi\">≥ $JUMP</span>" else "" } ?: ""}</td></tr>")
        }
        h.append("</tbody></table></div>")
        h.append("<p class=\"muted\">Zakresy odniesienia (oznaczenia orientacyjne, ocena należy do lekarza): częstość cyklu $NORM_LO–$NORM_HI dni oraz czas krwawienia do $BLEED_MAX dni (FIGO, klasyfikacja nieprawidłowych krwawień z macicy, System 1); regularność: rozrzut do 7 dni (26–41 lat) lub do 9 dni (18–25 i 42–45 lat); różnica co najmniej $JUMP dni między kolejnymi cyklami jest jednym z kryteriów STRAW+10 i jest tu tylko oznaczona. Cykle policzone z dat początku okresu (odstępy 15–90 dni); brak wpisu początku okresu zaburza długość cyklu.</p>")
        return h.toString()
    }
}

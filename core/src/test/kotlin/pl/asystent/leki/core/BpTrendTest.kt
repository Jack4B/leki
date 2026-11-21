package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpTrendTest {
    private val end = LocalDate.of(2026, 10, 21)

    /** Zmyślone pomiary: dwa dziennie przez [days] dni. */
    private fun readings(days: Int): List<Bp> = (0 until days).flatMap { i ->
        val d = end.minusDays((days - 1 - i).toLong())
        listOf(
            Bp("m$i", d, LocalTime.of(8, 0), 120 + (i * 7) % 30, 78 + (i * 3) % 14, 64, "rano"),
            Bp("e$i", d, LocalTime.of(20, 30), 128 + (i * 5) % 28, 82 + (i * 2) % 12, 70, "wieczór"),
        )
    }

    @Test fun shortRangeIsDetailedAndFitsWidth() {
        val g = Trend.build(readings(7), end.minusDays(6), end, 360f, 135, 85)!!
        assertTrue(g.detailed); assertEquals(14, g.readings.size); assertEquals(7, g.days.size)
        assertEquals(360f, g.width)
        assertTrue(g.marks.any { it.label != null })
    }

    @Test fun yearIsScrollableWithMonthLabelsAndDailyBands() {
        val list = readings(365)
        val g = Trend.build(list, end.minusDays(364), end, 360f, 135, 85)!!
        assertFalse(g.detailed); assertTrue(g.readings.isEmpty())
        assertEquals(365, g.days.size)
        assertEquals(7f, g.dayWidth)
        assertTrue(g.width > 1500f, "rok ma być szeroki, jest ${g.width}")
        val labels = g.marks.mapNotNull { it.label }
        assertTrue(labels.size in 12..14, labels.toString())
        assertTrue(labels.any { it.startsWith("sty") && it.endsWith("26") })
        // każdy punkt mieści się na wykresie
        assertTrue(g.days.all { it.x in 0f..g.width && it.sysMax <= it.sysMin && it.diaMax <= it.diaMin })
    }

    @Test fun mediumRangeUsesWeeklyLabels() {
        val g = Trend.build(readings(90), end.minusDays(89), end, 360f, 135, 85)!!
        assertFalse(g.detailed)
        assertEquals(12f, g.dayWidth)
        assertTrue(g.marks.count { it.line } >= 12)           // kreski co tydzień
        assertTrue(g.marks.count { it.label != null } in 15..30)
    }

    @Test fun emptyRangeGivesNull() {
        assertNull(Trend.build(readings(10), end.plusDays(5), end.plusDays(10), 360f, 135, 85))
    }

    @Test fun periodsAndDaily() {
        val list = readings(60)
        assertEquals(60, bpDaily(list).size)
        val weeks = bpPeriods(list, false, Settings())
        assertEquals(list.size, weeks.sumOf { it.n })
        assertTrue(weeks.all { it.from.dayOfWeek.value == 1 })
        val months = bpPeriods(list, true, Settings())
        assertTrue(months.all { it.from.dayOfMonth == 1 })
        assertEquals(list.count { Settings().bpFlag(it) == "podwyższone" }, months.sumOf { it.high })
    }

    @Test fun doctorReportForAYearUsesChunksAndSummaryInsteadOfEveryReading() {
        val s = Fixtures.state.copy(bp = readings(365))
        val per = Period(end.minusDays(364), end)
        val html = DoctorReport.html(s, per, end, Fixtures.now)
        assertTrue(html.contains("Miesiąc"), "powinno być zestawienie miesięczne")
        assertFalse(html.contains("<th>Godz.</th>"))             // brak tabeli każdego pomiaru
        assertEquals(4, Regex("<svg class=\"chart\"").findAll(html).count { true } - labsCharts(html), "rok: wykresy w segmentach po ok. 100 dni")
        assertTrue(html.contains("zestawienie miesięczne"))
        // krótki okres nadal ma pełną listę (zgodność z poprzednią wersją sprawdza ParityTest)
        val short = DoctorReport.html(s, Period(end.minusDays(6), end), end, Fixtures.now)
        assertTrue(short.contains("<th>Godz.</th>"))
    }

    private fun labsCharts(html: String): Int = Regex("aria-label=\"Wykres: ").findAll(html).count()

    @Test fun doctorReportIncludesAnalysisSectionsOnly() {
        val md = "# T\n\n## Pilne\nWażne **wnioski**.\n\n## Tabela\n| a | b |\n|---|---|\n| 1 | 2 |\n\n## Pytania\n1. Czy tak?"
        val a = Analysis("a1", end, "Analiza", md, Markdown.shortExclusions(md))
        val s = Fixtures.state.saveAnalysis(a)
        val html = DoctorReport.html(s, Fixtures.period(7), end, Fixtures.now)
        assertTrue(html.contains("analiza lekarsko-farmaceutyczna (AI)"))
        assertTrue(html.contains("Ważne <b>wnioski</b>")); assertTrue(html.contains("Czy tak?"))
        assertFalse(html.contains("<th>a</th>"))                 // sekcja "Tabela" wykluczona
        assertTrue(html.contains("To nie jest ocena lekarska"))
        // wyłączenie chipa usuwa sekcję
        val off = s.copy(settings = s.settings.copy(rp = s.settings.rp.copy(analysis = false)))
        assertFalse(DoctorReport.html(off, Fixtures.period(7), end, Fixtures.now).contains("analiza lekarsko-farmaceutyczna"))
        assertNotNull(s.reportAnalysis())
    }

    // ---------- wygląd wykresu: luki, oznaczenia, oś ----------

    @Test fun gapsBreakTheLineAndAreMarked() {
        // 30 dni, bez pomiarów w dniach 10–15 (6 dni) i przez ostatnie 4 dni; krótka przerwa 2 dni (dni 20–21) jest łączona
        val from = end.minusDays(29)
        val list = readings(30).filter { b -> val i = daysBetween(from, b.date); i !in 10..15 && i !in 20..21 && i < 26 }
        val g = Trend.build(list, from, end, 720f, 135, 85, fit = true)!!
        assertTrue(g.detailed)
        assertEquals(2, g.readingRuns.size, g.readingRuns.toString())
        assertEquals(g.readings.size, g.readingRuns.sumOf { it.count() })
        assertEquals(listOf(6, 4), g.gaps.map { it.days }, "przerwa w środku i na końcu okresu")
        assertEquals(g.dayWidth * 6, g.gaps[0].x2 - g.gaps[0].x1, 0.01f)
        assertTrue(g.readings.none { r -> g.gaps.any { r.x > it.x1 && r.x < it.x2 } }, "żaden pomiar nie leży w luce")
        assertEquals(listOf(0..9, 10..17), g.dayRuns, "krótka przerwa (2 dni) nie dzieli odcinka")
        val svg = Trend.svg(g)
        assertEquals(2, Regex("class=\"l-sys\"").findAll(svg).count(), "osobna linia dla każdego odcinka")
        assertEquals(2, Regex("class=\"fill\"").findAll(svg).count())
        assertEquals(g.gaps.count { it.x2 - it.x1 >= Trend.GAP_LABEL_MIN }, Regex("brak pomiarów").findAll(svg).count(), "podpis w każdej dość szerokiej luce")
        // bez luk: jeden odcinek, bez zaznaczeń
        val full = Trend.build(readings(30), from, end, 720f, 135, 85, fit = true)!!
        assertEquals(1, full.readingRuns.size); assertTrue(full.gaps.isEmpty())
        assertEquals(listOf(0..1, 2..2), Trend.runs(listOf(end, end.plusDays(3), end.plusDays(7))), "przerwa 2 dni łączona, 3 dni dzieli")
    }

    @Test fun systolicAndDiastolicAreMarkedSeparately() {
        val l = listOf(Bp("a", end, LocalTime.of(8, 0), 128, 92), Bp("b", end, LocalTime.of(20, 0), 142, 80))
        val g = Trend.build(l, end.minusDays(6), end, 360f, 135, 85)!!
        assertEquals(listOf(false to true, true to false), g.readings.map { it.sysHigh to it.diaHigh })
        assertTrue(g.readings.all { it.high })
        val svg = Trend.svg(g)
        assertTrue("p-dia out" in svg && "p-sys out" in svg, "czerwone rozkurczowe 92 i skurczowe 142")
        assertEquals(1, Regex("p-sys out").findAll(svg).count())
    }

    @Test fun thresholdValuesReplaceNearbyAxisTicks() {
        val g = Trend.build(readings(14), end.minusDays(13), end, 360f, 135, 85)!!
        val ax = Trend.axisLabels(g)
        assertEquals(listOf("135", "85"), ax.filter { it.second }.map { it.first.label })
        assertTrue(ax.none { (t, ref) -> !ref && ax.any { it.second && kotlin.math.abs(it.first.pos - t.pos) < 11f } }, "podziałka nie nachodzi na wartość progu")
        val svg = Trend.svg(g)
        assertTrue("class=\"axref\"" in svg && ">135<" in svg)
    }

    @Test fun gridIsWeeklyForAMonth() {
        val g = Trend.build(readings(30), end.minusDays(29), end, 720f, 135, 85, fit = true)!!
        val lines = g.marks.filter { it.line }
        assertTrue(lines.size in 4..5, "kreski co tydzień, nie codziennie: ${lines.size}")
        val week = Trend.build(readings(7), end.minusDays(6), end, 720f, 135, 85, fit = true)!!
        assertEquals(6, week.marks.count { it.line }, "krótki okres: kreska co dzień")
    }
}

package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Układ raportu do wydruku: tabele bez pojedynczych wierszy na stronie, nierozrywane bloki, wykresy z progami. */
class PrintLayoutTest {
    private val end = LocalDate.of(2030, 3, 31)
    private fun rows(n: Int) = List(n) { "<tr><td>$it</td></tr>" }
    private fun groups(html: String) = Regex("<tbody>(.*?)</tbody>").findAll(html).map { Regex("<tr>").findAll(it.groupValues[1]).count() }.toList()

    @Test fun shortTablesAreKeptWholeAndLongOnesSplitIntoBalancedGroups() {
        val short = DoctorReport.printTable(listOf("A"), rows(6))
        assertTrue("class=\"tbl keep\"" in short); assertEquals(listOf(3, 3), groups(short))
        for (n in listOf(9, 11, 23, 30, 41, 90)) {
            val g = groups(DoctorReport.printTable(listOf("A"), rows(n)))
            assertEquals(n, g.sum(), "żaden wiersz nie ginie ($n)")
            assertTrue(g.max() - g.min() <= 1, "grupy są wyrównane ($n): $g")
            assertTrue(g.all { it in 3..5 }, "ostatnia grupa nie jest pojedynczym wierszem ($n): $g")
            assertFalse("tbl keep" in DoctorReport.printTable(listOf("A"), rows(n)), "długa tabela może się dzielić ($n)")
        }
        assertEquals(1, Regex("<thead>").findAll(DoctorReport.printTable(listOf("A", "B"), rows(30))).count(), "nagłówek jest jeden i powtarza go druk")
        assertEquals(listOf<Int>(), groups(DoctorReport.printTable(listOf("A"), emptyList())).filter { it > 0 })
    }

    @Test fun cssKeepsHeadingsWithContentAndBlocksWhole() {
        val css = DoctorReport.CSS
        assertTrue("h3{" in css && "break-after:avoid" in css.substringAfter("h3{").substringBefore("}"), "nagłówek sekcji nie zostaje sam na końcu strony")
        assertTrue("thead{display:table-header-group}" in css)
        assertTrue("@media screen and (max-width:600px){.fig{overflow-x:auto}" in css, "na wąskim ekranie (podgląd) wykres ma czytelną szerokość i się przewija, a druk go nie dotyczy")
        assertTrue("tr,tbody{break-inside:avoid}" in css)
        for (cls in listOf(".fig{break-inside:avoid}", ".lab{margin:12px 0 6px;break-inside:avoid}", ".ecg-e{break-inside:avoid")) assertTrue(cls in css, cls)
    }

    private fun readings(days: Int, high: Boolean = true) = (0 until days).map { i ->
        val d = end.minusDays((days - 1 - i).toLong())
        Bp("b$i", d, java.time.LocalTime.of(8, 0), if (high && i == days - 1) 150 else 120, 80, 70)
    }

    @Test fun bpChartHasThresholdsAndMarksHighReadings() {
        val s = AppState(patient = "Jan Testowy", created = end.minusDays(40), bp = readings(20))
        val html = DoctorReport.html(s, Period(end.minusDays(19), end), end, Fixtures.now)
        assertTrue("próg 135" in html && "próg 85" in html, "progi z ustawień z podpisami")
        assertEquals(1, Regex("class=\"p-sys out\"").findAll(html).count(), "tylko pomiar na progu lub powyżej jest czerwony")
        assertTrue("<polygon class=\"fill\"" in html, "tło między skurczowym a rozkurczowym")
        assertTrue("na progu lub powyżej" in html)
        assertEquals(1, Regex("<svg class=\"chart\"").findAll(html).count())
    }

    @Test fun longPeriodsUseOneStyleOfChartAndSegmentsFitThePage() {
        val s = AppState(patient = "Jan Testowy", created = end.minusDays(400), bp = readings(130))
        val html = DoctorReport.html(s, Period(end.minusDays(129), end), end, Fixtures.now)
        val figs = Regex("<div class=\"fig\"><div class=\"rp-lab\">").findAll(html).count()
        assertEquals(2, figs, "130 dni: segmenty po ok. 100 dni (szerokość dnia 7)")
        assertFalse("class=\"p-dia\"" in html, "średnie dzienne, a nie pojedyncze pomiary, także w krótkim ostatnim segmencie")
        assertTrue("<th>Tydzień od</th>" in html)
    }

    @Test fun trendAggregateAndFitOptions() {
        val list = readings(30)
        val detailed = Trend.build(list, end.minusDays(29), end, 720f, 135, 85)!!
        assertTrue(detailed.detailed && detailed.width > 720f, "domyślnie 30 dni to pojedyncze pomiary i szerszy niż strona wykres")
        val fit = Trend.build(list, end.minusDays(29), end, 720f, 135, 85, fit = true)!!
        assertEquals(720f, fit.width); assertTrue(fit.detailed)
        val agg = Trend.build(list, end.minusDays(29), end, 720f, 135, 85, aggregate = true)!!
        assertFalse(agg.detailed); assertEquals(720f, agg.width)
        assertEquals(listOf(135, 85), agg.refValues)
    }

    @Test fun labBlockAndEcgEntryAreWrappedAndSingleRowsStayTogether() {
        val st = Fixtures.state
        val html = DoctorReport.html(st, Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertTrue("<div class=\"lab\"><div class=\"rp-lab\">" in html && "<div class=\"lab-body\">" in html)
        val ecg = Ecg("e1", Fixtures.today, java.time.LocalTime.of(8, 0), watchHr = 70, fs = 100, cmv = List(3000) { 0 })
        val withEcg = DoctorReport.html(st.copy(ecgs = listOf(ecg)), Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertEquals(1, Regex("<div class=\"ecg-e\">").findAll(withEcg).count())
    }
}

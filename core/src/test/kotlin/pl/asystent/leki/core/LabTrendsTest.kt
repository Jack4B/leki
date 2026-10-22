package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabTrendsTest {
    private val k = Marker("k", "Potas", "mmol/l", lo = 3.5, hi = 5.5)
    private fun lab(d: String, v: Double, m: String = "k", id: String = "$m$d") = Lab(id, LocalDate.parse(d), m, v)
    private fun series(vararg v: Double) = v.mapIndexed { i, x -> lab(LocalDate.of(2025, 1, 1).plusMonths(i.toLong()).toString(), x) }

    @Test fun trendNeedsThreeResultsAndDetectsDirection() {
        assertNull(labTrend(k, series(4.0, 4.5)))
        assertEquals("rośnie od 4 badań", labTrend(k, series(3.8, 4.0, 4.3, 4.6))!!.text)
        assertEquals(LabDir.Down, labTrend(k, series(5.0, 4.6, 4.2))!!.dir)
        assertEquals("maleje od 3 badań", labTrend(k, series(5.0, 4.6, 4.2))!!.text)
        // drobne wahania (poniżej 4% rozstępu zakresu = 0,08) nie są tendencją
        assertEquals("stabilnie", labTrend(k, series(4.50, 4.52, 4.50))!!.text)
        // zmiana kierunku: tendencja liczy się od ostatniego zakrętu
        assertEquals("bez wyraźnej tendencji", labTrend(k, series(4.0, 5.0, 4.0))!!.text)
        assertEquals("rośnie od 3 badań", labTrend(k, series(5.0, 4.0, 4.4, 4.8))!!.text)
    }

    @Test fun changeAndDeltaText() {
        val c = labChange(k, series(4.0, 4.5))!!
        assertEquals(0.5, c.delta, 1e-9)
        assertTrue(c.significant)                               // 0,5 ≥ 10% rozstępu (0,2)
        assertEquals("+0,5 (+13%)", labDeltaText(c.delta, c.pct))
        val small = labChange(k, series(4.0, 4.1))!!
        assertFalse(small.significant)
        assertEquals("−0,5 (−10%)", labDeltaText(-0.5, -0.1))
        assertEquals("0", labDeltaText(0.0, 0.0))
        assertNull(labChange(k, series(4.0)))
        // bez zakresu istotna jest zmiana o co najmniej 10%
        val nz = Marker("x", "Inny")
        assertTrue(labChange(nz, series(10.0, 12.0).map { it.copy(marker = "x") })!!.significant)
        assertFalse(labChange(nz, series(10.0, 10.5).map { it.copy(marker = "x") })!!.significant)
    }

    @Test fun yearAgoAndYears() {
        val l = listOf(lab("2024-03-10", 4.0), lab("2024-11-02", 4.2), lab("2025-03-20", 4.4), lab("2025-12-01", 5.8))
        assertEquals("2024-11-02", labYearAgo(l)!!.date.toString())                    // najbliższy sprzed roku od 2025-12-01 (okno ±75 dni)
        assertNull(labYearAgo(l.take(2)))                                              // jeden wynik: brak poprzedniego
        assertNull(labYearAgo(listOf(lab("2025-03-20", 4.4), lab("2025-12-01", 5.8)))) // nic w oknie
        val y = labYears(k, l)
        assertEquals(listOf(2025, 2024), y.map { it.year })
        assertEquals(2, y[0].n); assertEquals(1, y[0].out); assertEquals(5.1, y[0].avg, 1e-9)
    }

    @Test fun windowGhostAndPresets() {
        val l = listOf(lab("2023-01-01", 1.0), lab("2025-06-01", 2.0), lab("2026-02-01", 3.0))
        val today = LocalDate.of(2026, 3, 1)
        assertEquals(2, labWindow(l, LabWindow.ThreeYears, today).size)
        assertEquals(2, labWindow(l, LabWindow.Year, today).size)
        assertEquals(1, labWindow(l, LabWindow.Year, LocalDate.of(2026, 7, 1)).size)
        assertEquals(3, labWindow(l, LabWindow.All, today).size)
        // wynik z 2025-06-01 po przesunięciu o rok wypada po oknie, więc go nie ma; z okna 2025-06-01..2026-06-01 zostaje przesunięty 2024
        assertEquals(listOf("2026-06-01"), labGhost(l, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).map { it.date.toString() })
        val days = listOf("2026-02-01", "2025-06-01", "2025-02-10", "2023-01-01").map { LocalDate.parse(it) }
        assertEquals(listOf("2025-06-01", "2026-02-01"), labPresetDates(days, LabPreset.LastTwo).map { it.toString() })
        assertEquals(listOf("2025-02-10", "2026-02-01"), labPresetDates(days, LabPreset.YearAgo).map { it.toString() })
        assertEquals(4, labPresetDates(days, LabPreset.LastFour).size)
        assertTrue(labPresetDates(days.take(2), LabPreset.YearAgo).isEmpty())
        assertTrue(labPresetDates(days.take(1), LabPreset.LastTwo).isEmpty())
    }

    @Test fun perResultRangeOverridesMarkerRange() {
        val own = lab("2025-01-01", 5.0).copy(lo = 3.0, hi = 4.5)
        assertEquals("powyżej zakresu", labFlag(own, k))
        assertEquals("", labFlag(lab("2025-01-01", 5.0), k))
        assertEquals("3-4,5", rangeText(own, k)); assertEquals("3,5-5,5", rangeText(lab("2025-01-01", 5.0), k))
    }

    @Test fun pinnedFilterAndModel() {
        val ms = listOf(Marker("a", "Albumina", lo = 35.0, hi = 52.0, grp = "Wątroba"), Marker("b", "Białko", lo = 1.0, hi = 2.0, grp = "Wątroba", pin = true), Marker("c", "Cholesterol", lo = 0.0, hi = 5.0))
        val st = AppState(markers = ms, labs = listOf(lab("2025-01-01", 40.0, "a"), lab("2025-06-01", 52.5, "a"), lab("2025-06-01", 1.5, "b"), lab("2025-06-01", 6.0, "c")))
        val m = st.labsModel()
        assertEquals(listOf("Białko"), m.pinned.map { it.marker.name })
        assertEquals(listOf("Albumina", "Cholesterol"), m.out.map { it.marker.name }) // pinned nie powtarza się w grupach ani w „poza zakresem”
        assertTrue(m.groups.flatMap { it.items }.none { it.marker.id == "b" })
        assertEquals(3, m.withResults.size)
        assertEquals(listOf("Białko", "Albumina", "Cholesterol"), st.labsModel(LabSort.Alpha).flat.map { it.marker.name })     // przypięte na początku
        val f = st.labsModel(filter = LabFilter(out = true))
        assertEquals(2, f.out.size + f.groups.sumOf { it.items.size } + f.pinned.size)
        assertEquals(3, f.withResults.size)
        assertEquals(listOf("Białko"), st.labsModel(filter = LabFilter(pinned = true)).pinned.map { it.marker.name })
        // zmienione: Albumina 40 → 52,5 (zmiana ≥ 10% rozstępu 17)
        assertEquals(listOf("Albumina"), st.labsModel(filter = LabFilter(changed = true)).out.map { it.marker.name })
    }

    @Test fun backupKeepsPinAndOwnRange() {
        val st = AppState(markers = listOf(Marker("k", "Potas", "mmol/l", 3.5, 5.5, "Elektrolity", pin = true)), labs = listOf(lab("2025-01-01", 5.0).copy(lo = 3.0, hi = 4.5), lab("2025-02-01", 4.0)))
        val back = Backup.parseState(Backup.serialize(st))
        assertTrue(back.markers.first { it.id == "k" }.pin)
        val a = back.labs.first { it.date == LocalDate.parse("2025-01-01") }; val b = back.labs.first { it.date == LocalDate.parse("2025-02-01") }
        assertEquals(3.0, a.lo); assertEquals(4.5, a.hi); assertNull(b.lo); assertNull(b.hi)
        // starsza kopia bez nowych pól wczytuje się z wartościami domyślnymi
        val old = Backup.parseState(Fixtures.text("export.json"))
        assertTrue(old.markers.none { it.pin }); assertTrue(old.labs.all { it.lo == null && it.hi == null })
    }

    @Test fun compareRowsCarryChangeAndYearsGroup() {
        val st = AppState(markers = listOf(k), labs = listOf(lab("2024-05-01", 4.0), lab("2025-05-01", 4.5), lab("2026-05-01", 5.0)))
        val rows = st.labCompare(listOf(LocalDate.parse("2024-05-01"), LocalDate.parse("2026-05-01")))
        assertEquals(1.0, rows[0].delta!!, 1e-9); assertEquals(0.25, rows[0].pct!!, 1e-9); assertTrue(rows[0].significant)
        assertNull(st.labCompare(listOf(LocalDate.parse("2024-05-01")))[0].delta)
        val g = groupByPeriod(listOf(lab("2024-05-01", 1.0), lab("2025-05-01", 1.0), lab("2025-12-31", 1.0)), HistoryView.Years) { it.date }
        assertEquals(listOf("2025-01-01", "2024-01-01"), g.map { it.key }); assertEquals(listOf(2, 1), g.map { it.items.size })
        assertEquals("2025", periodLabel(g[0].start, g[0].end, HistoryView.Years))
    }
}

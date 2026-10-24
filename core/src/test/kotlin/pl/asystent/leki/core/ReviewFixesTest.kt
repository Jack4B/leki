package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** Testy błędów znalezionych w przeglądzie kodu (0.9.10). */
class ReviewFixesTest {
    @Test fun numbersRoundTheWayPeopleExpect() {
        assertEquals("1,01", fmtNum(1.005, 2))          // wcześniej 1 (dokładny rozwój binarny 1,00499…)
        assertEquals("0,29", fmtNum(0.285, 2))
        assertEquals("4,3", lnum(4.3)); assertEquals("0,5", num(0.5)); assertEquals("100", num(100.0)); assertEquals("0", num(-0.001))
        assertEquals("2,15", lnum(2.15)); assertEquals("134", lnum(134.0)); assertEquals("", fmtNum(Double.NaN))
        assertEquals("0,125", lnum(0.125)); assertEquals("1234567,89", num(1234567.891))
    }

    @Test fun sameDayLabsKeepInsertionOrderEverywhere() {
        val k = Marker("k", "Potas", "mmol/l", 3.5, 5.5)
        val d = LocalDate.of(2025, 5, 1)
        // id celowo w odwrotnej kolejności alfabetycznej niż kolejność dodania
        val st = AppState(markers = listOf(k), labs = listOf(Lab("z9", d, "k", 4.0), Lab("a1", d, "k", 5.0)))
        assertEquals(listOf(4.0, 5.0), st.labsOf("k").map { it.value })
        assertEquals(5.0, st.labsModel().withResults.first().last!!.value)
        assertEquals(5.0, st.labCompare(listOf(d))[0].labs[0]!!.value)           // ten sam „ostatni” co w przeglądzie
    }

    @Test fun backupReminderCountsAllKindsOfData() {
        val today = LocalDate.of(2026, 10, 24)
        val base = AppState(created = today.minusDays(30), lastBackup = null)
        assertNull(base.needBackup(today))
        assertNotNull(base.copy(labs = listOf(Lab("1", today, "potas", 4.0))).needBackup(today))
        assertNotNull(base.copy(analyses = listOf(Analysis("a", today, "t", "x"))).needBackup(today))
        assertNotNull(base.copy(meds = listOf(Med(id = "m", name = "A", phases = listOf(Phase(today, mapOf("morning" to 1.0), Defaults.allDow))))).needBackup(today))
    }

    @Test fun csvLabsUseOwnRangeAndFirstDateSeesLabs() {
        val k = Marker("k", "Potas", "mmol/l", 3.5, 5.5)
        val d = LocalDate.of(2020, 1, 1)
        val st = AppState(markers = listOf(k), created = LocalDate.of(2026, 1, 1), labs = listOf(Lab("1", d, "k", 5.0, "", 3.0, 4.5), Lab("2", d.plusDays(1), "k", 4.0)))
        val rows = Csv.labs(st).trim().split("\r\n")
        assertTrue(rows[1].contains(";3;4,5;"), rows[1])                 // zakres z badania
        assertTrue(rows[2].contains(";3,5;5,5;"), rows[2])               // zakres parametru
        assertEquals(d, st.firstDate(LocalDate.of(2026, 10, 24)))
    }

    @Test fun markdownCodeFencesLinksAndPlainTables() {
        val html = Markdown.toHtml("```\n# nie nagłówek\n| a | b |\n```\n\nZobacz [WHO](https://who.int/x) teraz.")
        assertTrue(html.contains("<pre><code># nie nagłówek"), html)
        assertFalse(html.contains("<h4>"), html)
        assertTrue(html.contains("WHO (https://who.int/x)"), html)
        val t = Markdown.toHtml("Lek | Dawka\n--- | ---\nA | 1\nB | 2\n\nkoniec")
        assertTrue(t.contains("<table>") && t.contains("<td>B</td>"), t)
        assertTrue(t.contains("<p>koniec</p>"), t)
        // nagłówek w bloku kodu nie dzieli tekstu na sekcje
        val secs = Markdown.sections("## A\ntekst\n```\n## B\n```\n## C\nx")
        assertEquals(listOf("A", "C"), secs.map { it.heading })
    }

    @Test fun notificationActionsMarkOnlyOpenDosesOfTheSlot() {
        val today = LocalDate.of(2026, 10, 24)
        val m1 = Med(id = "a", name = "A", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 1.0, "lunch" to 1.0), Defaults.allDow)))
        val m2 = Med(id = "b", name = "B", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 2.0), Defaults.allDow)))
        var st = AppState(meds = listOf(m1, m2), settings = Settings(remindOn = true))
        st = st.skipDose(today, Item.key("b", "morning"), "Zapomniałem", "")
        assertEquals(listOf("a"), st.openDoses(today, "morning").map { it.med.id })      // pominięta dawka nie jest otwarta
        val now = today.atTime(8, 20, 33)
        val after = st.markSlot(today, "morning", today, now)
        assertTrue(Item.key("a", "morning") in after.day(today).taken)
        assertEquals(LocalTime.of(8, 20), after.day(today).times[Item.key("a", "morning")])
        assertTrue(Item.key("a", "lunch") !in after.day(today).taken)                      // inna pora nietknięta
        assertTrue(Item.key("b", "morning") in after.day(today).skipped)                  // pominięcie zostaje
        assertTrue(after.openDoses(today, "morning").isEmpty())
        // przypomnienia o dawkach niosą dzień i porę (do przycisków „Wzięte” / „Odłóż”)
        val zone = java.time.ZoneId.of("Europe/Warsaw")
        val r = st.reminders(zone, today.atTime(6, 0).atZone(zone).toInstant().toEpochMilli(), today).filter { it.slotId != null }
        assertTrue(r.isNotEmpty() && r.all { it.date != null })
        assertTrue(r.any { it.date == today && it.slotId == "morning" })
    }
}

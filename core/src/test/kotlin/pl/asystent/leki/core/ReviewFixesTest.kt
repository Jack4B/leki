package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regresje z przeglądu kodu. Wszystkie dane są zmyślone. */
class ReviewFixesTest {
    private val today = LocalDate.of(2026, 10, 21)

    @Test fun ldlIsNotMatchedToHdl() {
        val s = AppState(markers = listOf(Marker("hdl", "Cholesterol HDL", "mg/dl", 40.0, null)))
        val row = LabScan.parseRow("Cholesterol LDL 100 mg/dl 0-115")!!
        val c = LabScan.candidates(listOf(row), s).single()
        assertNotEquals("hdl", c.markerId)
    }

    @Test fun qualifiedNamesAreNotKnownTests() {
        assertNull(LabScan.knownId("Cholesterol nie-HDL"))
        assertNull(LabScan.knownId("Wapń zjonizowany"))
        assertNull(LabScan.knownId("Kreatynina w moczu"))
        assertEquals(LabScan.knownId("Potas"), "potas")
    }

    @Test fun qualifierWithSpaceIsKept() {
        val r = LabScan.parseRow("CRP < 0,5 mg/l 0-5")!!
        assertEquals("<", r.qualifier)
        assertEquals(0.5, r.value)
    }

    @Test fun eventResultFollowsTheEventDate() {
        val d1 = LocalDate.of(2026, 10, 5); val d2 = LocalDate.of(2026, 10, 7)
        val s0 = AppState(markers = listOf(Marker("potas", "Potas", "mmol/l")))
            .saveEvent(Event("e1", d1, "Kontrola", marker = "potas"))
            .setEventResult("e1", "4,5")
        assertEquals(1, s0.labs.size)
        val s1 = s0.saveEvent(Event("e1", d2, "Kontrola", marker = "potas"))
        assertEquals("4,5", s1.day(d2).results["e1"])
        assertTrue(s1.day(d1).results.isEmpty())
        assertEquals(listOf(d2), s1.labs.map { it.date })
        assertEquals(4.5, s1.labs.single().value)
    }

    @Test fun dayListKeepsTheNewestDays() {
        val to = LocalDate.of(2026, 10, 21)
        val l = dayList(to.minusDays(1389), to)
        assertEquals(1390, l.size); assertEquals(to, l.last())
        val huge = dayList(LocalDate.of(1900, 1, 1), to)
        assertEquals(4000, huge.size); assertEquals(to, huge.last())
    }

    @Test fun doseOffsetWrapsAroundMidnight() {
        assertEquals(45, doseOffset(15, 23 * 60 + 30))      // 00:15 przy porze 23:30
        assertEquals(-45, doseOffset(23 * 60 + 30, 15))
        assertEquals(25, doseOffset(8 * 60 + 25, 8 * 60))
        assertEquals(-10, doseOffset(7 * 60 + 50, 8 * 60))
    }

    @Test fun absurdPressureValuesAreDropped() {
        val json = """{"v":3,"days":{},"bp":[
            {"id":"a","date":"2026-10-20","time":"08:00","sys":2000000000,"dia":80},
            {"id":"b","date":"2026-10-20","time":"09:00","sys":120,"dia":-5},
            {"id":"c","date":"2026-10-20","time":"10:00","sys":125,"dia":82,"pulse":9000}]}"""
        val st = Backup.parseState(json)
        assertEquals(listOf("c"), st.bp.map { it.id })
        assertNull(st.bp.single().pulse)
        // wykres dla poprawnych danych ma ograniczoną liczbę podziałek
        assertTrue(bpChart(st.bp)!!.yTicks.size < 40)
    }

    @Test fun deeplyNestedJsonIsAFormatError() {
        assertFailsWith<Backup.FormatException> { Backup.parseEnvelope("[".repeat(200_000)) }
    }

    @Test fun cryptoRejectsWrappedIterationsAndBadBase64() {
        val env = Backup.parseEnvelope(BackupCrypto.encrypt("haslo-12345", "{}", iter = 100_000))
        fun with(k: String, v: String): kotlinx.serialization.json.JsonElement {
            val o = (env as kotlinx.serialization.json.JsonObject).toMutableMap()
            o[k] = kotlinx.serialization.json.JsonPrimitive(v)
            return kotlinx.serialization.json.JsonObject(o)
        }
        assertFailsWith<Backup.FormatException> { BackupCrypto.decrypt("haslo-12345", with("iter", "4295067296")) }
        assertFailsWith<Backup.FormatException> { BackupCrypto.decrypt("haslo-12345", with("iter", "5000000")) }
        assertFailsWith<Backup.FormatException> { BackupCrypto.decrypt("haslo-12345", with("salt", "!!!not base64!!!")) }
        assertEquals("{}", BackupCrypto.decrypt("haslo-12345", env))
    }

    @Test fun parseNumAcceptsOnlyPlainNumbers() {
        assertEquals(4.5, parseNum("4,5")); assertEquals(0.5, parseNum(".5")); assertEquals(-3.0, parseNum("-3")); assertEquals(5.0, parseNum("5."))
        assertNull(parseNum("5f")); assertNull(parseNum("5d")); assertNull(parseNum("0x1p3")); assertNull(parseNum("4,5 mmol/l")); assertNull(parseNum("NaN"))
    }

    @Test fun labsMergeKeepsRangesWhenFileHasNone() {
        val s = AppState(markers = listOf(Marker("potas", "Potas", "mmol/l", 3.5, 5.5)))
        val f = Backup.LabsFile(listOf(Marker("potas", "Potas", "mmol/l", null, null)), listOf(Lab("l1", today, "potas", 4.0)))
        val m = s.applyLabsMerge(f).marker("potas")!!
        assertEquals(3.5, m.lo); assertEquals(5.5, m.hi)
    }

    @Test fun markdownMoneyAndHashes() {
        assertEquals("Koszt \$5 i \$10 miesięcznie", Markdown.cleanLatex("Koszt \$5 i \$10 miesięcznie"))
        assertEquals("2,8 mmol/l", Markdown.cleanLatex("\$2{,}8\\text{ mmol/l}\$"))
        assertEquals("Gen C#", Markdown.sections("# T\n\n## Gen C#\ntekst").single { it.heading.startsWith("Gen") }.heading)
        assertEquals("Wynik", Markdown.sections("# T\n\n## Wynik ##\ntekst").single { it.heading.startsWith("Wynik") }.heading)
        // bardzo długa linia nie zamraża (czas liniowy) i jest bezpiecznie escapowana
        val long = "*a ".repeat(30_000)
        val t0 = System.nanoTime()
        val h = Markdown.toHtml(long)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
        assertTrue(h.contains("*a"))
    }
}

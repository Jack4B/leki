package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Raporty muszą być znak w znak takie same jak w poprzedniej wersji aplikacji. */
class ParityTest {
    private fun assertSame(expected: String, actual: String) {
        if (expected == actual) return
        val i = expected.indices.firstOrNull { it >= actual.length || expected[it] != actual[it] } ?: expected.length
        val from = maxOf(0, i - 80)
        assertEquals(expected.substring(from, minOf(expected.length, i + 120)), actual.substring(minOf(from, actual.length), minOf(actual.length, i + 120)), "pierwsza różnica na pozycji $i")
        assertEquals(expected.length, actual.length)
    }

    @Test fun aiReport7() = ai("7", 7)
    @Test fun aiReport30() = ai("30", 30)
    @Test fun aiReportAll() = ai("all", 0)

    private fun ai(name: String, preset: Int) {
        val txt = AiReport.build(Fixtures.state, Fixtures.period(preset), Fixtures.today, Fixtures.now, "0.2.0-preview")
        // jedyna znana różnica: poprawna odmiana "1 pomiar" zamiast "1 pomiarów" w starej wersji
        assertSame(Fixtures.text("ai-$name.md").replace("(1 pomiarów)", "(1 pomiar)"), txt)
    }

    @Test fun doctorReport7() = doctor("7", 7)
    @Test fun doctorReport30() = doctor("30", 30)
    @Test fun doctorReportAll() = doctor("all", 0)

    private fun doctor(name: String, preset: Int) {
        val html = DoctorReport.html(Fixtures.state, Fixtures.period(preset), Fixtures.today, Fixtures.now)
        assertSame(Fixtures.text("report-$name.html"), html)
    }
}

package pl.asystent.leki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Raporty są porównywane znak w znak ze wzorcami w `fixtures` (pierwotnie z poprzedniej wersji aplikacji). Zmiany tekstu i układu są świadome: wzorzec raportu dla lekarza
 * odtwarza `WRITE_FIXTURES=1 ./gradlew :core:test --tests '*ParityTest*'` (po obejrzeniu zmiany, np. wydruku przez Chromium).
 */
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
        if (System.getenv("WRITE_FIXTURES") == "1") {
            var d: File? = File(".").canonicalFile
            while (d != null && !File(d, "src/test/resources/fixtures").isDirectory && !File(d, "core/src/test/resources/fixtures").isDirectory) d = d.parentFile
            val dir = listOf("src/test/resources/fixtures", "core/src/test/resources/fixtures").map { File(d ?: error("brak katalogu fixtures"), it) }.first { it.isDirectory }
            File(dir, "report-$name.html").writeText(html, Charsets.UTF_8)
        }
        assertSame(Fixtures.text("report-$name.html"), html)
    }
}

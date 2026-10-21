package pl.asystent.leki.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Linie z prawdziwego rozpoznawania tekstu (silnik OCR) na zmyślonych tabelach wyników wyrenderowanych jako obraz.
 * Zawierają typowe pomyłki OCR (np. "ulU/ml", "Ip/6", "25-0H"), więc sprawdzają odporność składania wierszy i odczytu.
 */
class LabScanOcrFixturesTest {
    private val today = LocalDate.of(2026, 10, 21)

    private fun lines(name: String): List<OcrLine> {
        val txt = javaClass.getResourceAsStream("/ocr/$name.json")!!.readBytes().toString(Charsets.UTF_8)
        return (Json.parseToJsonElement(txt) as JsonArray).map { e ->
            val o = e as JsonObject
            OcrLine(o["text"]!!.jsonPrimitive.content, o["left"]!!.jsonPrimitive.int, o["top"]!!.jsonPrimitive.int, o["right"]!!.jsonPrimitive.int, o["bottom"]!!.jsonPrimitive.int)
        }
    }

    @Test fun tableLayout() {
        val rows = LabScan.rows(lines("tabela"))
        val parsed = LabScan.parseRows(rows)
        val byName = parsed.associateBy { it.name }
        assertEquals(LocalDate.of(2026, 10, 20), LabScan.parseDate(rows, today))
        assertEquals(9, parsed.size, parsed.joinToString("\n") { it.raw })
        assertEquals(4.2, byName.getValue("Potas").value)
        assertEquals(133.0, byName.getValue("Sod").value)
        assertEquals(0.92, byName.getValue("Kreatynina").value)
        assertEquals(215.0, byName.getValue("Cholesterol calkowity").value)
        assertEquals(190.0, byName.getValue("Cholesterol calkowity").hi)
        assertEquals("<", byName.getValue("CRP").qualifier)
        assertEquals(14.8, byName.getValue("HGB Hemoglobina").value)
        assertEquals(13.5, byName.getValue("HGB Hemoglobina").lo)
        assertEquals(2.15, byName.getValue("TSH").value)
        assertEquals("miu/l", LabScan.normUnit(byName.getValue("TSH").unit))
        val c = LabScan.candidates(parsed, AppState())
        assertEquals(setOf("potas", "sod", "kreatynina", "glukoza", "hemoglobina", "leukocyty", "chol", "tsh", "crp"), c.map { it.markerId }.toSet())
    }

    @Test fun listLayout() {
        val rows = LabScan.rows(lines("lista"))
        val parsed = LabScan.parseRows(rows)
        assertEquals(LocalDate.of(2026, 10, 20), LabScan.parseDate(rows, today))
        assertEquals(6, parsed.size, rows.joinToString("\n"))
        val c = LabScan.candidates(parsed, AppState()).associateBy { it.markerId }
        assertEquals(4.2, c.getValue("potas").value)
        assertEquals(0.85, c.getValue("magnez").value)
        assertEquals(6.1, c.getValue("moczowy").value)
        assertEquals(88.0, c.getValue("ferrytyna").value)
        assertNotNull(c["witd"])
    }

    @Test fun stackedLayoutWithMisreadName() {
        val rows = LabScan.rows(lines("kolumna"))
        val parsed = LabScan.parseRows(rows)
        assertEquals(LocalDate.of(2026, 10, 20), LabScan.parseDate(rows, today))
        assertEquals(5, parsed.size, rows.joinToString("\n"))
        val c = LabScan.candidates(parsed, AppState()).associateBy { it.markerId }
        assertEquals(4.2, c.getValue("potas").value)
        assertEquals(0.92, c.getValue("kreatynina").value)
        assertEquals(2.15, c.getValue("tsh").value)
        assertEquals(14.8, c.getValue("hemoglobina").value)
        // nazwa "Sód" została przez OCR przekręcona na "pos": parametr zgadnięty po jednostce i zakresie, z ostrzeżeniem
        val sod = c.getValue("sod")
        assertEquals(133.0, sod.value)
        assertTrue(sod.guessed)
        assertTrue(LabScan.warnings(sod, AppState(), today).any { it.kind == WarningKind.Guess })
    }
}

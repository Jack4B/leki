package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wszystkie próbki tekstu są zmyślone; odwzorowują typowe układy wyników z polskich laboratoriów. */
class LabScanTest {
    private val today = LocalDate.of(2026, 10, 21)

    private fun row(s: String) = LabScan.parseRow(s)

    @Test fun tableRowWithColumns() {
        val r = row("Potas    4,2    mmol/l    3,5 - 5,1")!!
        assertEquals("Potas", r.name); assertEquals(4.2, r.value); assertEquals("mmol/l", r.unit); assertEquals(3.5, r.lo); assertEquals(5.1, r.hi)
    }

    @Test fun dotDecimalsAndColon() {
        val r = row("Potas: 4.2 mmol/l (3.5 - 5.1)")!!
        assertEquals("Potas", r.name); assertEquals(4.2, r.value); assertEquals(3.5, r.lo); assertEquals(5.1, r.hi)
    }

    @Test fun abbreviationsAndFlags() {
        val r = row("HGB Hemoglobina  14,8  g/dl  13,5 - 17,5")!!
        assertEquals("HGB Hemoglobina", r.name); assertEquals("g/dl", r.unit)
        val h = row("Cholesterol całkowity 215 H mg/dl <190")!!
        assertEquals(215.0, h.value); assertEquals("mg/dl", h.unit); assertEquals(190.0, h.hi); assertNull(h.lo)
        val wbc = row("WBC Leukocyty  6,43  tys./µl  4,00 – 10,00")!!
        assertEquals("tys./µl", wbc.unit); assertEquals(4.0, wbc.lo); assertEquals(10.0, wbc.hi)
    }

    @Test fun qualifierValues() {
        val r = row("CRP  <0,5  mg/l  do 5,0")!!
        assertEquals("<", r.qualifier); assertEquals(0.5, r.value); assertEquals(5.0, r.hi)
    }

    @Test fun rejectsNoise() {
        assertNull(row("Data pobrania: 20.10.2026 07:45"))
        assertNull(row("Strona 1 z 2"))
        assertNull(row("Pacjent: Jan Testowy PESEL 90010112345"))
        assertNull(row("ul. Przykładowa 12"))
        assertNull(row("Tel. 22 123 45 67"))
        assertNull(row("Badanie   Wynik   Jednostka   Zakres referencyjny"))
        assertNull(row("Potas 4,2"))  // bez jednostki i zakresu
    }

    @Test fun nameWithDigits() {
        val r = row("Witamina B12  412  pg/ml  197 - 771")!!
        assertEquals("Witamina B12", r.name); assertEquals(412.0, r.value)
        assertEquals("witb12", LabScan.knownId(r.name))
    }

    @Test fun knownIdMatching() {
        assertEquals("potas", LabScan.knownId("Potas"))
        assertEquals("potas", LabScan.knownId("K"))
        assertEquals("sod", LabScan.knownId("Sód (Na)"))
        assertEquals("hemoglobina", LabScan.knownId("HGB Hemoglobina"))
        assertEquals("hdl", LabScan.knownId("Cholesterol HDL"))
        assertEquals("ldl", LabScan.knownId("Cholesterol LDL"))
        assertEquals("chol", LabScan.knownId("Cholesterol całkowity"))
        assertEquals("tg", LabScan.knownId("Trójglicerydy"))
        assertEquals("tsh", LabScan.knownId("TSH"))
        assertEquals("crp", LabScan.knownId("CRP białko C-reaktywne"))
        assertEquals("moczowy", LabScan.knownId("Kwas moczowy"))
        assertNull(LabScan.knownId("Coś zupełnie innego"))
        assertNull(LabScan.knownId("Witamina K"))  // krótki alias "k" nie może łapać dłuższych nazw
    }

    @Test fun datePrefersCollectionDateAndSkipsBirthDate() {
        val rows = listOf("Pacjent: Jan Testowy, data urodzenia: 01.01.1960", "Data rejestracji: 19.10.2026", "Data pobrania: 20.10.2026 07:45", "Data wydruku 21.10.2026")
        assertEquals(LocalDate.of(2026, 10, 20), LabScan.parseDate(rows, today))
        assertEquals(LocalDate.of(2026, 10, 20), LabScan.parseDate(listOf("Pobranie materiału 2026-10-20"), today))
        assertNull(LabScan.parseDate(listOf("brak daty tutaj"), today))
        assertNull(LabScan.parseDate(listOf("Data pobrania: 20.10.2030"), today)) // przyszłość
    }

    @Test fun rowsFromOcrLinesGroupsColumns() {
        fun l(t: String, x: Int, y: Int) = OcrLine(t, x, y, x + t.length * 12, y + 24)
        val lines = listOf(
            l("4,2", 400, 102), l("Potas", 20, 100), l("mmol/l", 520, 101), l("3,5 - 5,1", 660, 103),
            l("Sód", 20, 150), l("138", 400, 151), l("mmol/l", 520, 150), l("136 - 145", 660, 152),
            l("Data pobrania: 20.10.2026", 20, 20),
        )
        val rows = LabScan.rows(lines)
        assertEquals(listOf("Data pobrania: 20.10.2026", "Potas  4,2  mmol/l  3,5 - 5,1", "Sód  138  mmol/l  136 - 145"), rows)
        val parsed = LabScan.parseRows(rows)
        assertEquals(listOf("Potas", "Sód"), parsed.map { it.name })
    }

    private fun scanText() = listOf(
        "Badanie  Wynik  Jednostka  Zakres referencyjny",
        "Potas  4,2  mmol/l  3,5 - 5,1",
        "Sód  138  mmol/l  136 - 145",
        "Kreatynina  0,92  mg/dl  0,67 - 1,17",
        "Glukoza  5,2  mmol/l  3,9 - 5,5",
        "TSH  2,15  µIU/ml  0,27 - 4,20",
        "Potas  4,3  mmol/l  3,5 - 5,1",
    )

    @Test fun candidatesMapToExistingAndNewMarkers() {
        val s = AppState()
        val c = LabScan.candidates(LabScan.parseRows(scanText()), s)
        assertEquals(6, c.size)
        val potas = c[0]
        assertEquals("potas", potas.markerId); assertNull(potas.proposed); assertTrue(potas.include)
        val glukoza = c.first { it.rawName == "Glukoza" }
        assertEquals("glukoza", glukoza.markerId); assertNotNull(glukoza.proposed)
        assertEquals("Glukoza", glukoza.proposed!!.name); assertEquals("Nerki i metabolizm", glukoza.proposed!!.grp); assertEquals(3.9, glukoza.proposed!!.lo)
        assertFalse(c.last().include) // drugi "Potas" w tym samym skanie
    }

    @Test fun warningsUnitDuplicateJump() {
        val date = LocalDate.of(2026, 10, 20)
        val s = AppState(labs = listOf(Lab("a", date, "potas", 4.0), Lab("b", date.minusDays(30), "kreatynina", 0.9)))
        val rows = LabScan.parseRows(listOf("Potas  4,2  mmol/l  3,5 - 5,1", "Kreatynina  92  umol/l  60 - 104", "Sód  138  mmol/l  136 - 145", "Magnez  9  mmol/l  0,7 - 1,1"))
        val c = LabScan.candidates(rows, s)
        val kinds = c.associate { it.markerId to LabScan.warnings(it, s, date).map { w -> w.kind } }
        assertTrue(WarningKind.Duplicate in kinds["potas"]!!)
        assertTrue(WarningKind.Unit in kinds["kreatynina"]!!)
        assertTrue(kinds["sod"]!!.isEmpty())
        assertFalse(LabScan.defaultInclude(c.first { it.markerId == "potas" }, s, date))
        assertTrue(LabScan.defaultInclude(c.first { it.markerId == "sod" }, s, date))
    }

    @Test fun jumpWarning() {
        val date = LocalDate.of(2026, 10, 20)
        val s = AppState(labs = listOf(Lab("a", date.minusDays(10), "potas", 4.1)))
        val c = LabScan.candidates(LabScan.parseRows(listOf("Potas  42  mmol/l  3,5 - 5,1")), s).single()
        assertTrue(LabScan.warnings(c, s, date).any { it.kind == WarningKind.Jump })
    }

    @Test fun unitEquivalence() {
        assertEquals(LabScan.normUnit("µIU/ml"), LabScan.normUnit("mIU/l"))
        assertEquals(LabScan.normUnit("tys./µl"), LabScan.normUnit("10^3/µl"))
        assertTrue(LabScan.normUnit("mg/dl") != LabScan.normUnit("mmol/l"))
    }

    @Test fun applyCreatesMarkersAndReplacesSameDay() {
        val date = LocalDate.of(2026, 10, 20)
        val s = AppState(labs = listOf(Lab("a", date, "potas", 4.0)))
        val c = LabScan.candidates(LabScan.parseRows(scanText().take(5)), s).filter { it.rawName != "Kreatynina" }
        val out = LabScan.apply(s, date, c)
        assertEquals(4.2, out.labs.single { it.marker == "potas" && it.date == date }.value)  // zastąpiony
        assertEquals(1, out.labs.count { it.marker == "potas" })
        assertNotNull(out.marker("glukoza")); assertEquals(138.0, out.labs.single { it.marker == "sod" }.value)
        assertTrue(out.labs.all { it.note == "ze skanu" || it.id == "a" })
    }

    @Test fun userRenamedMarkerIsMatchedByName() {
        val s = AppState(markers = listOf(Marker("x1", "Potas (K)", "mmol/l", 3.5, 5.1)))
        val c = LabScan.candidates(LabScan.parseRows(listOf("Potas (K)  4,2  mmol/l  3,5 - 5,1")), s).single()
        assertEquals("x1", c.markerId); assertNull(c.proposed)
    }
}

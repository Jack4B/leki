package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Teksty opakowań są zmyślone (nazwy fikcyjne). */
class MedScanTest {
    private fun line(t: String, top: Int, h: Int) = OcrLine(t, 20, top, 20 + t.length * 12, top + h)

    @Test fun readsNameStrengthSubstanceAndCountFromABoxFront() {
        val d = assertNotNull(MedScan.parse(listOf(
            line("PROBAMED", 40, 90),
            line("Tabletki powlekane", 140, 30),
            line("10 mg", 180, 44),
            line("Substancja czynna: Amlodipinum", 240, 22),
            line("30 tabletek powlekanych", 280, 24),
            line("Podmiot odpowiedzialny: Firma Testowa", 320, 18),
            line("Rp", 20, 20),
        )))
        assertEquals("Probamed", d.name)
        assertEquals("10 mg", d.strength)
        assertEquals("Probamed 10 mg", d.fullName())
        assertEquals("Amlodipinum", d.substance)
        assertEquals("tabl.", d.unit)
        assertEquals(30.0, d.count)
        // żaden z napisów pomocniczych nie wchodzi na listę nazw
        assertTrue(d.names.none { it.contains("tablet", true) || it.contains("podmiot", true) || it.contains("substanc", true) }, d.names.toString())
    }

    @Test fun capsulesLiquidsAndPatches() {
        val caps = assertNotNull(MedScan.parse(listOf(line("Zdrowex Forte", 30, 70), line("500 mg", 110, 40), line("60 kapsułek", 160, 24))))
        assertEquals("kaps.", caps.unit); assertEquals(60.0, caps.count); assertEquals("500 mg", caps.strength)
        val syrup = assertNotNull(MedScan.parse(listOf(line("KASZLOSTOP", 30, 80), line("Syrop", 120, 30), line("120 ml", 160, 26), line("5 mg/ml", 200, 30))))
        assertEquals("ml", syrup.unit); assertEquals(120.0, syrup.count); assertEquals("5 mg/ml", syrup.strength); assertEquals("Kaszlostop", syrup.name)
        val patch = assertNotNull(MedScan.parse(listOf(line("Plastrex", 30, 80), line("7 plastrów", 120, 26))))
        assertEquals("plaster", patch.unit); assertEquals(7.0, patch.count)
    }

    @Test fun biggestTextIsTheNameAndOthersAreAlternatives() {
        val d = assertNotNull(MedScan.parse(listOf(line("Alfa-Lek", 30, 100), line("Beta Med", 150, 50), line("Gamma", 220, 30))))
        assertEquals(listOf("Alfa-Lek", "Beta Med", "Gamma"), d.names)
    }

    @Test fun substanceOnNextLineAndLeafletWording() {
        val d = assertNotNull(MedScan.parse(listOf(line("Nowolek", 30, 80), line("Substancją czynną leku jest", 120, 22), line("telmisartan 40 mg, hydrochlorotiazyd", 150, 22))))
        assertEquals("telmisartan", d.substance)
        val inline = assertNotNull(MedScan.parse(listOf(line("Nowolek", 30, 80), line("Substancją czynną leku jest losartan potasowy", 120, 22))))
        assertEquals("losartan potasowy", inline.substance)
    }

    @Test fun defaultsAndEmptyInput() {
        assertNull(MedScan.parse(emptyList()))
        assertNull(MedScan.parse(listOf(line("   ", 10, 10))))
        // tylko szum: brak nazwy, ale jest moc, więc propozycja jest (bez nazwy)
        val onlyStrength = assertNotNull(MedScan.parse(listOf(line("20 mg", 10, 40))))
        assertEquals("", onlyStrength.name); assertEquals("tabl.", onlyStrength.unit)
        // liczby i daty nie są nazwą
        assertNull(MedScan.parse(listOf(line("12.2027", 10, 40), line("EXP 12/2027", 60, 20), line("LOT 12345", 90, 20))))
    }

    @Test fun nameTidyingKeepsMixedCaseAndFixesCaps() {
        assertEquals("Concor Cor", MedScan.tidyName("CONCOR COR"))
        assertEquals("Vitamina D3", MedScan.tidyName("Vitamina D3"))
        assertEquals("Probamed", MedScan.tidyName("  PROBAMED® "))
        assertEquals("Testovit B12", MedScan.tidyName("TESTOVIT B12"))
        // moc nie jest dublowana, gdy nazwa już ją zawiera
        assertEquals("Probamed 10 mg", MedDraft(listOf("Probamed 10 mg"), "10 mg", "", "tabl.", null).fullName())
    }
}

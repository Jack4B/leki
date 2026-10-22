package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Teksty opakowań są zmyślone (nazwy fikcyjne). */
class MedScanTest {
    private fun line(t: String, top: Int, h: Int) = OcrLine(t, 20, top, 20 + (t.length * h * 0.4).toInt(), top + h)   // proporcje jak w prawdziwym druku

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

    @Test fun brandInTheSameLineAsStrengthAndSubstanceBelowIt() {
        // przód typowego opakowania: „Nazwa® 20 mg”, pod spodem moc z formą, nazwa substancji, liczba sztuk (nazwy zmyślone)
        val d = assertNotNull(MedScan.parse(listOf(
            line("Testokinet® 20 mg", 40, 70),
            line("20 mg, tabletki", 130, 36),
            line("Probamidu chlorowodorek", 180, 40),
            line("30 tabletek", 400, 24),
            line("Należy zapoznać się z treścią ulotki przed zastosowaniem leku.", 440, 18),
            line("Do stosowania doustnego", 470, 18),
        )))
        assertEquals("Testokinet", d.name)
        assertEquals("20 mg", d.strength)
        assertEquals("Testokinet 20 mg", d.fullName())
        assertEquals("Probamidu chlorowodorek", d.substance)
        assertEquals(30.0, d.count); assertEquals("tabl.", d.unit)
        assertTrue(d.names.none { it.contains("chlorowodorek") }, d.names.toString())
    }

    @Test fun junkBoxesAndTrademarkSignal() {
        // wąska, wysoka ramka (śmieć z OCR) nie jest nazwą, nawet jeśli jest największa
        val junk = OcrLine("Axt", 300, 100, 340, 400)
        val d = assertNotNull(MedScan.parse(listOf(junk, line("Zdrowex®", 40, 50), line("Zdrowex Plus", 120, 80), line("10 mg", 220, 30))))
        assertEquals("Zdrowex", d.name)            // ® wygrywa z większym napisem
        assertTrue(d.names.none { it == "Axt" }, d.names.toString())
        assertTrue(d.names.contains("Zdrowex Plus"))
    }

    @Test fun keepsTheWholeRecognisedTextForTheUserToPickFrom() {
        val d = assertNotNull(MedScan.parse(listOf(line("Zdrowex®", 40, 50), line("10 mg", 100, 30), line("30 tabletek", 150, 24), line("x", 190, 10))))
        assertEquals(listOf("Zdrowex®", "10 mg", "30 tabletek"), d.lines)   // od góry, bez jednoznakowych śmieci
        // pojedynczy napis wyglądający na substancję (lek generyczny) jest i nazwą, i substancją
        val generic = assertNotNull(MedScan.parse(listOf(line("Probamidu chlorowodorek", 40, 60), line("10 mg", 120, 30))))
        assertEquals("Probamidu chlorowodorek", generic.name); assertEquals("Probamidu chlorowodorek", generic.substance)
    }
}

package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Porządkowanie tekstu opisu badania. Cały tekst zmyślony (przychodnia, pacjent, opis, PESEL z samych zer). */
class StudyTextTest {
    private fun line(text: String, width: Int, i: Int, gapBefore: Int = 0) = OcrLine(text, 100, i * 40 + gapBefore, 100 + width, i * 40 + gapBefore + 30)

    private val page: List<OcrLine> = listOf(
        line("NZOZ Przychodnia Przykładowa, ul. Testowa 1", 500, 0),
        line("BADANIE ULTRASONOGRAFICZNE", 400, 1),
        line("Miasto, 2030-05-10", 300, 2),
        line("Pacjent JAN TESTOWY  PESEL 00000000000", 520, 3),
        line("Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian", 700, 4),
        line("ogniskowych. Pęcherzyk cienkościenny, zawiera złóg ok. 1,5 cm. Drogi", 700, 5),
        line("żółciowe nieposzerzone.", 250, 6),
        line("Nerka prawa o prawidłowej wielkości, z prawidłowym różnicowaniem korowo-", 700, 7),
        line("rdzeniowym, bez zmian ogniskowych.", 350, 8),
        line("Pola nadnerczowe bez widocznych zmian.", 300, 9),
        line("Wnioski:", 100, 10),
        line("Kamica pęcherzyka żółciowego.", 300, 11),
        line("lek.med JAN LEKARZ", 200, 12),
        line("1234567", 90, 13),
    )

    @Test fun linesAreJoinedIntoParagraphsAndSplitIntoDescriptionAndConclusions() {
        val p = StudyText.parse(listOf(page))
        assertEquals(
            "Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian ogniskowych. Pęcherzyk cienkościenny, zawiera złóg ok. 1,5 cm. Drogi żółciowe nieposzerzone.\n\n" +
                "Nerka prawa o prawidłowej wielkości, z prawidłowym różnicowaniem korowo-rdzeniowym, bez zmian ogniskowych.\n\nPola nadnerczowe bez widocznych zmian.",
            p.description,
        )
        assertEquals("Kamica pęcherzyka żółciowego.", p.conclusions)
        assertFalse(p.isEmpty)
    }

    @Test fun letterheadPatientLineAndSignatureAreDroppedButTitleKindAndDateAreRead() {
        val p = StudyText.parse(listOf(page))
        val all = p.description + p.conclusions
        assertFalse("Przychodnia" in all || "PESEL" in all || "JAN" in all || "00000000000" in all || "lek.med" in all || "1234567" in all)
        assertEquals("Badanie ultrasonograficzne", p.title); assertEquals(StudyKind.Usg, p.kind); assertEquals(LocalDate.of(2030, 5, 10), p.date)
    }

    @Test fun withoutAPatientLineNothingIsDroppedExceptSignature() {
        val p = StudyText.parse(listOf(listOf(line("Opis bez nagłówka placówki, ale z długim pierwszym wierszem", 700, 0), line("i krótkim końcem.", 200, 1), line("dr Jan Przykładowy", 200, 2))))
        assertEquals("Opis bez nagłówka placówki, ale z długim pierwszym wierszem i krótkim końcem.", p.description)
        assertEquals("", p.title); assertNull(p.kind); assertNull(p.date)
    }

    @Test fun aWideGapBetweenLinesStartsANewParagraph() {
        val p = StudyText.parse(listOf(listOf(line("Pierwszy akapit bez kropki na końcu", 700, 0), line("drugi akapit po odstępie", 700, 1, gapBefore = 40))))
        assertEquals("Pierwszy akapit bez kropki na końcu\n\ndrugi akapit po odstępie", p.description)
    }

    @Test fun conclusionsInlineAndWithoutAHeading() {
        assertEquals("Opis." to "Brak zmian.", StudyText.splitConclusions("Opis.\n\nWnioski: Brak zmian."))
        assertEquals("Opis.\n\nDruga część." to "", StudyText.splitConclusions("Opis.\n\nDruga część."))
        assertEquals("" to "Tylko wnioski.", StudyText.splitConclusions("Wnioski\n\nTylko wnioski."))
    }

    @Test fun pagesAreSeparatedByAParagraphBreak() {
        val p = StudyText.parse(listOf(listOf(line("Strona pierwsza kończy się bez kropki", 700, 0)), listOf(line("Strona druga zaczyna się dalej.", 300, 0))))
        assertEquals("Strona pierwsza kończy się bez kropki\n\nStrona druga zaczyna się dalej.", p.description)
    }

    @Test fun plainTextReflowFixesHardWrappedLines() {
        val bad = "Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian\nogniskowych. Pęcherzyk zawiera złóg.\nNerka prawa o prawidłowej wielkości, bez zmian ogniskowych w dolnym biegunie\ntorbiel korowa.\n\nWnioski:\nKamica."
        assertEquals(
            "Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian ogniskowych. Pęcherzyk zawiera złóg.\n\nNerka prawa o prawidłowej wielkości, bez zmian ogniskowych w dolnym biegunie torbiel korowa.\n\nWnioski:\n\nKamica.",
            StudyText.reflow(bad),
        )
        val once = StudyText.reflow(bad)
        assertEquals(once, StudyText.reflow(once), "ponowne porządkowanie niczego nie zmienia")
        assertEquals("", StudyText.reflow("  \n\n "))
    }

    private class Spec(val text: String, val left: Int, val width: Int, val row: Int)

    private val flat = listOf(
        Spec("NZOZ Przychodnia Przykładowa, ul. Testowa 1", 100, 500, 0), Spec("Miasto, 2030-05-10", 800, 300, 0),
        Spec("BADANIE U LTRASONOGRAFICZNE", 250, 400, 1), Spec("Pacjent JAN TESTOWY  PESEL 00000000000", 100, 520, 2),
        Spec("Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian", 100, 700, 3),
        Spec("ogniskowych. Pęcherzyk cienkościenny, zawiera złóg ok. 1,5 cm. Drogi", 100, 700, 4), Spec("żółciowe nieposzerzone.", 100, 250, 5),
        Spec("Nerka prawa o prawidłowej wielkości, z prawidłowym różnicowaniem korowo-", 100, 700, 6), Spec("rdzeniowym, bez zmian ogniskowych.", 100, 350, 7),
        Spec("Pola nadnerczowe bez widocznych zmian.", 100, 300, 8), Spec("Wnioski:", 100, 100, 9), Spec("Kamica pęcherzyka żółciowego.", 100, 300, 10),
        Spec("lek.med JAN LEKARZ", 500, 200, 11), Spec("Jan", 520, 60, 12), Spec("P66", 520, 50, 13),
    )

    /** Prostokąty wierszy obrócone o [deg] stopni (dodatni: zgodnie z ruchem wskazówek zegara), jako pola ograniczające, jak je zwraca odczyt tekstu. */
    private fun rotated(specs: List<Spec>, deg: Double): List<OcrLine> = specs.map { s ->
        val a = Math.toRadians(deg); val y0 = s.row * 45.0; val h = 30.0
        val pts = listOf(s.left to y0, s.left + s.width to y0, s.left to y0 + h, s.left + s.width to y0 + h).map { (x, y) -> (x * Math.cos(a) - y * Math.sin(a)) to (x * Math.sin(a) + y * Math.cos(a)) }
        OcrLine(s.text, pts.minOf { it.first }.toInt(), pts.minOf { it.second }.toInt(), pts.maxOf { it.first }.toInt(), pts.maxOf { it.second }.toInt())
    }

    private val expectedDescription = "Wątroba niepowiększona, o jednorodnej echogeniczności, bez zmian ogniskowych. Pęcherzyk cienkościenny, zawiera złóg ok. 1,5 cm. Drogi żółciowe nieposzerzone.\n\n" +
        "Nerka prawa o prawidłowej wielkości, z prawidłowym różnicowaniem korowo-rdzeniowym, bez zmian ogniskowych.\n\nPola nadnerczowe bez widocznych zmian."

    @Test fun aTiltedPhotoWithScrambledOcrOrderIsReadInReadingOrder() {
        for (deg in listOf(0.0, 3.5, -3.0, 5.0)) {
            val shuffled = rotated(flat, deg).shuffled(java.util.Random(7))
            val p = StudyText.parse(listOf(shuffled))
            assertEquals(expectedDescription, p.description, "pochylenie $deg°")
            assertEquals("Kamica pęcherzyka żółciowego.", p.conclusions, "pochylenie $deg°")
            assertEquals("Badanie ultrasonograficzne", p.title, "pochylenie $deg°: rozdzielona litera jest sklejona")
            assertEquals(StudyKind.Usg, p.kind); assertEquals(LocalDate.of(2030, 5, 10), p.date)
            assertFalse("lek.med" in p.description + p.conclusions || "P66" in p.description + p.conclusions, "podpis i pieczątka są odcięte")
        }
    }

    @Test fun skewIsEstimatedFromTheLeftMarginAndFlatPagesStayFlat() {
        assertEquals(0.0, StudyText.skew(rotated(flat, 0.0)))
        assertEquals(Math.tan(Math.toRadians(4.0)), StudyText.skew(rotated(flat, 4.0)), 0.01)
        assertEquals(-Math.tan(Math.toRadians(2.0)), StudyText.skew(rotated(flat, -2.0)), 0.01)
        assertEquals(0.0, StudyText.skew(rotated(flat.take(3), 4.0)), "za mało pełnych wierszy do oceny")
        assertEquals(listOf("a"), StudyText.readingOrder(listOf(OcrLine("a", 1, 1, 5, 5))).map { it.text })
    }

    @Test fun doctorReportShowsParagraphsAndConclusionsLikeText() {
        val st = Study("a", LocalDate.of(2030, 5, 10), StudyKind.Usg, "USG jamy brzusznej", "", "Pierwszy akapit.\nDruga linia tego samego akapitu.\n\nDrugi akapit.", "Kamica.\n\nZalecana kontrola.", emptyList())
        val s = AppState(patient = "Jan Testowy", created = LocalDate.of(2030, 4, 1)).saveStudy(st).let { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(studies = true))) }
        val html = DoctorReport.html(s, Period(LocalDate.of(2030, 5, 1), LocalDate.of(2030, 5, 31)), LocalDate.of(2030, 5, 31), Fixtures.now)
        assertTrue("<p>Pierwszy akapit.<br>Druga linia tego samego akapitu.</p><p>Drugi akapit.</p>" in html)
        assertTrue("<p><b>Wnioski:</b> Kamica.</p><p>Zalecana kontrola.</p>" in html)
        assertFalse("<p></p>" in html)
    }
}

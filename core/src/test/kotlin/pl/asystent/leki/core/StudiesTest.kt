package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Badania opisowe i obrazowe. Wszystkie dane zmyślone (opisy, miejsca, numer PESEL z samych zer). */
class StudiesTest {
    private val end = LocalDate.of(2030, 6, 30)
    private val per = Period(end.minusDays(29), end)
    private fun jpeg(n: Int = 10) = "/9j/" + "QUJD".repeat(n)
    private fun study(id: String, daysAgo: Long, title: String = "USG jamy brzusznej", images: Int = 0, kind: StudyKind = StudyKind.Usg) = Study(
        id, end.minusDays(daysAgo), kind, title, "Przychodnia Przykładowa",
        "Pacjent JAN TESTOWY PESEL 00000000000\nWątroba niepowiększona, bez zmian ogniskowych.\nPęcherzyk żółciowy zawiera złóg ok. 1,5 cm.", "Kamica pęcherzyka żółciowego.",
        List(images) { StudyImage("i$it", jpeg(), if (it == 0) "Pęcherzyk" else "") })
    private val base = AppState(patient = "Jan Testowy", created = end.minusDays(60))

    @Test fun jpegCheckAndSanitize() {
        assertTrue(Studies.isJpegBase64(jpeg()))
        assertFalse(Studies.isJpegBase64("")); assertFalse(Studies.isJpegBase64("iVBORw0KGgo=")); assertFalse(Studies.isJpegBase64("/9j/ ***"))
        assertFalse(Studies.isJpegBase64("/9j/" + "A".repeat(Studies.MAX_IMAGE_CHARS)), "za duże zdjęcie")
        val big = Study("x", end, StudyKind.Tk, " ".repeat(5), "p".repeat(500), "d".repeat(30_000), "c".repeat(9_000),
            List(12) { StudyImage("i$it", if (it == 3) "nie-jpeg" else jpeg(), "c".repeat(300)) })
        val c = Studies.sanitize(big)
        assertEquals("Tomografia (TK)", c.title, "pusty tytuł dostaje nazwę rodzaju")
        assertEquals(Studies.MAX_PLACE, c.place.length); assertEquals(Studies.MAX_DESCRIPTION, c.description.length); assertEquals(Studies.MAX_CONCLUSIONS, c.conclusions.length)
        assertEquals(Studies.MAX_IMAGES, c.images.size); assertTrue(c.images.none { it.id == "i3" }); assertTrue(c.images.all { it.caption.length == Studies.MAX_CAPTION })
    }

    @Test fun totalImageLimitAndAddCheck() {
        val per1 = List(6) { Study("s$it", end, StudyKind.Usg, "USG", images = List(4) { j -> StudyImage("i$j", "/9j/" + "QUJD".repeat(80_000)) }) } // 4 * 320 004 znaków
        val capped = Studies.capTotal(per1)
        assertTrue(Studies.imageChars(capped) <= Studies.MAX_TOTAL_CHARS); assertEquals(4, capped[0].images.size); assertTrue(capped.sumOf { it.images.size } < 24, "zdjęcia ponad limit są pomijane")
        assertNull(Studies.cannotAddImage(emptyList(), null, 1000))
        assertTrue(Studies.cannotAddImage(listOf(study("a", 1, images = Studies.MAX_IMAGES)), study("a", 1, images = Studies.MAX_IMAGES), 1000)!!.contains("najwyżej"))
        assertTrue(Studies.cannotAddImage(capped, null, 400_000)!!.contains("za duże"))
        assertTrue(Studies.cannotAddImage(capped, null, 300_000)!!.contains("MB"), "łączny limit")
    }

    @Test fun pdfIsRecognisedWithoutRelyingOnMimeType() {
        val pdf = "%PDF-1.7".toByteArray(); val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0)
        assertTrue(Studies.looksLikePdf("application/pdf", "wynik", null))
        assertTrue(Studies.looksLikePdf(null, "wynik.PDF", null), "samo rozszerzenie")
        assertTrue(Studies.looksLikePdf("application/octet-stream", "plik", pdf), "nagłówek PDF, typ i nazwa nic nie mówią")
        assertTrue(Studies.looksLikePdf("image/jpeg", "zdjecie.jpg", pdf), "nagłówek rozstrzyga")
        assertFalse(Studies.looksLikePdf("image/jpeg", "wynik.pdf", jpg), "typ obrazu i nagłówek JPEG wygrywają z nazwą")
        assertFalse(Studies.looksLikePdf(null, "zdjecie.jpg", jpg)); assertFalse(Studies.looksLikePdf(null, null, null)); assertFalse(Studies.looksLikePdf("text/plain", "notatka.txt", "abc".toByteArray()))
    }

    @Test fun importPreviewMentionsStudiesAndImages() {
        assertEquals("", Studies.importNote(emptyList()))
        assertEquals(", badania opisowe i obrazowe: 1", Studies.importNote(listOf(study("a", 1))))
        assertEquals(", badania opisowe i obrazowe: 2, 3 zdjęcia", Studies.importNote(listOf(study("a", 1, images = 2), study("b", 2, images = 1))))
        assertEquals(", badania opisowe i obrazowe: 1, 1 zdjęcie", Studies.importNote(listOf(study("a", 1, images = 1))))
        assertEquals(", badania opisowe i obrazowe: 1, 5 zdjęć", Studies.importNote(listOf(study("a", 1, images = 5))))
        assertEquals(", badania opisowe i obrazowe: 1, 12 zdjęć", Studies.importNote(listOf(study("a", 1, images = 12))))
        // kopia z samymi badaniami (bez leków, pomiarów i wyników) ma w podglądzie co pokazać po wczytaniu
        val only = Backup.parseAll(Backup.export(base.saveStudy(study("a", 1, images = 2)))).single()
        assertTrue(only.meds.isEmpty() && only.bp.isEmpty() && only.labs.isEmpty())
        assertEquals(", badania opisowe i obrazowe: 1, 2 zdjęcia", Studies.importNote(only.studies))
    }

    @Test fun identifiersAreStrippedFromTextForAi() {
        val t = "Pacjent JAN TESTOWY   PESEL 00000000000\nWątroba niepowiększona.\n\n\n\nNr 12345678901 skierowania\nWnioski: brak."
        assertEquals("Wątroba niepowiększona.\n\nWnioski: brak.", Studies.stripIdentifiers(t))
        assertEquals("Opis bez numerów.", Studies.stripIdentifiers("Opis bez numerów."))
        assertEquals("Numer 1234567890 ma 10 cyfr.", Studies.stripIdentifiers("Numer 1234567890 ma 10 cyfr."), "10 cyfr to nie PESEL")
    }

    @Test fun selectionAutoPeriodAndManualAnyDate() {
        val s = base.copy(studies = listOf(study("a", 3), study("b", 10), study("old", 200, "Tomografia głowy", kind = StudyKind.Tk)))
        val auto = StudySelection.forReport(s, per, null)
        assertEquals(listOf("a", "b"), auto.chosen.map { it.id }); assertEquals(2, auto.inPeriod)
        assertEquals(listOf("old"), StudySelection.forReport(s, per, listOf("old", "brak")).chosen.map { it.id }, "wybór ręczny bez ograniczenia datą")
        assertTrue(StudySelection.forReport(s, per, emptyList()).chosen.isEmpty())
        assertEquals(listOf("a", "b", "old"), StudySelection.toggled(s, per, null, "old"))
        assertEquals(listOf("b"), StudySelection.toggled(s, per, null, "a"))
        val many = base.copy(studies = (1..8).map { study("s$it", it.toLong()) })
        assertEquals(StudySelection.AUTO_MAX, StudySelection.forReport(many, per, null).chosen.size)
    }

    @Test fun saveDeleteAndCleanupOfSelection() {
        val s = base.saveStudy(study("a", 3, images = 2)).saveStudy(study("a", 3, title = "  ", images = 2).copy(kind = StudyKind.Mri))
        assertEquals(1, s.studies.size); assertEquals("Rezonans (MRI)", s.studies[0].title)
        val picked = s.copy(settings = s.settings.copy(rp = s.settings.rp.copy(studyIds = listOf("a", "z"))))
        assertEquals(listOf("z"), picked.deleteStudy("a").settings.rp.studyIds); assertTrue(picked.deleteStudy("a").studies.isEmpty())
        assertNull(s.deleteStudy("a").settings.rp.studyIds)
    }

    @Test fun undoOfADeleteRestoresTheStudyAndItsPick() {
        val st = study("a", 3, images = 1)
        val s = base.saveStudy(st).saveStudy(study("b", 5)).let { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(studyIds = listOf("a", "b")))) }
        val gone = s.deleteStudy("a")
        assertEquals(listOf("b"), gone.settings.rp.studyIds)
        val back = gone.restoreStudy(st, wasPicked = true)
        assertEquals(setOf("a", "b"), back.studies.map { it.id }.toSet()); assertEquals(listOf("b", "a"), back.settings.rp.studyIds)
        assertEquals(listOf("b"), gone.restoreStudy(st, wasPicked = false).settings.rp.studyIds, "niewybrane badanie wraca bez wyboru")
        // tryb automatyczny zostaje automatyczny
        assertNull(base.saveStudy(st).deleteStudy("a").restoreStudy(st, wasPicked = true).settings.rp.studyIds)
    }

    @Test fun backupRoundTripAndOldBackups() {
        val s = base.saveStudy(study("a", 3, images = 2)).let { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(studies = true, studyIds = listOf("a")), ai = it.settings.ai.copy(studies = true))) }
        val back = Backup.parseState(Backup.export(s))
        assertEquals(s.studies, back.studies); assertTrue(back.settings.rp.studies && back.settings.ai.studies); assertEquals(listOf("a"), back.settings.rp.studyIds)
        assertEquals(s, back)
        val plain = Backup.export(base)
        assertFalse("studies" in plain || "studyIds" in plain, "pola domyślne nie są zapisywane")
        val old = Backup.parseState(Fixtures.text("state.json"))
        assertTrue(old.studies.isEmpty()); assertFalse(old.settings.rp.studies); assertFalse(old.settings.ai.studies); assertNull(old.settings.rp.studyIds)
        val enc = BackupCrypto.encrypt("haslo-12345", Backup.export(s), iter = 100_000)
        assertEquals(s.studies, Backup.parseState(BackupCrypto.decrypt("haslo-12345", Backup.parseEnvelope(enc))).studies)
    }

    @Test fun corruptStudiesInAFileAreDropped() {
        val json = """{"v":3,"days":{},"studies":[
            {"id":"a","date":"2030-06-01","kind":"usg","title":"USG","images":[{"id":"x","data":"nie-jpeg"},{"id":"y","data":"${jpeg()}"}]},
            {"id":"b","date":"zła data","title":"x"},{"date":"2030-06-01","title":"bez id"},"nie obiekt"]}"""
        val st = Backup.parseState(json).studies
        assertEquals(listOf("a"), st.map { it.id }); assertEquals(listOf("y"), st[0].images.map { it.id })
    }

    @Test fun doctorReportShowsOnlyPickedStudiesWithImages() {
        val s0 = base.copy(studies = listOf(study("a", 3, images = 2), study("b", 100, "Tomografia głowy", kind = StudyKind.Tk)))
        val off = DoctorReport.html(s0, per, end, Fixtures.now)
        assertFalse("Badania opisowe i obrazowe" in off, "domyślnie wyłączone")
        val on = s0.copy(settings = s0.settings.copy(rp = s0.settings.rp.copy(studies = true)))
        val html = DoctorReport.html(on, per, end, Fixtures.now)
        assertTrue("<h3>Badania opisowe i obrazowe</h3>" in html && "USG jamy brzusznej" in html && "Kamica pęcherzyka żółciowego." in html)
        assertFalse("Tomografia głowy" in html, "badanie spoza okresu wchodzi dopiero po wyborze")
        assertEquals(2, Regex("<img src=\"data:image/jpeg;base64,").findAll(html).count()); assertTrue("<figcaption>Pęcherzyk</figcaption>" in html)
        assertTrue("Wątroba niepowiększona, bez zmian ogniskowych.<br>Pęcherzyk żółciowy zawiera złóg ok. 1,5 cm." in html, "opis jest pokazany jak wpisany, z podziałem wierszy")
        val picked = on.copy(settings = on.settings.copy(rp = on.settings.rp.copy(studyIds = listOf("b"))))
        val h2 = DoctorReport.html(picked, per, end, Fixtures.now)
        assertTrue("Tomografia głowy" in h2 && "USG jamy brzusznej" !in h2)
        val none = DoctorReport.html(on.copy(settings = on.settings.copy(rp = on.settings.rp.copy(studyIds = emptyList()))), per, end, Fixtures.now)
        assertTrue("Nie wybrano badań do raportu." in none)
        // ukryty moduł Badania nie wchodzi do raportu
        assertFalse("USG jamy brzusznej" in DoctorReport.html(on.copy(settings = on.settings.copy(labsOn = false)), per, end, Fixtures.now))
    }

    @Test fun aiReportHasTextWithoutImagesAndWithoutPesel() {
        val s0 = base.copy(studies = listOf(study("a", 3, images = 2)))
        assertFalse("Badania opisowe i obrazowe" in AiReport.build(s0, per, end, Fixtures.now, "test"))
        val on = s0.copy(settings = s0.settings.copy(ai = s0.settings.ai.copy(studies = true)))
        val t = AiReport.build(on, per, end, Fixtures.now, "test")
        assertTrue("## Badania opisowe i obrazowe (opis słowny, bez obrazów)" in t && "- `## Badania opisowe i obrazowe`" in t)
        assertTrue("Wątroba niepowiększona, bez zmian ogniskowych. Pęcherzyk żółciowy zawiera złóg ok. 1,5 cm." in t)
        assertTrue("- Wnioski: Kamica pęcherzyka żółciowego." in t)
        assertFalse("PESEL" in t || "00000000000" in t || "JAN TESTOWY" in t, "numer PESEL i imię z wiersza nagłówkowego nie trafiają do AI")
        assertFalse("/9j/" in t); assertTrue("2 zdjęcia z tego badania; nie ma ich w tym tekście." in t)
        assertTrue("obrazów nie widzisz i nie oceniaj ich" in t)
        val picked = on.copy(settings = on.settings.copy(rp = on.settings.rp.copy(studyIds = emptyList())))
        assertFalse("## Badania opisowe i obrazowe (opis słowny" in AiReport.build(picked, per, end, Fixtures.now, "test"), "pusty wybór ręczny")
    }
}

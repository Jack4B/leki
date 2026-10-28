package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyInfoTest {
    private val today = LocalDate.of(2030, 5, 10)
    private val per = Period(today.minusDays(6), today)
    private val base = AppState(patient = "Jan Testowy", created = today.minusDays(30))
    private fun withBody(s: AppState, year: Int = 1976, kg: Double = 82.5, cm: Int = 178) = s.copy(settings = s.settings.copy(birthYear = year, weightKg = kg, heightCm = cm))

    @Test fun ageBmiAndDescription() {
        assertEquals(54, BodyInfo.age(1976, today))
        assertNull(BodyInfo.age(0, today)); assertNull(BodyInfo.age(2031, today)); assertNull(BodyInfo.age(1800, today))
        assertEquals(26.0, BodyInfo.bmi(82.5, 178))
        assertNull(BodyInfo.bmi(0.0, 178)); assertNull(BodyInfo.bmi(82.5, 0))
        assertEquals("wiek ok. 54 lata (rok urodzenia 1976), masa ciała 82,5 kg, wzrost 178 cm, BMI 26", BodyInfo.describe(withBody(base).settings, today))
        assertEquals("wzrost 178 cm", BodyInfo.describe(Settings(heightCm = 178), today))
        assertNull(BodyInfo.describe(Settings(), today))
        assertEquals("wiek ok. 1 rok (rok urodzenia 2029)", BodyInfo.describe(Settings(birthYear = 2029), today))
        assertEquals("wiek ok. 25 lat (rok urodzenia 2005)", BodyInfo.describe(Settings(birthYear = 2005), today))
    }

    @Test fun parsingFieldsAcceptsBlankAndRejectsNonsense() {
        assertEquals(0, BodyInfo.parseYear("", today)); assertEquals(1976, BodyInfo.parseYear(" 1976 ", today))
        assertNull(BodyInfo.parseYear("2031", today)); assertNull(BodyInfo.parseYear("76", today)); assertNull(BodyInfo.parseYear("abc", today))
        assertEquals(0.0, BodyInfo.parseWeight("")); assertEquals(82.5, BodyInfo.parseWeight("82,5")); assertEquals(82.5, BodyInfo.parseWeight("82.46"))
        assertNull(BodyInfo.parseWeight("1")); assertNull(BodyInfo.parseWeight("500")); assertNull(BodyInfo.parseWeight("x"))
        assertEquals(0, BodyInfo.parseHeight("")); assertEquals(178, BodyInfo.parseHeight("178")); assertNull(BodyInfo.parseHeight("20")); assertNull(BodyInfo.parseHeight("300"))
    }

    @Test fun survivesBackupAndDefaultsAreNotWritten() {
        val plain = Backup.export(base)
        assertFalse("birthYear" in plain || "weightKg" in plain || "heightCm" in plain || "\"profile\"" in plain)
        val back = Backup.parseState(Backup.export(withBody(base)))
        assertEquals(1976, back.settings.birthYear); assertEquals(82.5, back.settings.weightKg); assertEquals(178, back.settings.heightCm)
        assertEquals(withBody(base), back)
        val off = Backup.parseState(Backup.export(base.copy(settings = base.settings.copy(ai = base.settings.ai.copy(profile = false)))))
        assertFalse(off.settings.ai.profile)
        // stara kopia bez tych pól: brak danych, profil w AI włączony
        val old = Backup.parseState(Fixtures.text("state.json"))
        assertEquals(0, old.settings.birthYear); assertEquals(0.0, old.settings.weightKg); assertEquals(0, old.settings.heightCm); assertTrue(old.settings.ai.profile)
    }

    @Test fun nonsenseValuesInAFileAreDropped() {
        val json = Backup.export(withBody(base)).replace(Regex("\"birthYear\"\\s*:\\s*1976"), "\"birthYear\":12").replace(Regex("\"weightKg\"\\s*:\\s*82.5"), "\"weightKg\":9999").replace(Regex("\"heightCm\"\\s*:\\s*178"), "\"heightCm\":7")
        assertTrue("\"birthYear\":12" in json && "\"weightKg\":9999" in json && "\"heightCm\":7" in json, "podmiana w pliku się udała")
        val s = Backup.parseState(json).settings
        assertEquals(0, s.birthYear); assertEquals(0.0, s.weightKg); assertEquals(0, s.heightCm)
    }

    @Test fun aiReportHasTheBodyDataOnlyWhenGivenAndEnabled() {
        val none = AiReport.build(base, per, today, Fixtures.now, "test")
        assertFalse("Dane z ustawień aplikacji" in none)
        val st = withBody(base)
        val ai = AiReport.build(st, per, today, Fixtures.now, "test")
        assertTrue("- Dane z ustawień aplikacji: wiek ok. 54 lata (rok urodzenia 1976), masa ciała 82,5 kg, wzrost 178 cm, BMI 26." in ai)
        val off = AiReport.build(st.copy(settings = st.settings.copy(ai = st.settings.ai.copy(profile = false))), per, today, Fixtures.now, "test")
        assertFalse("Dane z ustawień aplikacji" in off)
        // raport dla lekarza nie dostaje tych danych
        assertFalse("1976" in DoctorReport.html(st, per, today, Fixtures.now))
    }

    @Test fun aiPromptAsksForDoctorAndPharmacistPerspectiveAndCurrentGuidelines() {
        val t = AiReport.build(base, per, today, Fixtures.now, "test")
        assertTrue("lekarz (internista" in t && "farmaceuta kliniczny" in t)
        assertTrue("aktualnej wiedzy klinicznej i aktualnych wytycznych" in t)
        assertTrue("normami dla wieku pacjenta".replace("normami", "norm") in t)
        assertTrue("Nie zalecaj samodzielnego odstawiania leków ani zmiany dawek" in t, "ostrzeżenie o konsultacji zostaje")
    }
}

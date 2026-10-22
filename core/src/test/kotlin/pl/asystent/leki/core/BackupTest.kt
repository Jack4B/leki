package pl.asystent.leki.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackupTest {
    @Test fun loadsLegacyState() {
        val s = Fixtures.state
        assertEquals("Jan Testowy", s.patient)
        assertEquals(4, s.meds.size)
        assertEquals(4, s.slots.size)
        assertEquals(1.5, s.med("a")!!.phases[1].doses["morning"])
        assertEquals(listOf(1, 3, 5), s.med("b")!!.phases[0].dow)
        assertTrue("a|morning" in s.day(Fixtures.today.minusDays(1)).taken)
        assertEquals("Zawroty głowy | po południu", s.day(Fixtures.today.minusDays(2)).note)
        // wynik wpisany przy kontroli trafia do dziennika badań
        assertTrue(s.labs.any { it.id == "ev:e0" && it.value == 4.3 })
    }

    @Test fun legacyExportEnvelopeLoads() {
        val s = Backup.parseState(Fixtures.text("export.json"))
        assertEquals(Fixtures.state.meds.size, s.meds.size)
        assertEquals(Fixtures.state.bp.size, s.bp.size)
    }

    @Test fun roundTripKeepsData() {
        val s = Fixtures.state
        val back = Backup.parseState(Backup.export(s))
        assertEquals(s, back)
        val back2 = Backup.parseState(Backup.serialize(s))
        assertEquals(s, back2)
    }

    @Test fun exportHasLegacyShape() {
        val o = Json.parseToJsonElement(Backup.export(Fixtures.state)) as kotlinx.serialization.json.JsonObject
        assertEquals("asystent-lekow", (o["app"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("3", (o["version"] as kotlinx.serialization.json.JsonPrimitive).content)
        val legacy = Json.parseToJsonElement(Fixtures.text("export.json")) as kotlinx.serialization.json.JsonObject
        val keysNew = (o["data"] as kotlinx.serialization.json.JsonObject).keys
        val keysOld = (legacy["data"] as kotlinx.serialization.json.JsonObject).keys
        // nowe pola od wersji 0.5: analizy wklejone przez użytkownika i zapisy EKG; stare kopie go nie mają i wczytują się z pustą listą
        assertEquals(keysOld + setOf("analyses", "ecgs"), keysNew)
    }

    @Test fun decryptsLegacyEncryptedBackup() {
        val env = Backup.parseEnvelope(Fixtures.text("enc.json"))
        assertEquals("enc", Backup.kindOf(env))
        val plain = BackupCrypto.decrypt("Hasło-żółć 123", env)
        val s = Backup.parseState(plain)
        assertEquals(Fixtures.state.meds.size, s.meds.size)
        assertFailsWith<BackupCrypto.WrongPasswordException> { BackupCrypto.decrypt("zle haslo", env) }
    }

    @Test fun encryptRoundTrip() {
        val enc = BackupCrypto.encrypt("tajne-haslo", "zażółć gęślą jaźń", iter = 100_000)
        assertEquals("zażółć gęślą jaźń", BackupCrypto.decrypt("tajne-haslo", Backup.parseEnvelope(enc)))
        assertNotEquals(enc, BackupCrypto.encrypt("tajne-haslo", "zażółć gęślą jaźń", iter = 100_000))
    }

    @Test fun rejectsWrongFiles() {
        assertFailsWith<Backup.FormatException> { Backup.parseState("""{"days":{},"bp":[]}""") }
        assertFailsWith<Backup.FormatException> { Backup.parseState("""{"foo":1}""") }
        assertFailsWith<Backup.FormatException> { Backup.parseState("nie json") }
    }

    @Test fun toleratesBrokenFragments() {
        val s = Backup.parseState(
            """{"v":3,"days":{"2026-01-01":{"taken":{"a|b":1},"water":999,"note":5},"zly":{}},"meds":[{"id":"x"},{"id":"m","name":"M","phases":[{"from":"2026-01-01","doses":{"morning":0,"night":2},"dow":[9]}]}],
               "bp":[{"sys":120,"dia":80,"date":"2026-01-02","time":"07:00"},{"sys":"x"}],"slots":[],"markers":[]}""",
        )
        assertEquals(1, s.meds.size)
        assertEquals(mapOf("night" to 2.0), s.meds[0].phases[0].doses)
        assertEquals(Defaults.allDow, s.meds[0].phases[0].dow)
        assertEquals(1, s.bp.size)
        assertEquals(100, s.days.values.first().water)
        assertEquals(4, s.slots.size)
        assertEquals(5, s.markers.size)
    }

    @Test fun analysesSurviveBackupAndOldBackupsHaveNone() {
        assertTrue(Fixtures.state.analyses.isEmpty())
        val a = Analysis("a1", java.time.LocalDate.of(2026, 10, 21), "Analiza AI", "# Tytuł\n\n## Pilne\nTekst z \$2{,}8\\text{ mmol/l}\$\n\n## Szczegóły\nDużo tekstu", listOf("Szczegóły"))
        val s = Fixtures.state.saveAnalysis(a).let { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(analysisId = "a1", analysis = false))) }
        val back = Backup.parseState(Backup.export(s))
        assertEquals(s, back)
        assertEquals(a, back.analysis("a1"))
        assertEquals(a, back.reportAnalysis())
        // usunięcie analizy czyści wybór w raporcie
        val del = back.deleteAnalysis("a1")
        assertTrue(del.analyses.isEmpty()); assertEquals("", del.settings.rp.analysisId)
        // zaszyfrowana kopia też ją niesie
        val enc = BackupCrypto.encrypt("haslo-12345", Backup.export(s), iter = 100_000)
        assertEquals(a, Backup.parseState(BackupCrypto.decrypt("haslo-12345", Backup.parseEnvelope(enc))).analysis("a1"))
    }

    @Test fun formatting() {
        assertEquals("1", num(1.0)); assertEquals("0,5", num(0.5)); assertEquals("2,15", lnum(2.15)); assertEquals("134", lnum(134.0))
        assertEquals("pomiarów", plN(5, "pomiar", "pomiary", "pomiarów")); assertEquals("pomiary", plN(22, "pomiar", "pomiary", "pomiarów"))
        assertEquals("Środa 21 października", longDay(Fixtures.today))
    }
}

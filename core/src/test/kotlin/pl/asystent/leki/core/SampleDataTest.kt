package pl.asystent.leki.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleDataTest {
    private fun dir(): File {
        var d: File? = File(".").canonicalFile
        while (d != null && !File(d, "przyklady").isDirectory && !File(d, "gradle.properties").isFile) d = d.parentFile
        return File(d ?: error("brak katalogu repozytorium"), "przyklady").also { it.mkdirs() }
    }

    @Test fun samplesMatchGeneratorAndLoadInApp() {
        val dir = dir()
        if (System.getenv("WRITE_SAMPLES") == "1") SampleData.files().forEach { (n, t) -> File(dir, n).writeText(t, Charsets.UTF_8) }
        SampleData.files().forEach { (n, t) -> assertEquals(t, File(dir, n).readText(Charsets.UTF_8), "plik $n jest nieaktualny (WRITE_SAMPLES=1 odtwarza)") }

        val one = Backup.parseAll(File(dir, "przyklad-jedna-osoba.json").readText())
        assertEquals(1, one.size)
        val j = one[0]
        assertEquals("Jan Przykładowy", j.patient)
        assertEquals(3, j.meds.size); assertTrue(j.bp.size > 60); assertTrue(j.days.size >= 80); assertTrue(j.labs.size >= 12)
        // dane wystarczają na raport za ostatnie 30 dni i na statystyki
        assertTrue(DoctorReport.html(j, Period(SampleData.last.minusDays(29), SampleData.last), SampleData.last, java.time.LocalDateTime.of(SampleData.last, java.time.LocalTime.NOON)).length > 3000)

        val two = Backup.parseAll(File(dir, "przyklad-dwie-osoby.json").readText())
        assertEquals(listOf("Jan Przykładowy", "Ola Przykładowa"), two.map { it.patient })
        assertTrue(two[1].settings.cyklOn && !two[1].settings.bpOn && two[1].bp.isEmpty())
        val sum = Cykl.summary(two[1].cykl.db)!!
        assertTrue(sum.entries > 90 && sum.last == "2026-10-03" && sum.periods == 9, sum.toString())

        val diary = File(dir, "przyklad-dziennik-cyklu.json").readText()
        assertTrue(Cykl.looksLikeDb(diary) && Cykl.isDb(diary))

        val labs = Backup.parseLabsFile(Backup.parseEnvelope(File(dir, "przyklad-wyniki-badan.json").readText()))
        assertEquals(4, labs.markers.size); assertEquals(12, labs.labs.size)
        assertEquals("labs", Backup.kindOf(Backup.parseEnvelope(File(dir, "przyklad-wyniki-badan.json").readText())))
    }
}

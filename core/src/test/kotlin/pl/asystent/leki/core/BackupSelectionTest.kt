package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupSelectionTest {
    private val day = LocalDate.of(2030, 5, 5)
    private val diary = """{"entries":[{"type":"period_start","date":"2030-03-01"}]}"""
    private fun person(name: String, cykl: CyklData = CyklData()) = AppState(patient = name, cykl = cykl)

    // p1 ma dziennik, p2 i p3 korzystają z dziennika p1, p4 ma własny pusty
    private val all = listOf("p1" to person("Ala", CyklData(diary, "")), "p2" to person("Bea"), "p3" to person("Cyd"), "p4" to person("Dan"))
    private val owner = { id: String -> if (id == "p2" || id == "p3") "p1" else id }

    @Test fun nullMeansEveryoneInAppOrder() {
        val r = Backup.selectForExport(all, null, owner, day)
        assertEquals(listOf("Ala", "Bea", "Cyd", "Dan"), r.map { it.patient })
        assertEquals(listOf("", "#0", "#0", ""), r.map { it.settings.cyklFrom })
        assertTrue(r.all { it.lastBackup == day })
    }

    @Test fun orderFollowsAppNotSelection() {
        val r = Backup.selectForExport(all, setOf("p4", "p2", "p1"), owner, day)
        assertEquals(listOf("Ala", "Bea", "Dan"), r.map { it.patient })
        assertEquals("#0", r[1].settings.cyklFrom)
    }

    @Test fun ownerOutsideSelectionDiaryIsCarriedOnceByFirstUser() {
        val r = Backup.selectForExport(all, setOf("p2", "p3"), owner, day)
        assertEquals(listOf("Bea", "Cyd"), r.map { it.patient })
        // dziennik właściciela jest w pliku raz, druga osoba wskazuje na pierwszą
        assertEquals(diary, r[0].cykl.db); assertEquals("", r[0].settings.cyklFrom)
        assertTrue(r[1].cykl.isEmpty); assertEquals("#0", r[1].settings.cyklFrom)
        // po zapisie i wczytaniu powiązanie zostaje
        val back = Backup.parseAll(Backup.exportAll(r))
        assertEquals("#0", back[1].settings.cyklFrom)
        assertEquals(diary, back[0].cykl.db)
    }

    @Test fun singleUserOfSharedDiaryGetsItsCopy() {
        val r = Backup.selectForExport(all, setOf("p3"), owner, day)
        assertEquals(1, r.size)
        assertEquals(diary, r[0].cykl.db); assertEquals("", r[0].settings.cyklFrom)
    }

    @Test fun unknownSelectionIsRefusedInsteadOfExportingEveryone() {
        assertFailsWith<IllegalArgumentException> { Backup.selectForExport(all, setOf("p9"), owner, day) }
        assertFailsWith<IllegalArgumentException> { Backup.selectForExport(all, emptySet(), owner, day) }
    }

    @Test fun addedNamesStayUnique() {
        val taken = mutableSetOf("ala", "bea")
        assertEquals("Cyd", Backup.uniqueName(" Cyd ", taken))
        val first = Backup.uniqueName("Ala", taken); taken.add(first.lowercase())
        val second = Backup.uniqueName("ala", taken); taken.add(second.lowercase())
        val third = Backup.uniqueName("ALA", taken)
        assertEquals("Ala (z pliku)", first)
        assertEquals("ala (z pliku 3)", second)
        assertEquals("ALA (z pliku 4)", third)
        assertEquals("", Backup.uniqueName("  ", taken))
    }
}

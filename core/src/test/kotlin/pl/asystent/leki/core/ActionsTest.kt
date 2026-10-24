package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionsTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val now = LocalDateTime.of(2026, 10, 21, 9, 7, 33)

    private fun base(): AppState {
        val m = Med("a", "Lek A", unit = "tabl.", timerMin = 30, note = "po posiłku", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 2.0, "dinner" to 1.0), Defaults.allDow)))
        return AppState(created = today.minusDays(5), meds = listOf(m))
    }

    @Test fun toggleDoseTakesAndStartsTimer() {
        val s = base().toggleDose(today, "a|morning", today, now)
        val r = s.day(today)
        assertTrue("a|morning" in r.taken)
        assertEquals(LocalTime.of(9, 7), r.times["a|morning"])
        assertTrue(s.timerEnd > 0)
        assertEquals("Lek A", s.timerMed)
        // cofnięcie
        val back = s.toggleDose(today, "a|morning", today, now)
        assertFalse("a|morning" in back.day(today).taken)
    }

    @Test fun pastDoseGetsSlotTimeAndNoTimer() {
        val d = today.minusDays(1)
        val s = base().toggleDose(d, "a|dinner", today, now)
        assertEquals(LocalTime.of(19, 0), s.day(d).times["a|dinner"])
        assertEquals(0L, s.timerEnd)
    }

    @Test fun skipAndClear() {
        var s = base().toggleDose(today, "a|morning", today, now).skipDose(today, "a|morning", "", " uwaga ")
        assertFalse("a|morning" in s.day(today).taken)
        assertEquals(Skipped("Bez podania powodu", "uwaga"), s.day(today).skipped["a|morning"])
        s = s.clearSkip(today, "a|morning")
        assertTrue(s.days[today] == null || s.day(today).isBlank)
    }

    @Test fun markAllAndStats() {
        val s = base().markAll(today, today, java.time.LocalDateTime.of(today, java.time.LocalTime.of(23, 59)))
        val st = s.dayStats(today)
        assertEquals(2, st.tot); assertEquals(2, st.n); assertTrue(st.complete)
    }

    @Test fun waterTogglesDown() {
        var s = base().setWater(today, 3)
        assertEquals(3, s.day(today).water)
        s = s.setWater(today, 3)
        assertEquals(2, s.day(today).water)
    }

    @Test fun eventResultSyncsToLabs() {
        val ev = Event("e1", today, "Badanie", marker = "potas")
        var s = base().copy(events = listOf(ev)).setEventResult("e1", " 4,2 ")
        assertEquals(4.2, s.labs.single { it.id == "ev:e1" }.value)
        assertEquals("4,2", s.day(today).results["e1"])
        s = s.setEventResult("e1", "")
        assertTrue(s.labs.none { it.id == "ev:e1" })
        // edycja wyniku w dzienniku badań zmienia też pole przy kontroli
        s = base().copy(events = listOf(ev)).setEventResult("e1", "4,2")
        s = s.saveLab(s.labs.single().copy(value = 3.9))
        assertEquals("3,9", s.day(today).results["e1"])
        s = s.deleteLab("ev:e1")
        assertNull(s.day(today).results["e1"])
    }

    @Test fun saveMedValidatesAndReplaces() {
        val s0 = base()
        val bad = s0.saveMed(MedEdit(null, " ", "", "", "", 0, 0.0, null, today, mapOf("morning" to 1.0), Defaults.allDow))
        assertTrue(bad is MedSave.Error)
        val noDose = s0.saveMed(MedEdit(null, "X", "", "", "", 0, 0.0, null, today, mapOf("zly" to 1.0), Defaults.allDow))
        assertTrue(noDose is MedSave.Error)
        val ok = s0.saveMed(MedEdit(null, " Nowy ", "", "", "", 0, 0.0, null, today, mapOf("morning" to 1.0), Defaults.allDow, replaces = "a"))
        assertTrue(ok is MedSave.Ok)
        val s1 = (ok as MedSave.Ok).state
        assertEquals(today.minusDays(1), s1.med("a")!!.end)
        assertEquals("Nowy", s1.meds.last().name)
        assertEquals("Dodano Nowy. Lek A zakończony 20.10.2026.", ok.message)
        // zmiana dawkowania dodaje fazę
        val ch = s0.saveMed(MedEdit("a", "Lek A", "", "", "tabl.", 30, 0.0, null, today, mapOf("morning" to 1.0), Defaults.allDow))
        assertEquals(2, ((ch as MedSave.Ok).state.med("a")!!.phases.size))
        // koniec przed początkiem
        val err = s0.saveMed(MedEdit("a", "Lek A", "", "", "tabl.", 0, 0.0, today.minusDays(30), today, mapOf("morning" to 1.0), Defaults.allDow))
        assertTrue(err is MedSave.Error)
    }

    @Test fun deleteMedCleansDays() {
        val s = base().toggleDose(today, "a|morning", today, now).skipDose(today, "a|dinner", "x", "")
        val d = s.deleteMed("a")
        assertTrue(d.meds.isEmpty())
        assertTrue(d.days.values.all { it.taken.isEmpty() && it.skipped.isEmpty() })
    }

    @Test fun deleteSlotKeepsLastAndCleansDoses() {
        val s = base()
        val d = s.deleteSlot("dinner")!!
        assertEquals(3, d.slots.size)
        assertNull(d.med("a")!!.phases[0].doses["dinner"])
        assertNull(AppState(slots = listOf(Slot("x", "X", LocalTime.NOON))).deleteSlot("x"))
    }

    @Test fun labsMerge() {
        val s = AppState(labs = listOf(Lab("l", today, "potas", 4.0)))
        val f = Backup.LabsFile(
            markers = listOf(Marker("potas", "Potas", "mmol/l", 3.6, 5.1), Marker("nowy", "Nowy", "u", 1.0, 2.0)),
            labs = listOf(Lab("1", today, "potas", 4.1), Lab("2", today.minusDays(1), "potas", 3.9), Lab("3", today, "nowy", 1.5), Lab("4", today, "obcy", 9.0)),
        )
        val p = s.previewLabsMerge(f)
        assertEquals(LabsMergePreview(fresh = 2, skipped = 2, newMarkers = 1), p)
        val m = s.applyLabsMerge(f)
        assertEquals(3, m.labs.size)
        assertEquals(3.6, m.marker("potas")!!.lo)
        assertNotNull(m.marker("nowy"))
    }

    @Test fun remindersSkipTakenAndPast() {
        val zone = java.time.ZoneId.of("Europe/Warsaw")
        var s = base().copy(settings = Settings(remindOn = true, remindLead = 10, backupRemindDays = 0, stockWarnDays = 0))
        val nowMs = LocalDateTime.of(2026, 10, 21, 7, 0).atZone(zone).toInstant().toEpochMilli()
        val all = s.reminders(zone, nowMs, today)
        assertEquals(28, all.size) // 14 dni x (śniadanie, kolacja)
        assertEquals("Pora na leki: Śniadanie", all.first().title)
        assertEquals(LocalDateTime.of(2026, 10, 21, 7, 50).atZone(zone).toInstant().toEpochMilli(), all.first().atMillis)
        s = s.toggleDose(today, "a|morning", today, now)
        // 27 przypomnień o porach (poranna dawka odhaczona) i jedno z stopera po dawce
        val after = s.reminders(zone, nowMs, today)
        assertEquals(27, after.count { it.id != 1 })
        assertEquals(1, after.count { it.id == 1 })
    }

    @Test fun needBackupMessages() {
        val s = base().toggleDose(today, "a|morning", today, now)
        assertEquals("nigdy", s.copy(created = today.minusDays(3)).needBackup(today))
        assertNull(s.copy(lastBackup = today.minusDays(6)).needBackup(today))
        assertEquals("7 dni temu", s.copy(lastBackup = today.minusDays(7)).needBackup(today))
    }
}

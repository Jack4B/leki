package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyTest {
    private fun bp(sys: Int, dia: Int) = Bp("x", LocalDate.of(2031, 1, 1), LocalTime.of(8, 0), sys, dia, null, "rano", "")

    @Test fun veryHighAndVeryLowBloodPressureAreFlaggedRegardlessOfSettings() {
        assertTrue(bpVeryHigh(bp(180, 90)) && bpVeryHigh(bp(150, 110)) && !bpVeryHigh(bp(179, 109)))
        assertTrue(bpVeryLow(bp(85, 60)) && bpVeryLow(bp(110, 45)) && !bpVeryLow(bp(95, 60)))
        val s = Settings(hiSys = 250, hiDia = 150)           // progi ustawione bardzo wysoko nie wyłączają wskazówki
        assertEquals("bardzo wysokie", s.bpFlagText(bp(195, 115)))
        assertEquals("", s.bpFlagText(bp(120, 80)))
        assertEquals("podwyższone", Settings().bpFlagText(bp(140, 85)))
        assertTrue(BP_ALERT_HIGH.contains("112") && BP_ALERT_LOW.contains("112"))
    }

    @Test fun defaultsAreConservativeAndSexNeutral() {
        assertEquals(90, Settings().loSys)
        val m = Defaults.markers().associateBy { it.id }
        assertEquals(5.1, m.getValue("potas").hi)
        assertNull(m.getValue("kreatynina").lo); assertNull(m.getValue("kreatynina").hi)   // zakres zależy od płci i laboratorium
        assertNull(m.getValue("moczowy").hi)
    }

    @Test fun largeDosesSurviveBackupInsteadOfVanishing() {
        val st = Fixtures.state
        val med = st.meds.first()
        val big = st.copy(meds = listOf(med.copy(phases = listOf(Phase(Fixtures.today.minusDays(5), mapOf(st.slots.first().id to 60.0, st.slots.last().id to 10.0), Defaults.allDow)))))
        val back = Backup.parseState(Backup.export(big)).meds.first().phases.first().doses
        assertEquals(60.0, back[st.slots.first().id])
        assertEquals(10.0, back[st.slots.last().id])
    }

    @Test fun doseLimitKeepsMilligramDosesAndDropsAbsurdOnes() {
        val st = Fixtures.state
        val med = st.meds.first()
        val a = st.slots.first().id; val b = st.slots.last().id
        val big = st.copy(meds = listOf(med.copy(phases = listOf(Phase(Fixtures.today.minusDays(5), mapOf(a to Backup.MAX_DOSE, b to Backup.MAX_DOSE + 1), Defaults.allDow)))))
        val back = Backup.parseState(Backup.export(big)).meds.first().phases.first().doses
        assertEquals(Backup.MAX_DOSE, back[a])
        assertNull(back[b])
    }

    @Test fun markAllKeepsSkippedDosesAndLaterSlotsToday() {
        val st = Fixtures.state
        val today = Fixtures.today
        val morning = LocalDateTime.of(today, LocalTime.of(7, 0))
        val after = st.markAll(today, today, morning)
        val items = st.scheduleFor(today)
        val later = items.filter { it.slot.time.minutes > 7 * 60 }
        assertTrue(later.none { it.key in after.day(today).taken }, "dawki z późniejszych pór nie są odhaczane o 7:00")
        // świadome pominięcie zostaje
        val skipKey = items.first().key
        val withSkip = st.updateDay(today) { it.copy(skipped = it.skipped + (skipKey to Skipped("Złe samopoczucie"))) }
        val done = withSkip.markAll(today, today, LocalDateTime.of(today, LocalTime.of(23, 0)))
        assertTrue(skipKey in done.day(today).skipped && skipKey !in done.day(today).taken)
        assertFalse(items.drop(1).any { it.key !in done.day(today).taken && it.key !in done.day(today).skipped })
    }
}

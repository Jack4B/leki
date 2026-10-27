package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcgSelectionTest {
    private val end = LocalDate.of(2030, 3, 31)
    private val per = Period(end.minusDays(29), end)

    private fun ecg(id: String, daysAgo: Long, hr: Int = 70) =
        Ecg(id, end.minusDays(daysAgo), LocalTime.of(8, 0), watchHr = hr, watchNote = "Zapis $id", fs = 125, cmv = List(125 * 2) { 0 })

    private val state = AppState(patient = "Jan Testowy", created = end.minusDays(40), ecgs = (1..8).map { ecg("e$it", it.toLong(), 60 + it) } + ecg("stary", 90))

    @Test fun autoModeTakesNewestFromPeriod() {
        val d = EcgSelection.forReport(state, per, null, EcgSelection.AUTO_DOCTOR)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6"), d.chosen.map { it.id })
        assertEquals(8, d.inPeriod)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), EcgSelection.forReport(state, per, null, EcgSelection.AUTO_AI).chosen.map { it.id })
    }

    @Test fun manualModeTakesOnlyPickedFromPeriod() {
        val d = EcgSelection.forReport(state, per, listOf("e7", "e2", "stary", "brak"), EcgSelection.AUTO_DOCTOR)
        assertEquals(listOf("e2", "e7"), d.chosen.map { it.id }, "zapis spoza okresu i nieistniejący są pominięte, kolejność od najnowszego")
        assertTrue(EcgSelection.forReport(state, per, emptyList(), EcgSelection.AUTO_DOCTOR).chosen.isEmpty(), "pusty wybór ręczny to brak zapisów, nie tryb automatyczny")
    }

    @Test fun togglingFromAutoStartsWithTheAutomaticSix() {
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7"), EcgSelection.toggled(state, per, null, "e7"))
        assertEquals(listOf("e2", "e3", "e4", "e5", "e6"), EcgSelection.toggled(state, per, null, "e1"))
        assertEquals(listOf("e3"), EcgSelection.toggled(state, per, listOf("e3", "e5"), "e5"))
        assertEquals(emptyList(), EcgSelection.toggled(state, per, listOf("e3"), "e3"))
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e7"), EcgSelection.toggled(state, per, null, "e7", EcgSelection.AUTO_AI), "w raporcie dla AI start od 5 najnowszych")
    }

    @Test fun doctorReportShowsOnlyPickedRecords() {
        val st = state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = listOf("e7", "e2"))))
        val html = DoctorReport.html(st, per, end, Fixtures.now)
        assertTrue(plDate(end.minusDays(7)) in html && plDate(end.minusDays(2)) in html)
        assertFalse(plDate(end.minusDays(1)) in html, "niezaznaczony najnowszy zapis nie wchodzi do raportu")
        assertTrue("Pokazano 2 wybranych z 8 zapisów EKG" in html)
        val auto = DoctorReport.html(state, per, end, Fixtures.now)
        assertTrue("Pokazano 6 najnowszych z 8 zapisów EKG" in auto)
        val none = DoctorReport.html(state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = emptyList()))), per, end, Fixtures.now)
        assertTrue("Nie wybrano zapisów EKG do raportu" in none)
    }

    @Test fun aiReportFollowsTheSamePick() {
        val st = state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = listOf("e7"))))
        val ai = AiReport.build(st, per, end, Fixtures.now, "test")
        assertTrue(plDate(end.minusDays(7)) in ai)
        assertFalse(plDate(end.minusDays(1)) in ai)
        assertTrue(plDate(end.minusDays(1)) in AiReport.build(state, per, end, Fixtures.now, "test"))
    }

    @Test fun pickSurvivesBackupAndDefaultIsNotWritten() {
        assertFalse("ecgIds" in Backup.export(state))
        assertNull(Backup.parseState(Backup.export(state)).settings.rp.ecgIds)
        val st = state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = listOf("e2", "e7"))))
        assertEquals(listOf("e2", "e7"), Backup.parseState(Backup.export(st)).settings.rp.ecgIds)
        val none = state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = emptyList())))
        assertEquals(emptyList(), Backup.parseState(Backup.export(none)).settings.rp.ecgIds)
        assertNull(Backup.parseState(Fixtures.text("state.json")).settings.rp.ecgIds, "starsza kopia: tryb automatyczny")
    }

    @Test fun deletingARecordDropsItFromThePick() {
        val st = state.copy(settings = state.settings.copy(rp = state.settings.rp.copy(ecgIds = listOf("e2", "e7"))))
        assertEquals(listOf("e7"), st.deleteEcg("e2").settings.rp.ecgIds)
        assertNull(state.deleteEcg("e2").settings.rp.ecgIds)
    }
}

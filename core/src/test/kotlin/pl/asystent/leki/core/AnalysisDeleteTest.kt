package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisDeleteTest {
    private val day = LocalDate.of(2030, 5, 10)
    private val a1 = Analysis("a1", day, "Analiza A", "# A\n\n## Pilne\nTekst")
    private val a2 = Analysis("a2", day.plusDays(1), "Analiza B", "# B")
    private val base = AppState(patient = "Jan Testowy", created = day).saveAnalysis(a1).saveAnalysis(a2)
    private fun pick(s: AppState, id: String) = s.copy(settings = s.settings.copy(rp = s.settings.rp.copy(analysisId = id)))

    @Test fun deletingOneAnalysisKeepsTheOthers() {
        val s = base.deleteAnalysis("a1")
        assertEquals(listOf("a2"), s.analyses.map { it.id })
        assertEquals("a2", s.reportAnalysis()?.id, "bez wyboru do raportu idzie najnowsza")
    }

    @Test fun deletingThePickedAnalysisClearsThePickAndUndoRestoresBoth() {
        val picked = pick(base, "a1")
        val gone = picked.deleteAnalysis("a1")
        assertEquals("", gone.settings.rp.analysisId)
        val back = gone.restoreAnalysis(a1, wasPicked = true)
        assertEquals("a1", back.settings.rp.analysisId)
        assertEquals(setOf("a1", "a2"), back.analyses.map { it.id }.toSet())
        assertEquals(a1, back.analysis("a1"))
    }

    @Test fun undoOfANotPickedAnalysisDoesNotChangeThePick() {
        val picked = pick(base, "a2")
        val back = picked.deleteAnalysis("a1").restoreAnalysis(a1, wasPicked = false)
        assertEquals("a2", back.settings.rp.analysisId)
        assertTrue(back.analyses.any { it.id == "a1" })
    }

    @Test fun deletingTheLastAnalysisRemovesItFromTheDoctorReport() {
        val withIt = base.copy(settings = base.settings.copy(rp = base.settings.rp.copy(analysis = true, analysisId = "a1")))
        val per = Period(day.minusDays(6), day)
        assertTrue("analiza lekarsko-farmaceutyczna" in DoctorReport.html(withIt, per, day, Fixtures.now))
        val none = withIt.deleteAnalysis("a1").deleteAnalysis("a2")
        assertTrue(none.analyses.isEmpty())
        assertTrue("analiza lekarsko-farmaceutyczna" !in DoctorReport.html(none, per, day, Fixtures.now))
    }
}

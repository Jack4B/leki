package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModulesTest {
    private val s = Fixtures.state
    private val per = Fixtures.period(7)

    @Test fun defaultsKeepEveryModuleVisible() {
        assertTrue(Settings().bpOn && Settings().labsOn)
        // stare kopie (bez pól bpOn i labsOn) mają wszystkie moduły widoczne
        val old = Backup.parseState(Fixtures.text("state.json"))
        assertTrue(old.settings.bpOn && old.settings.labsOn)
        assertFalse(Backup.export(old).contains("bpOn") || Backup.export(old).contains("labsOn"), "domyślne wartości nie są zapisywane")
    }

    @Test fun hiddenModulesSurviveBackupRoundTrip() {
        val st = s.copy(settings = s.settings.copy(bpOn = false, labsOn = false, cyklOn = true))
        val back = Backup.parseState(Backup.export(st))
        assertFalse(back.settings.bpOn); assertFalse(back.settings.labsOn); assertTrue(back.settings.cyklOn)
        // dane ukrytego modułu zostają w kopii
        assertEquals(s.bp.size, back.bp.size); assertEquals(s.labs.size, back.labs.size)
        val inner = Backup.parseState(Backup.serialize(st))
        assertFalse(inner.settings.bpOn)
        val all = Backup.parseAll(Backup.exportAll(listOf(st, s)))
        assertFalse(all[0].settings.bpOn); assertTrue(all[1].settings.bpOn)
    }

    @Test fun hiddenModulesStayOutOfReports() {
        assertTrue(s.bp.isNotEmpty() && s.labs.isNotEmpty())
        val on = DoctorReport.html(s, per, Fixtures.today, Fixtures.now)
        val noBp = DoctorReport.html(s.copy(settings = s.settings.copy(bpOn = false)), per, Fixtures.today, Fixtures.now)
        assertTrue(on.length > noBp.length, "raport bez ciśnienia jest krótszy")
        assertFalse(noBp.contains("Pomiary ciśnienia"))
        val ai = AiReport.build(s.copy(settings = s.settings.copy(labsOn = false)), per, Fixtures.today, Fixtures.now, "test")
        val aiOn = AiReport.build(s, per, Fixtures.today, Fixtures.now, "test")
        assertTrue(aiOn.length > ai.length)
        val eff = s.settings.copy(bpOn = false, labsOn = false).effectiveRp()
        assertFalse(eff.bp || eff.labs || eff.ecg || eff.labsPeriod)
        assertTrue(eff.meds)
        // ukryty moduł Cykl nie wchodzi do raportów, nawet jeśli pigułka była włączona
        val c = s.settings.copy(rp = s.settings.rp.copy(cykl = true), ai = s.settings.ai.copy(cykl = true))
        assertTrue(c.copy(cyklOn = true).effectiveRp().cykl && c.copy(cyklOn = true).effectiveAi().cykl)
        assertFalse(c.copy(cyklOn = false).effectiveRp().cykl || c.copy(cyklOn = false).effectiveAi().cykl)
    }
}

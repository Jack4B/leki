package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventResultTest {
    private val day = LocalDate.of(2030, 5, 10)
    private val event = Event("k1", day, "Kontrola potasu", field = "Potas (mmol/l)", marker = "potas")
    private val base = AppState(patient = "Jan Testowy", created = day.minusDays(30)).saveEvent(event)

    private fun fileWith(value: Double, on: LocalDate = day) =
        Backup.LabsFile(listOf(Marker("potas", "Potas", "mmol/l", 3.5, 5.1, "Elektrolity")), listOf(Lab("f1", on, "potas", value, "z pliku")))

    @Test fun waitingEventHasNoResult() {
        assertNull(base.eventResult(event))
    }

    @Test fun importedResultMarksTheEventDoneWithoutDuplicates() {
        val s = base.applyLabsMerge(fileWith(4.4))
        assertEquals("4,4", s.eventResult(event))
        assertEquals("4,4", s.day(day).results["k1"], "wynik trafia też do kontroli (raport: Kontrole i wyniki)")
        assertEquals(1, s.labs.count { it.marker == "potas" && it.date == day })
        // wpisanie tej samej wartości ręcznie w kontroli nie tworzy drugiego wyniku
        val again = s.setEventResult("k1", "4,4")
        assertEquals(1, again.labs.count { it.marker == "potas" && it.date == day })
    }

    @Test fun manualEntryAfterImportDoesNotDuplicateInTheLabLog() {
        val s = base.copy(labs = listOf(Lab("x1", day, "potas", 4.6, ""))).setEventResult("k1", "4,6")
        assertEquals(1, s.labs.count { it.marker == "potas" && it.date == day })
    }

    @Test fun manualEntryStillCreatesTheLabWhenNothingElseExists() {
        val s = base.setEventResult("k1", "4,2")
        assertEquals(listOf("ev:k1"), s.labs.filter { it.marker == "potas" }.map { it.id })
        assertEquals("4,2", s.eventResult(event))
    }

    @Test fun resultFromAnotherDayDoesNotCloseTheEvent() {
        val s = base.applyLabsMerge(fileWith(4.4, on = day.minusDays(1)))
        assertNull(s.eventResult(event))
        assertTrue(s.day(day).results.isEmpty())
    }

    @Test fun manualLabAndLaterEventAreLinked() {
        val viaLab = base.saveLab(Lab("m1", day, "potas", 5.0, ""))
        assertEquals("5", viaLab.eventResult(event))
        val laterEvent = AppState(created = day.minusDays(30), labs = listOf(Lab("m1", day, "potas", 5.0, ""))).saveEvent(event)
        assertEquals("5", laterEvent.eventResult(event))
        assertEquals(1, laterEvent.labs.size)
    }

    @Test fun editingTheLabUpdatesTheLinkedResult() {
        val s = base.saveLab(Lab("m1", day, "potas", 5.0, "")).saveLab(Lab("m1", day, "potas", 4.1, ""))
        assertEquals("4,1", s.eventResult(event))
    }

    @Test fun deletingTheLabReopensTheEvent() {
        val s = base.applyLabsMerge(fileWith(4.4)).deleteLab("f1")
        assertNull(s.eventResult(event))
        assertTrue(s.labs.isEmpty())
    }

    @Test fun labAddedAfterManualEntryReplacesTheEventLabInsteadOfDuplicating() {
        // najpierw wpis ręczny w kontroli (powstaje wynik ev:k1), potem ten sam parametr z tego dnia dodany w Badaniach
        val manual = base.setEventResult("k1", "4,2")
        assertEquals(listOf("ev:k1"), manual.labs.map { it.id })
        val s = manual.saveLab(Lab("m2", day, "potas", 4.8, ""))
        assertEquals(listOf("m2"), s.labs.map { it.id }, "w dzienniku jeden wynik z tego dnia")
        assertEquals(1, s.labs.count { it.marker == "potas" && it.date == day })
        assertEquals("4,8", s.eventResult(event), "wynik z Badań jest źródłem")
        // po usunięciu wyniku z Badań kontrola znów czeka na wpis (wpis ręczny został zastąpiony)
        val gone = s.deleteLab("m2")
        assertNull(gone.eventResult(event)); assertTrue(gone.labs.isEmpty())
    }

    @Test fun eventFieldCannotDriftFromAnExistingLabResult() {
        val s = base.applyLabsMerge(fileWith(4.4)).setEventResult("k1", "9,9")
        assertEquals("4,4", s.eventResult(event))
        assertEquals(listOf(4.4), s.labs.map { it.value })
        // zmiana wyniku w Badaniach zmienia też kontrolę
        assertEquals("4,1", s.saveLab(Lab("f1", day, "potas", 4.1, "")).eventResult(event))
    }

    @Test fun savingTheEventAfterManualEntryAndLabKeepsOneResult() {
        val legacy = base.copy(labs = listOf(Lab("ev:k1", day, "potas", 4.2, "Kontrola potasu"), Lab("m3", day, "potas", 4.6, "")))
        val s = legacy.saveEvent(event)
        assertEquals(listOf("m3"), s.labs.map { it.id })
        assertEquals("4,6", s.eventResult(event))
    }

    @Test fun eventWithoutMarkerIsNotLinked() {
        val plain = Event("k2", day, "Wizyta")
        val s = AppState(created = day.minusDays(30)).saveEvent(plain).applyLabsMerge(fileWith(4.4))
        assertNull(s.eventResult(plain))
    }

    @Test fun doctorReportShowsTheLinkedResult() {
        val s = base.applyLabsMerge(fileWith(4.4))
        val html = DoctorReport.html(s, Period(day.minusDays(6), day), day, Fixtures.now)
        assertTrue("<b>4,4</b>" in html)
    }
}

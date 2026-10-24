package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pl.asystent.leki.core.Profiles
import pl.asystent.leki.core.storage.StartOver.Step

class StartOverTest {
    @Test fun writesAreBlockedWheneverThePendingFileExistsWhateverItsContent() {
        assertFalse(StartOver.writesBlocked(null), "brak pliku postępu")
        assertTrue(StartOver.writesBlocked(""), "proces zabity w trakcie kroku (1), zanim zapisano postęp")
        assertTrue(StartOver.writesBlocked(StartOver.encode(setOf(Step.RENAME))))
        assertTrue(StartOver.writesBlocked(StartOver.encode(setOf(Step.RENAME, Step.KEYS, Step.MARKER))), "do usunięcia pliku po kroku (4)")
        assertTrue(StartOver.writesBlocked("\u0000śmieci{{"), "nieczytelny postęp też blokuje")
        assertNull(StartOver.decode("\u0000śmieci{{"))
    }

    @Test fun unreadableProgressRestartsFromTheFirstStep() {
        assertEquals(Step.values().toList(), StartOver.remainingFor("nieczytelne"))
        assertEquals(listOf(Step.KEYS2), StartOver.remainingFor("RENAME,KEYS,MARKER"))
    }

    @Test fun remainingStepsContinueForward() {
        assertEquals(listOf(Step.KEYS, Step.MARKER, Step.KEYS2), StartOver.remaining(setOf(Step.RENAME)))
        assertEquals(emptyList(), StartOver.remaining(Step.values().toSet()))
    }

    @Test fun pendingFileRoundTripAndRejectsGaps() {
        val done = setOf(Step.RENAME, Step.KEYS)
        assertEquals(done, StartOver.decode(StartOver.encode(done)))
        assertEquals(emptySet(), StartOver.decode(""))
        assertNull(StartOver.decode("KEYS"), "krok bez poprzednich")
        assertNull(StartOver.decode("RENAME,NIEZNANY"))
        assertNull(StartOver.decode("RENAME,RENAME"))
    }

    @Test fun wipeAllRemovesDiscardedRejectedAndUnreadableLeftovers() {
        for (n in listOf("state.uszkodzony.json", "state-p2.uszkodzony.enc", "state.odrzucony.json", "state-p3.odrzucony.enc",
            "state.nieczytelny.enc", "state-p2.nieczytelny.enc", "state.przed-osobami.json")) assertTrue(Profiles.isLeftover(n), n)
        for (n in listOf("state.json", "state.enc", "state-p2.enc", "keys.bin", "keys.bin.2", "storage.json", "profiles.json", "startover.pending")) assertFalse(Profiles.isLeftover(n), n)
    }
}

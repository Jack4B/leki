package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageMarkerTest {
    @Test fun roundTrip() {
        val m = StorageMarker(StorageMode.TO_ENC, 2, 1)
        assertEquals(m, StorageMarker.parse(m.toJson()))
    }

    @Test fun invalidMarkersAreNull() {
        for (t in listOf("", "{", "[]", """{"v":2,"mode":"enc","dekId":1,"attempts":0}""", """{"v":1,"mode":"x","dekId":1,"attempts":0}""",
            """{"v":1,"mode":"enc","dekId":0,"attempts":0}""", """{"v":1,"mode":"enc","dekId":1,"attempts":4}""", """{"v":1,"mode":"enc","dekId":1,"attempts":-1}""",
            """{"v":1,"mode":"enc","dekId":1}""", """{"v":1,"mode":"enc","attempts":0}""", """{"v":"1","mode":"enc","dekId":1,"attempts":0}""")) {
            assertNull(StorageMarker.parse(t), t)
        }
    }

    @Test fun attemptsNeverExceedLimitAndResetOnSuccessOrManualRetry() {
        var m = StorageMarker(StorageMode.PLAIN, 1)
        repeat(5) { m = m.afterFailure() }
        assertEquals(3, m.attempts); assertFalse(m.mayAutoRetry)
        assertEquals(m, StorageMarker.parse(m.toJson()), "po wielu porażkach znacznik pozostaje ważny")
        assertEquals(0, m.afterManualRetry().attempts); assertEquals(0, m.afterSuccess().attempts)
        assertTrue(m.afterManualRetry().mayAutoRetry)
    }

    @Test fun rollbackRestoresBindingModeInBothDirectionsAndCountsOneCommonAttemptCounter() {
        val a = StorageMarker(StorageMode.TO_ENC, 1, 0).rolledBack()
        assertEquals(StorageMode.PLAIN, a.mode); assertEquals(1, a.attempts)
        val b = StorageMarker(StorageMode.TO_PLAIN, 1, a.attempts).rolledBack()
        assertEquals(StorageMode.ENC, b.mode); assertEquals(2, b.attempts, "licznik wspólny dla obu kierunków")
        assertEquals(StorageMarker(StorageMode.ENC, 1, 2), StorageMarker(StorageMode.ENC, 1, 2).rolledBack())
    }

    @Test fun evaluateMissingOrBrokenMarker() {
        assertIs<MarkerState.FreshPlain>(StorageMarker.evaluate(null, false, emptyList()))
        assertIs<MarkerState.FreshPlain>(StorageMarker.evaluate("{", false, emptyList()))
        assertIs<MarkerState.ReadOnlyEnc>(StorageMarker.evaluate(null, true, listOf(1)), "brak znacznika, ale są .enc lub keys.bin")
        assertIs<MarkerState.ReadOnlyEnc>(StorageMarker.evaluate("{", true, emptyList()))
    }

    @Test fun dekIdMustMatchAnyKeyCopyNotOnlyTheFirst() {
        val json = StorageMarker(StorageMode.ENC, 1).toJson()
        assertIs<MarkerState.Valid>(StorageMarker.evaluate(json, true, listOf(1, 1)))
        assertIs<MarkerState.Valid>(StorageMarker.evaluate(json, true, listOf(2, 1)), "keys.bin ma nowy dekId, keys.bin.2 zgodny ze znacznikiem")
        assertIs<MarkerState.Valid>(StorageMarker.evaluate(json, true, listOf(1)), "druga kopia nieczytelna")
        assertIs<MarkerState.ReadOnlyEnc>(StorageMarker.evaluate(json, true, listOf(2, 3)))
        assertIs<MarkerState.Valid>(StorageMarker.evaluate(json, true, emptyList()), "bez odczytanych kopii znacznika nie da się zweryfikować; rozstrzyga klasa F")
    }

    @Test fun problemPrecedence() {
        assertEquals(StorageProblem.KEYS_FILE_BROKEN, StorageProblem.first(StorageProblem.values().toList()))
        assertEquals(StorageProblem.MARKER_INVALID, StorageProblem.first(listOf(StorageProblem.FILE_CORRUPT, StorageProblem.MARKER_INVALID, StorageProblem.KEY_UNAVAILABLE)))
        assertEquals(StorageProblem.KEY_ALIAS_MISSING, StorageProblem.first(listOf(StorageProblem.KEY_UNAVAILABLE, StorageProblem.KEY_ALIAS_MISSING)))
        assertNull(StorageProblem.first(emptyList()))
    }

    @Test fun extraFieldsAreIgnoredButVersionMustBeTheIntegerOne() {
        assertEquals(StorageMarker(StorageMode.ENC, 2, 1), StorageMarker.parse("""{"v":1,"mode":"enc","dekId":2,"attempts":1,"nowe":{"a":[1,2]},"x":"y"}"""))
        assertNull(StorageMarker.parse("""{"v":1.0,"mode":"enc","dekId":1,"attempts":0}"""), "1.0 nie jest liczbą całkowitą")
        assertNull(StorageMarker.parse("""{"v":1,"mode":"enc","dekId":1.5,"attempts":0}"""))
        assertNull(StorageMarker.parse("""{"v":1,"mode":"enc","dekId":1,"attempts":0}""" + " ".repeat(StorageMarker.MAX_CHARS)), "ponad ok. 4 KB")
    }

    @Test fun inPlainModeDekIdMismatchDoesNotInvalidateTheMarker() {
        for (mode in listOf(StorageMode.PLAIN, StorageMode.TO_ENC)) assertIs<MarkerState.Valid>(StorageMarker.evaluate(StorageMarker(mode, 2).toJson(), true, listOf(1, 1)), mode.id)
        for (mode in listOf(StorageMode.ENC, StorageMode.TO_PLAIN)) assertIs<MarkerState.ReadOnlyEnc>(StorageMarker.evaluate(StorageMarker(mode, 2).toJson(), true, listOf(1, 1)), mode.id)
    }
}

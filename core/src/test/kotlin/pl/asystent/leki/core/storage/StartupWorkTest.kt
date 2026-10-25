package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupWorkTest {
    private val ids = listOf("p1", "p2")
    private val cipher = SoftCipher(TEST_DEK)
    private val wrap = FakeKeyWrap().also { it.add("leki_kek_1") }
    private fun healthyEnc(): MemOps = MemOps().also { m ->
        ids.forEach { m.files[StorageNames.encFile(it)] = DataFile.seal(cipher, 1, 1, DataFile.Kind.STATE, it, """{"dose":"nie"}""".toByteArray()) }
        val keys = KeyFile.encode(wrap, "leki_kek_1", 1, TEST_DEK)
        m.files[StorageNames.KEYS] = keys; m.files[StorageNames.KEYS2] = keys
        m.files[StorageNames.MARKER] = StorageMarker(StorageMode.ENC, 1).toJson().toByteArray()
    }

    @Test fun healthyEncModeHasNothingToFinishSoWritesAreAllowedFromProcessStart() {
        assertFalse(Migration.hasPendingWork(healthyEnc()))
        assertFalse(Migration.hasPendingWork(MemOps()), "instalacja jawna bez plików szyfrowania")
        assertFalse(Migration.hasPendingWork(MemOps(linkedMapOf("state.json" to "{}".toByteArray(), "profiles.json" to "{}".toByteArray()))))
    }

    @Test fun startFromReceiverInHealthyEncStoresTheTakenDose() {
        val m = healthyEnc()
        // „start z odbiornika”: odczyt tylko do odczytu, bez sprzątania, potem odhaczenie dawki trafia do pliku
        val r = StorageReader.read(m, wrap, { SoftCipher(it) }, ids)
        assertNull(r.problem); assertFalse(r.readOnly)
        assertFalse(Migration.hasPendingWork(m))
        val taken = """{"dose":"tak"}""".toByteArray()
        StorageWriter.writePerson(m, r.cipher!!, r.dekId!!, "p1", taken, nowMillis = 1_700_000_000_000)
        val after = StorageReader.read(m, wrap, { SoftCipher(it) }, ids)
        assertContentEquals(taken, after.persons["p1"]!!.plain)
        assertEquals(1_700_000_000L, StorageWriter.generation(1_700_000_000_000))
        assertTrue(StorageNames.plainFile("p1") !in m.files, "nie powstaje jawny plik")
    }

    @Test fun somethingToFinishBlocksWritesUntilBootstrap() {
        fun pending(setup: (MemOps) -> Unit) = Migration.hasPendingWork(healthyEnc().also(setup))
        assertTrue(pending { it.files[StartOver.PENDING_FILE] = ByteArray(0) }, "startover.pending")
        assertTrue(pending { it.files[StorageNames.MARKER] = StorageMarker(StorageMode.TO_ENC, 1).toJson().toByteArray() }, "to-enc")
        assertTrue(pending { it.files[StorageNames.MARKER] = StorageMarker(StorageMode.TO_PLAIN, 1).toJson().toByteArray() }, "to-plain")
        assertTrue(pending { it.files[StorageNames.MARKER] = "{".toByteArray() }, "nieważny znacznik")
        assertTrue(pending { it.files.remove(StorageNames.MARKER) }, "brak znacznika przy .enc")
        assertTrue(pending { it.files["state.json"] = "{}".toByteArray() }, "jawna resztka w trybie enc")
        assertTrue(pending { it.files["state-p2.json"] = "{}".toByteArray() }, "jawna resztka drugiej osoby")
        val plainWithEnc = healthyEnc().also { it.files[StorageNames.MARKER] = StorageMarker(StorageMode.PLAIN, 1).toJson().toByteArray() }
        assertTrue(Migration.hasPendingWork(plainWithEnc), "kandydaci .enc w trybie plain")
        assertFalse(Migration.hasPendingWork(MemOps(linkedMapOf(StorageNames.MARKER to StorageMarker(StorageMode.PLAIN, 1).toJson().toByteArray(), "state.json" to "{}".toByteArray()))))
    }
}

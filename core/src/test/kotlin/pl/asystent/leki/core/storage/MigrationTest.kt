package pl.asystent.leki.core.storage

import pl.asystent.leki.core.Profiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    private val ids = listOf("p1", "p2")
    private val cipher = SoftCipher(TEST_DEK)
    private val wrap = FakeKeyWrap().also { it.add("leki_kek_1") }
    private val data = mapOf("p1" to """{"v":3,"days":{},"patient":"Jan Testowy"}""".toByteArray(), "p2" to """{"v":3,"days":{},"patient":"Ewa Testowa"}""".toByteArray())

    private fun plainDir(): MemOps = MemOps().also { m ->
        data.forEach { (id, b) -> m.files[StorageNames.plainFile(id)] = b }
        m.files[Profiles.BEFORE_PEOPLE] = "{}".toByteArray()
        val keys = KeyFile.encode(wrap, "leki_kek_1", 1, TEST_DEK)
        m.files[StorageNames.KEYS] = keys; m.files[StorageNames.KEYS2] = keys
        // kolejność przygotowania (plan, krok 0): najpierw znacznik plain z nowym dekId, dopiero potem keys.bin
        m.files[StorageNames.MARKER] = StorageMarker(StorageMode.PLAIN, 1).toJson().toByteArray()
    }

    private fun read(ops: FileOps) = StorageReader.read(ops, wrap, { SoftCipher(it) }, ids)
    private fun assertData(ops: FileOps, why: String) {
        val r = read(ops)
        assertNull(r.problem, why)
        data.forEach { (id, b) -> assertContentEquals(b, r.persons.getValue(id).plain, "$why $id") }
    }

    @Test fun enableHappyPath() {
        val m = plainDir()
        assertEquals(Migration.Outcome.DONE, Migration.enable(m, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)))
        assertEquals(StorageMarker(StorageMode.ENC, 1, 0), Migration.readMarker(m))
        assertTrue(ids.all { StorageNames.plainFile(it) !in m.files && StorageNames.encFile(it) in m.files })
        assertFalse(Profiles.BEFORE_PEOPLE in m.files)
        assertData(m, "po włączeniu")
    }

    @Test fun killAtEveryStepOfEnablingNeverLosesDataAndCanBeCompleted() {
        val total = plainDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.enable(k, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)); k.ops }
        for (k in 1..total + 1) {
            val m = plainDir()
            try { Migration.enable(KillingOps(m, k), cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) } catch (e: Killed) {}
            // restart: wiążący format wraca; po cofnięciu zapisy są odblokowane, a dane równe oryginałowi
            val back = Migration.recoverAfterRestart(m, cipher, ids)
            if (back != null) assertEquals(StorageMode.PLAIN, back.mode, "przerwane włączanie wraca do plain (krok $k)")
            assertData(m, "po przerwaniu w kroku $k")
            val marker = Migration.readMarker(m)
            assertTrue(marker == null || marker.mode == StorageMode.PLAIN || marker.mode == StorageMode.ENC, "krok $k: znacznik nie zostaje w to-*")
            // dokończenie
            if (marker?.mode != StorageMode.ENC) assertEquals(Migration.Outcome.DONE, Migration.enable(m, cipher, 1, ids, marker ?: StorageMarker(StorageMode.PLAIN, 1)))
            assertEquals(StorageMode.ENC, Migration.readMarker(m)!!.mode)
            assertData(m, "po dokończeniu (krok $k)")
        }
    }

    @Test fun killAtEveryStepOfDisablingNeverLosesData() {
        fun encDir() = plainDir().also { Migration.enable(it, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) }
        val total = encDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.disable(k, cipher, 1, ids, StorageMarker(StorageMode.ENC, 1)); k.ops }
        for (k in 1..total + 1) {
            val m = encDir()
            try { Migration.disable(KillingOps(m, k), cipher, 1, ids, StorageMarker(StorageMode.ENC, 1)) } catch (e: Killed) {}
            val back = Migration.recoverAfterRestart(m, cipher, ids)
            if (back != null) assertEquals(StorageMode.ENC, back.mode, "przerwane wyłączanie wraca do enc (krok $k)")
            assertData(m, "po przerwaniu wyłączania w kroku $k")
        }
    }

    @Test fun failedVerificationRollsBackDeletesCandidatesAndCountsAttempt() {
        val m = plainDir()
        val lying = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray) = cipher.decrypt(iv, body, aad).also { it[0] = 'X'.code.toByte() }
        }
        assertEquals(Migration.Outcome.ROLLED_BACK, Migration.enable(m, lying, 1, ids, StorageMarker(StorageMode.PLAIN, 1)))
        assertEquals(StorageMarker(StorageMode.PLAIN, 1, 1), Migration.readMarker(m))
        assertTrue(ids.none { StorageNames.encFile(it) in m.files }, "kandydaci usunięci")
        assertData(m, "po nieudanej weryfikacji")
    }

    @Test fun attemptsStayValidAfterRepeatedFailures() {
        val m = plainDir()
        val lying = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray) = cipher.decrypt(iv, body, aad).also { it[0] = 'X'.code.toByte() }
        }
        var marker = StorageMarker(StorageMode.PLAIN, 1)
        repeat(5) { Migration.enable(m, lying, 1, ids, marker); marker = Migration.readMarker(m)!! }
        assertEquals(3, marker.attempts); assertFalse(marker.mayAutoRetry)
        assertEquals(marker, Migration.readMarker(m), "znacznik pozostaje ważny")
        assertEquals(Migration.Outcome.DONE, Migration.enable(m, cipher, 1, ids, marker.afterManualRetry()))
    }

    @Test fun candidateWithKeyErrorIsNotDeletedOnRollback() {
        val m = plainDir()
        Migration.writeMarker(m, StorageMarker(StorageMode.TO_ENC, 1, 0))
        m.files[StorageNames.encFile("p1")] = DataFile.seal(cipher, 1, 1, DataFile.Kind.STATE, "p1", data.getValue("p1"))
        val noKey = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = throw KeyUnavailableException("niedostępny")
        }
        Migration.recoverAfterRestart(m, noKey, ids)
        assertTrue(StorageNames.encFile("p1") in m.files, "błąd klucza nie jest powodem do skasowania")
        assertEquals(StorageMode.PLAIN, Migration.readMarker(m)!!.mode)
    }
}

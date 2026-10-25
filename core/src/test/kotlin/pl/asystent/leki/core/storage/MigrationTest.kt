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
            val rec = Migration.recoverAfterRestart(m, cipher, ids); assertTrue(rec.writesMayResume)
            val back = rec.marker
            if (back != null) assertEquals(StorageMode.PLAIN, back.mode, "przerwane włączanie wraca do plain (krok $k)")
            assertData(m, "po przerwaniu w kroku $k")
            val marker = Migration.readMarker(m)
            assertTrue(marker == null || marker.mode == StorageMode.PLAIN || marker.mode == StorageMode.ENC, "krok $k: znacznik nie zostaje w to-*")
            // dokończenie
            if (marker?.mode != StorageMode.ENC) {
                // jak w aplikacji: najpierw krok 0 (klucze sprzątane na starcie w trybie plain są tworzone na nowo), potem migracja
                assertEquals(Migration.Prepared.OK, Migration.prepareKeys(m, wrap, "leki_kek_1", 1, TEST_DEK, marker?.attempts ?: 0))
            }
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
            val rec = Migration.recoverAfterRestart(m, cipher, ids); assertTrue(rec.writesMayResume)
            val back = rec.marker
            if (back != null) assertTrue(back.mode == StorageMode.ENC || back.mode == StorageMode.PLAIN, "wyłączanie: tryb wiążący (krok $k)")
            assertData(m, "po przerwaniu wyłączania w kroku $k")
            val marker = Migration.readMarker(m)
            assertTrue(marker == null || marker.mode == StorageMode.PLAIN || marker.mode == StorageMode.ENC, "krok $k: znacznik nie zostaje w to-*")
            if (marker?.mode == StorageMode.ENC) {   // dokończenie wyłączania
                assertEquals(Migration.Outcome.DONE, Migration.disable(m, cipher, 1, ids, marker))
            } else Migration.recoverAfterRestart(m, cipher, ids)   // znacznik plain: sprzątanie na starcie dokańcza kasowanie .enc i kluczy
            assertEquals(StorageMode.PLAIN, Migration.readMarker(m)!!.mode)
            assertData(m, "po dokończeniu wyłączania (krok $k)")
            assertTrue(ids.none { StorageNames.encFile(it) in m.files } && StorageNames.KEYS !in m.files && StorageNames.KEYS2 !in m.files, "krok $k: .enc i klucze usunięte po dokończeniu")
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
        assertTrue(Migration.recoverAfterRestart(m, noKey, ids).writesMayResume)
        assertTrue(StorageNames.encFile("p1") in m.files, "błąd klucza nie jest powodem do skasowania")
        assertEquals(StorageMode.PLAIN, Migration.readMarker(m)!!.mode)
    }

    // ---------- krok 0: przygotowanie kluczy ----------

    @Test fun killAtEveryStepOfKeyPreparationLeavesAValidPlainMarkerEvenWithLeftoverKeys() {
        fun dirWithLeftoverKeys() = MemOps().also { m ->
            data.forEach { (id, b) -> m.files[StorageNames.plainFile(id)] = b }
            m.files[StorageNames.KEYS] = KeyFile.encode(wrap, "leki_kek_1", 1, TEST_DEK)   // pozostałość po wcześniejszej próbie z innym dekId
            m.files[StorageNames.KEYS2] = KeyFile.encode(wrap, "leki_kek_1", 1, TEST_DEK)
            m.files[StorageNames.MARKER] = StorageMarker(StorageMode.PLAIN, 1).toJson().toByteArray()
        }
        val total = dirWithLeftoverKeys().let { m -> val k = KillingOps(m, Int.MAX_VALUE); assertEquals(Migration.Prepared.OK, Migration.prepareKeys(k, wrap, "leki_kek_1", 2, TEST_DEK, 0)); k.ops }
        assertEquals(3, total, "znacznik, keys.bin, keys.bin.2")
        for (k in 1..total + 1) {
            val m = dirWithLeftoverKeys()
            try { Migration.prepareKeys(KillingOps(m, k), wrap, "leki_kek_1", 2, TEST_DEK, 0) } catch (e: Killed) {}
            val r = read(m)
            assertNull(r.problem, "krok $k"); assertFalse(r.readOnly, "krok $k")
            assertContentEquals(data["p1"], r.persons["p1"]!!.plain)
            assertEquals(Migration.Prepared.OK, Migration.prepareKeys(m, wrap, "leki_kek_1", 2, TEST_DEK, 0), "ponowienie po kroku $k")
            assertEquals(StorageMarker(StorageMode.PLAIN, 2, 0), Migration.readMarker(m))
        }
    }

    // ---------- sprzątanie jawnych resztek w trybie enc ----------

    @Test fun plainLeftoversAreRemovedOnlyWhenTheMatchingEncAuthenticates() {
        val total = plainDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.enable(k, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)); k.ops }
        for (k in 1..total + 1) {
            val m = plainDir()
            try { Migration.enable(KillingOps(m, k), cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) } catch (e: Killed) {}
            if (Migration.readMarker(m)?.mode != StorageMode.ENC) continue     // przed zatwierdzeniem jawny plik jest wiążący
            Migration.cleanupPlainLeftovers(m, cipher, ids)
            assertTrue(ids.none { StorageNames.plainFile(it) in m.files || StorageNames.plainFile(it) + ".bak" in m.files }, "krok $k: jawne pliki nie zostają")
            assertFalse(Profiles.BEFORE_PEOPLE in m.files)
            assertData(m, "po sprzątaniu (krok $k)")
        }
    }

    @Test fun plainFileIsKeptWhenItsEncCannotBeAuthenticatedOrIsMissing() {
        val m = plainDir(); Migration.enable(m, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1))
        m.files[StorageNames.plainFile("p1")] = data.getValue("p1")                       // resztka
        m.files[StorageNames.plainFile("p2")] = data.getValue("p2"); m.files.remove(StorageNames.encFile("p2"))   // osoba bez .enc
        val noKey = object : DataCipher by cipher { override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = throw KeyUnavailableException("x") }
        assertEquals(0, Migration.cleanupPlainLeftovers(m, noKey, ids), "błąd klucza")
        assertEquals(1, Migration.cleanupPlainLeftovers(m, cipher, ids), "tylko p1")
        assertTrue(StorageNames.plainFile("p2") in m.files && StorageNames.plainFile("p1") !in m.files)
    }

    @Test fun cleanupDoesNothingOutsideEncMode() {
        val m = plainDir()
        assertEquals(0, Migration.cleanupPlainLeftovers(m, cipher, ids))
        assertTrue(StorageNames.plainFile("p1") in m.files)
    }

    // ---------- cofanie wyłączania, weryfikacja, błąd znacznika ----------

    @Test fun rollbackOfDisablingKeepsPlainFileOfAPersonWithoutEnc() {
        val m = plainDir(); Migration.enable(m, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1))
        m.files.remove(StorageNames.encFile("p2"))
        m.files[StorageNames.plainFile("p2")] = data.getValue("p2")                       // fallback StorageReader: osoba bez .enc
        m.files[StorageNames.plainFile("p1")] = data.getValue("p1")                       // kandydat po przerwanym wyłączaniu
        Migration.writeMarker(m, StorageMarker(StorageMode.TO_PLAIN, 1, 0))
        assertTrue(Migration.recoverAfterRestart(m, cipher, ids).writesMayResume)
        assertTrue(StorageNames.plainFile("p2") in m.files, "plik osoby bez .enc zostaje")
        assertFalse(StorageNames.plainFile("p1") in m.files, "kandydat osoby z czytelnym .enc jest usunięty")
        assertEquals(StorageMode.ENC, Migration.readMarker(m)!!.mode)
    }

    @Test fun disableVerificationFailureIsHandledLikeInEnable() {
        val m = plainDir(); Migration.enable(m, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1))
        var calls = 0
        val failsOnVerify = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray {
                calls++
                if (calls > ids.size) throw AuthFailedException()   // czytanie przeszło, weryfikacja pada
                return cipher.decrypt(iv, body, aad)
            }
        }
        assertEquals(Migration.Outcome.ROLLED_BACK, Migration.disable(m, failsOnVerify, 1, ids, StorageMarker(StorageMode.ENC, 1)))
        assertEquals(StorageMode.ENC, Migration.readMarker(m)!!.mode); assertEquals(1, Migration.readMarker(m)!!.attempts)
        assertData(m, "po nieudanej weryfikacji wyłączania")
        assertEquals(ids.size, Migration.cleanupPlainLeftovers(m, cipher, ids), "resztki po nieudanym wyłączaniu sprzątane przy następnym starcie")
    }

    /** Odrzuca zapis znacznika po [failAfter] udanych zapisach znacznika. */
    private class FailingMarkerOps(private val inner: MemOps, var failMarkerWrites: Boolean = false) : FileOps {
        override fun read(name: String) = inner.read(name)
        override fun write(name: String, bytes: ByteArray) { if (failMarkerWrites && name == StorageNames.MARKER) throw java.io.IOException("brak miejsca"); inner.write(name, bytes) }
        override fun delete(name: String) = inner.delete(name)
        override fun list() = inner.list()
    }

    @Test fun markerWriteFailureOnRollbackKeepsWritesBlockedAndDeletesNothing() {
        val m = plainDir()
        Migration.writeMarker(m, StorageMarker(StorageMode.TO_ENC, 1, 0))
        m.files[StorageNames.encFile("p1")] = DataFile.seal(cipher, 1, 1, DataFile.Kind.STATE, "p1", data.getValue("p1"))
        val f = FailingMarkerOps(m, failMarkerWrites = true)
        val rec = Migration.recoverAfterRestart(f, cipher, ids)
        assertFalse(rec.writesMayResume, "zapisy zostają zablokowane")
        assertTrue(StorageNames.encFile("p1") in m.files, "kandydat nietknięty")
        assertEquals(StorageMode.TO_ENC, Migration.readMarker(m)!!.mode)
        // po usunięciu przyczyny kolejna próba kończy cofanie
        f.failMarkerWrites = false
        assertTrue(Migration.recoverAfterRestart(f, cipher, ids).writesMayResume)
        assertEquals(StorageMode.PLAIN, Migration.readMarker(m)!!.mode)
    }

    @Test fun enableReportsRollbackFailedWhenTheMarkerCannotBeWritten() {
        val m = plainDir(); val f = FailingMarkerOps(m, failMarkerWrites = true)
        assertEquals(Migration.Outcome.ROLLBACK_FAILED, Migration.enable(f, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)))
        assertEquals(StorageMode.PLAIN, Migration.readMarker(m)!!.mode)
        assertData(m, "nic nie zmieniono")
    }

    @Test fun killDuringRollbackAndDuringRecoveryIsIdempotent() {
        fun stuck() = plainDir().also { m ->
            Migration.writeMarker(m, StorageMarker(StorageMode.TO_ENC, 1, 0))
            ids.forEach { m.files[StorageNames.encFile(it)] = DataFile.seal(cipher, 1, 1, DataFile.Kind.STATE, it, data.getValue(it)) }
        }
        val total = stuck().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.recoverAfterRestart(k, cipher, ids); k.ops }
        for (k in 1..total + 1) {
            val m = stuck()
            try { Migration.recoverAfterRestart(KillingOps(m, k), cipher, ids) } catch (e: Killed) {}
            assertData(m, "po przerwaniu odzyskiwania w kroku $k")
            val rec = Migration.recoverAfterRestart(m, cipher, ids)        // kolejny start dokańcza
            assertTrue(rec.writesMayResume)
            val marker = Migration.readMarker(m)!!
            assertEquals(StorageMode.PLAIN, marker.mode, "krok $k")
            assertTrue(marker.attempts in 1..3)
            assertTrue(ids.none { StorageNames.encFile(it) in m.files }, "krok $k: kandydaci usunięci")
            assertData(m, "po dokończeniu odzyskiwania $k")
        }
    }

    @Test fun killDuringRollbackInsideEnableKeepsDataAndRecovers() {
        val lying = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray) = cipher.decrypt(iv, body, aad).also { it[0] = 'X'.code.toByte() }
        }
        val total = plainDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.enable(k, lying, 1, ids, StorageMarker(StorageMode.PLAIN, 1)); k.ops }
        for (k in 1..total + 1) {
            val m = plainDir()
            try { Migration.enable(KillingOps(m, k), lying, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) } catch (e: Killed) {}
            assertTrue(Migration.recoverAfterRestart(m, cipher, ids).writesMayResume)
            assertData(m, "krok $k")
            assertTrue(Migration.readMarker(m)?.mode in listOf(null, StorageMode.PLAIN), "krok $k")
        }
    }

    // ---------- guard prepareKeys, powtórzenie kroku 0 po restarcie, dekId z świeżego znacznika ----------

    @Test fun prepareKeysIsRefusedUnlessMarkerIsPlainOrMissingAndThereAreNoEncFiles() {
        fun bare() = MemOps().also { m -> data.forEach { (id, b) -> m.files[StorageNames.plainFile(id)] = b } }
        fun prep(m: MemOps) = Migration.prepareKeys(m, wrap, "leki_kek_1", 1, TEST_DEK, 0)
        assertEquals(Migration.Prepared.OK, prep(bare()), "brak znacznika")
        assertEquals(Migration.Prepared.OK, prep(bare().also { Migration.writeMarker(it, StorageMarker(StorageMode.PLAIN, 5)) }))
        for (mode in listOf(StorageMode.ENC, StorageMode.TO_ENC, StorageMode.TO_PLAIN)) {
            val m = bare().also { Migration.writeMarker(it, StorageMarker(mode, 1)) }
            assertEquals(Migration.Prepared.NOT_ALLOWED, prep(m), mode.id)
            assertTrue(StorageNames.KEYS !in m.files, "nic nie zapisano")
        }
        val broken = bare().also { it.files[StorageNames.MARKER] = "{".toByteArray() }
        assertEquals(Migration.Prepared.NOT_ALLOWED, prep(broken)); assertEquals("{", String(broken.files[StorageNames.MARKER]!!))
        val withEnc = bare().also { it.files["state.enc"] = ByteArray(40) }
        assertEquals(Migration.Prepared.NOT_ALLOWED, prep(withEnc)); assertTrue(StorageNames.KEYS !in withEnc.files)
    }

    @Test fun restartBetweenPrepareKeysAndEnableNeverUsesTheDekFromMemory() {
        val m = plainDir().also { it.files.remove(StorageNames.KEYS); it.files.remove(StorageNames.KEYS2); it.files.remove(StorageNames.MARKER) }
        val dekA = ByteArray(32) { 1 }; val dekB = ByteArray(32) { 2 }
        assertEquals(Migration.Prepared.OK, Migration.prepareKeys(m, wrap, "leki_kek_1", 1, dekA, 0))
        // restart: start aplikacji sprząta klucze w trybie plain, bo nie ma jeszcze .enc
        Migration.recoverAfterRestart(m, SoftCipher(dekA), ids)
        assertTrue(StorageNames.KEYS !in m.files && StorageNames.KEYS2 !in m.files)
        // Store powtarza krok 0 z NOWYM DEK i szyfruje nim
        assertEquals(Migration.Outcome.DONE, Migration.enableWithKeys(m, wrap, { SoftCipher(it) }, "leki_kek_1", 1, dekB, ids))
        val r = StorageReader.read(m, wrap, { SoftCipher(it) }, ids)
        assertNull(r.problem)
        data.forEach { (id, b) -> assertContentEquals(b, r.persons.getValue(id).plain) }
        assertContentEquals(dekB, KeyFile.unwrap(wrap, KeyFile.decode(m.files[StorageNames.KEYS]!!)!!))
        assertFailsWith<AuthFailedException> { DataFile.open(SoftCipher(dekA), m.files["state.enc"]!!, DataFile.Kind.STATE, "p1") }
    }

    @Test fun enableWithKeysReportsNotAllowedAndKeysFailed() {
        val enc = plainDir().also { Migration.enable(it, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) }
        assertEquals(Migration.Outcome.NOT_ALLOWED, Migration.enableWithKeys(enc, wrap, { SoftCipher(it) }, "leki_kek_1", 1, TEST_DEK, ids))
        val noAlias = MemOps().also { m -> data.forEach { (id, b) -> m.files[StorageNames.plainFile(id)] = b } }
        val broken = object : KeyWrap by wrap { override fun unwrap(alias: String, iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = ByteArray(32) }
        assertEquals(Migration.Outcome.KEYS_FAILED, Migration.enableWithKeys(noAlias, broken, { SoftCipher(it) }, "leki_kek_1", 1, TEST_DEK, ids))
        assertTrue(StorageNames.encFile("p1") !in noAlias.files, "migracji nie zaczęto")
    }

    @Test fun cleanupUsesTheDekIdOfTheFreshlyReadMarker() {
        val m = plainDir().also { Migration.enable(it, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) }
        m.files[StorageNames.plainFile("p1")] = data.getValue("p1")
        // znacznik wskazuje inny dekId niż nagłówki plików: sprzątanie niczego nie usuwa (DekMismatch), niezależnie od innych argumentów
        Migration.writeMarker(m, StorageMarker(StorageMode.ENC, 2, 0))
        assertEquals(0, Migration.cleanupPlainLeftovers(m, cipher, ids))
        Migration.writeMarker(m, StorageMarker(StorageMode.ENC, 1, 0))
        assertEquals(1, Migration.cleanupPlainLeftovers(m, cipher, ids))
    }

    @Test fun beforePeopleBackupIsRemovedOnlyAfterASuccessfulMigration() {
        // udane włączanie: zapas sprzed osób znika
        val ok = plainDir(); assertTrue(Profiles.BEFORE_PEOPLE in ok.files)
        assertEquals(Migration.Outcome.DONE, Migration.enable(ok, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)))
        assertFalse(Profiles.BEFORE_PEOPLE in ok.files)
        // nieudana weryfikacja i cofnięcie: zapas zostaje
        val bad = plainDir()
        val lying = object : DataCipher by cipher {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray) = cipher.decrypt(iv, body, aad).also { it[0] = 'X'.code.toByte() }
        }
        assertEquals(Migration.Outcome.ROLLED_BACK, Migration.enable(bad, lying, 1, ids, StorageMarker(StorageMode.PLAIN, 1)))
        assertTrue(Profiles.BEFORE_PEOPLE in bad.files, "po cofnięciu zapas zostaje")
        // przerwanie przed zatwierdzeniem: zapas zostaje, po zatwierdzeniu i sprzątaniu znika
        val total = plainDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); Migration.enable(k, cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)); k.ops }
        for (k in 1..total + 1) {
            val m = plainDir()
            try { Migration.enable(KillingOps(m, k), cipher, 1, ids, StorageMarker(StorageMode.PLAIN, 1)) } catch (e: Killed) {}
            val mode = Migration.readMarker(m)?.mode
            if (mode != StorageMode.ENC) assertTrue(Profiles.BEFORE_PEOPLE in m.files, "krok $k: przed zatwierdzeniem zapas zostaje")
            else { Migration.cleanupPlainLeftovers(m, cipher, ids); assertFalse(Profiles.BEFORE_PEOPLE in m.files, "krok $k: po zatwierdzeniu znika") }
        }
    }
}

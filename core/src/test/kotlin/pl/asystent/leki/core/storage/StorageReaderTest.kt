package pl.asystent.leki.core.storage

import pl.asystent.leki.core.Profiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageReaderTest {
    private val ids = listOf("p1", "p2")
    private val cipher = SoftCipher(TEST_DEK)
    private val data = mapOf("p1" to """{"patient":"Jan Testowy"}""".toByteArray(), "p2" to """{"patient":"Ewa Testowa"}""".toByteArray())
    private fun wrap() = FakeKeyWrap().also { it.add("leki_kek_1") }
    private fun encDir(dekId: Int = 1, marker: StorageMarker? = StorageMarker(StorageMode.ENC, 1)): MemOps = MemOps().also { m ->
        data.forEach { (id, b) -> m.files[StorageNames.encFile(id)] = DataFile.seal(cipher, dekId, 1, DataFile.Kind.STATE, id, b) }
        val keys = KeyFile.encode(wrap(), "leki_kek_1", 1, TEST_DEK)
        m.files[StorageNames.KEYS] = keys; m.files[StorageNames.KEYS2] = keys
        if (marker != null) m.files[StorageNames.MARKER] = marker.toJson().toByteArray()
    }
    private fun read(ops: FileOps, w: KeyWrap = wrap()) = StorageReader.read(ops, w, { SoftCipher(it) }, ids)

    @Test fun freshInstallationReadsPlainFilesWithoutKeys() {
        val m = MemOps(linkedMapOf("state.json" to data.getValue("p1")))
        val r = read(m)
        assertIs<MarkerState.FreshPlain>(r.marker); assertContentEquals(data["p1"], r.persons["p1"]!!.plain); assertNull(r.persons["p2"]!!.plain); assertNull(r.problem)
    }

    @Test fun encModeReadsDataAndNeverWrites() {
        val m = encDir(); val before = m.writes
        val r = read(m)
        assertNull(r.problem); assertFalse(r.readOnly)
        data.forEach { (id, b) -> assertContentEquals(b, r.persons.getValue(id).plain) }
        assertEquals(before, m.writes, "odczyt (też w tle) niczego nie zapisuje")
    }

    @Test fun missingMarkerWithEncFilesIsReadOnlyEncNotPlain() {
        val m = encDir(marker = null)
        m.files[StorageNames.plainFile("p1")] = """{"stare":true}""".toByteArray()
        val r = read(m); val w = m.writes
        assertTrue(r.readOnly); assertEquals(StorageProblem.MARKER_INVALID, r.problem)
        assertContentEquals(data["p1"], r.persons["p1"]!!.plain, "czyta .enc, a nie jawny")
        assertEquals(w, m.writes)
    }

    @Test fun markerWithAnyMatchingKeyCopyIsValidEvenIfFirstCopyHasNewDekId() {
        val m = encDir()
        m.files[StorageNames.KEYS] = KeyFile.encode(wrap().also { it.add("leki_kek_2") }, "leki_kek_2", 2, ByteArray(32) { 5 })
        val r = read(m, FakeKeyWrap(wrap().aliases.also { it["leki_kek_2"] = ByteArray(32) { 6 } }))
        assertIs<MarkerState.Valid>(r.marker); assertNull(r.problem)
        data.forEach { (id, b) -> assertContentEquals(b, r.persons.getValue(id).plain) }
    }

    @Test fun classifiesProblemsSeparately() {
        // B: brak aliasu
        assertEquals(StorageProblem.KEY_ALIAS_MISSING, read(encDir(), FakeKeyWrap()).problem)
        // F: obie kopie keys.bin uszkodzone
        encDir().also { it.files[StorageNames.KEYS] = ByteArray(5); it.files[StorageNames.KEYS2] = "śmieci".toByteArray() }.let { assertEquals(StorageProblem.KEYS_FILE_BROKEN, read(it).problem) }
        // F: kopie czytelne, ale szyfrogram uszkodzony
        encDir().also { m -> for (n in listOf(StorageNames.KEYS, StorageNames.KEYS2)) m.files[n] = m.files[n]!!.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } }.let { assertEquals(StorageProblem.KEYS_FILE_BROKEN, read(it).problem) }
        // E: zmieniony bajt treści przy działającym kluczu
        encDir().also { m -> val b = m.files["state.enc"]!!; b[b.size - 1] = (b[b.size - 1] + 1).toByte() }.let { m ->
            val x = read(m)
            assertEquals(StorageProblem.CONTENT_AUTH_FAILED, x.persons["p1"]!!.problem); assertNull(x.persons["p2"]!!.problem)
        }
        // C: ucięty plik
        encDir().also { it.files["state.enc"] = it.files["state.enc"]!!.copyOf(15) }.let { assertEquals(StorageProblem.FILE_CORRUPT, read(it).persons["p1"]!!.problem) }
        // D: nowsza wersja
        encDir().also { it.files["state.enc"] = it.files["state.enc"]!!.also { b -> b[4] = 9 } }.let { assertEquals(StorageProblem.NEWER_VERSION, read(it).persons["p1"]!!.problem) }
        // inny dekId w nagłówku pliku
        encDir(dekId = 2).let { assertEquals(StorageProblem.DEK_ID_MISMATCH, read(it).persons["p1"]!!.problem) }
        // pierwszeństwo: F ponad nieważnym znacznikiem
        encDir(marker = null).also { it.files[StorageNames.KEYS] = ByteArray(3); it.files[StorageNames.KEYS2] = ByteArray(3) }.let { assertEquals(StorageProblem.KEYS_FILE_BROKEN, read(it).problem) }
    }

    @Test fun everyProblemClassHasItsOwnMessage() {
        assertEquals(StorageProblem.values().size, StorageProblem.values().map { it.message }.toSet().size)
    }

    @Test fun plainFallbackWhenEncFileIsMissingInEncMode() {
        val m = encDir().also { it.files.remove("state-p2.enc"); it.files["state-p2.json"] = data.getValue("p2") }
        val p = read(m).persons["p2"]!!
        assertTrue(p.fromPlainFallback); assertContentEquals(data["p2"], p.plain)
    }

    @Test fun oversizedMarkerIsInvalid() {
        val m = encDir().also { it.files[StorageNames.MARKER] = ByteArray(StorageMarker.MAX_CHARS * 4 + 1) { ' '.code.toByte() } }
        assertTrue(read(m).readOnly)
    }

    @Test fun oversizedPersonFileIsCorruptNotAnEmptyPerson() {
        val m = encDir().also { it.files["state.enc"] = ByteArray(DataFile.MAX_FILE_BYTES + 1) }
        assertEquals(StorageProblem.FILE_CORRUPT, read(m).persons["p1"]!!.problem)
        val plain = MemOps(linkedMapOf("state.json" to ByteArray(DataFile.MAX_FILE_BYTES + 1) { '{'.code.toByte() }))
        assertEquals(StorageProblem.FILE_CORRUPT, read(plain).persons["p1"]!!.problem)
    }

    @Test fun resultExposesCipherAndDekIdForWritingInEncMode() {
        val r = read(encDir())
        assertEquals(1, r.dekId); assertTrue(r.cipher != null)
        assertNull(read(MemOps()).cipher)
    }
}

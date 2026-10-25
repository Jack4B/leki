package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    // ---------- wykonanie „Zaczynam od nowa” ----------

    private val ids = listOf("p1", "p2")
    private fun stuckDir(): MemOps {
        val oldWrap = FakeKeyWrap().also { it.add("leki_kek_1") }
        val cipher = SoftCipher(TEST_DEK)
        return MemOps().also { m ->
            ids.forEach { m.files[StorageNames.encFile(it)] = DataFile.seal(cipher, 1, 3, DataFile.Kind.STATE, it, """{"x":"$it"}""".toByteArray()) }
            m.files[StorageNames.KEYS] = KeyFile.encode(oldWrap, "leki_kek_1", 1, TEST_DEK); m.files[StorageNames.KEYS2] = m.files[StorageNames.KEYS]!!
            m.files[StorageNames.MARKER] = StorageMarker(StorageMode.ENC, 1).toJson().toByteArray()
        }
    }
    private val newDek = ByteArray(32) { (100 + it).toByte() }

    @Test fun executeKillAtEveryStepIsResumableAndBlocksWritesWhilePending() {
        fun run(ops: FileOps, wrap: FakeKeyWrap) = StartOver.execute(ops, wrap, { wrap.add(it) }, wrap.aliases.keys, newDek, 1, ids)
        val total = stuckDir().let { m -> val k = KillingOps(m, Int.MAX_VALUE); run(k, FakeKeyWrap(linkedMapOf("leki_kek_1" to ByteArray(32)))); k.ops }
        for (k in 1..total + 1) {
            val m = stuckDir(); val wrap = FakeKeyWrap().also { it.add("leki_kek_1") }
            try { run(KillingOps(m, k), wrap) } catch (e: Killed) {
                // przerwanie przed zapisem pliku postępu nie zmieniło jeszcze niczego; po każdej zmianie plik istnieje i blokuje zapisy
                val changed = StorageNames.encFile("p1") !in m.files || StorageNames.unreadableFile("p1") in m.files || m.files[StorageNames.KEYS]?.let { KeyFile.decode(it)?.dekId } != 1
                if (changed) assertTrue(StartOver.writesBlocked(m.files[StartOver.PENDING_FILE]?.let { String(it) }), "krok $k: zapisy zablokowane po przerwaniu")
            }
            val r = run(m, wrap)!!                                      // wznowienie po restarcie
            assertEquals(2, r.dekId, "krok $k"); assertContentEquals(newDek, r.dek)
            assertFalse(StartOver.PENDING_FILE in m.files, "krok $k: plik postępu usunięty na końcu")
            assertEquals(StorageMarker(StorageMode.ENC, 2, 0), Migration.readMarker(m))
            assertTrue(StorageNames.KEYS in m.files && StorageNames.KEYS2 in m.files)
            assertTrue(ids.all { StorageNames.encFile(it) !in m.files && StorageNames.unreadableFile(it) in m.files }, "stare pliki odłożone jako nieczytelne")
            val read = StorageReader.read(m, wrap, { SoftCipher(it) }, ids)
            assertNull(read.problem, "krok $k"); assertTrue(read.persons.values.all { it.plain == null })
            val alias = KeyFile.decode(m.files[StorageNames.KEYS]!!)!!.alias
            assertTrue((KeyFile.aliasNumber(alias) ?: 0) >= 2 && alias in wrap.aliases, "nowy alias, nie ten sam (po przerwaniu może zostać osierocony poprzedni, sprzątany osobno)")
        }
    }

    @Test fun encryptionRelatedFilesAreWipedButPlainDataAndIndexAreNot() {
        for (n in listOf("state.enc", "state-p3.enc", "keys.bin", "keys.bin.2", "storage.json", "startover.pending", "state.nieczytelny.enc",
            "state.json.bak", "state.enc.bak", "state.json.new", "state.odrzucony.json", "state-p2.uszkodzony.enc", "state.przed-osobami.json")) assertTrue(StorageNames.isEncryptionRelated(n), n)
        for (n in listOf("state.json", "state-p2.json", "profiles.json")) assertFalse(StorageNames.isEncryptionRelated(n), n)
    }
}

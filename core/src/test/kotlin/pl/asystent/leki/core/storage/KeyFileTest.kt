package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyFileTest {
    private val dek = ByteArray(32) { (it + 3).toByte() }
    private fun wrap() = FakeKeyWrap().also { it.add("leki_kek_1"); it.add("leki_kek_2") }

    @Test fun roundTripStoresAliasAndDekId() {
        val w = wrap()
        val bytes = KeyFile.encode(w, "leki_kek_1", 3, dek)
        val h = KeyFile.decode(bytes)!!
        assertEquals("leki_kek_1", h.alias); assertEquals(3, h.dekId)
        assertContentEquals(dek, KeyFile.unwrap(w, h))
    }

    @Test fun structuralDamageIsNullNotException() {
        val bytes = KeyFile.encode(wrap(), "leki_kek_1", 1, dek)
        assertNull(KeyFile.decode(ByteArray(0)))
        assertNull(KeyFile.decode(bytes.copyOf(10)))
        assertNull(KeyFile.decode(bytes.also { it[0] = 0 }.copyOf()))
        assertNull(KeyFile.decode(KeyFile.encode(wrap(), "leki_kek_1", 1, dek).also { it[4] = 9 }))
    }

    @Test fun changedHeaderOrBodyFailsAuthentication() {
        val w = wrap()
        val good = KeyFile.encode(w, "leki_kek_1", 1, dek)
        val badBody = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<AuthFailedException> { KeyFile.unwrap(w, KeyFile.decode(badBody)!!) }
        val badDek = good.copyOf().also { it[5] = 2 }   // dekId jest w AAD
        assertFailsWith<AuthFailedException> { KeyFile.unwrap(w, KeyFile.decode(badDek)!!) }
    }

    @Test fun missingAliasIsKeyErrorNotAuthError() {
        val bytes = KeyFile.encode(wrap(), "leki_kek_2", 1, dek)
        val e = assertFailsWith<KeyUnavailableException> { KeyFile.unwrap(FakeKeyWrap(), KeyFile.decode(bytes)!!) }
        assertEquals(true, e.aliasMissing)
    }

    @Test fun aliasNumberingUsesMaxOfKeystoreAndKeyFilesAndIgnoresOtherNames() {
        assertEquals("leki_kek_1", KeyFile.nextAlias(emptyList(), emptyList()))
        assertEquals("leki_kek_4", KeyFile.nextAlias(listOf("leki_kek_2", "inny", "leki_kek_v1", "leki_kek_03"), listOf("leki_kek_3")))
        assertEquals("leki_kek_8", KeyFile.nextAlias(listOf("leki_kek_2"), listOf("leki_kek_7")))
        assertEquals(12, KeyFile.aliasNumber("leki_kek_12")); assertNull(KeyFile.aliasNumber("leki_kek_0")); assertNull(KeyFile.aliasNumber("leki_kek_1x"))
    }

    @Test fun bindingCopyIsTheOneMatchingMarkerDekId() {
        val a = KeyHeader(2, "leki_kek_2", ByteArray(12), ByteArray(48))   // keys.bin: już nowy dekId (przerwane „Zaczynam od nowa”)
        val b = KeyHeader(1, "leki_kek_1", ByteArray(12), ByteArray(48))   // keys.bin.2: stary
        assertEquals(b, KeyFile.binding(listOf(a, b), 1))
        assertEquals(a, KeyFile.binding(listOf(a, b), 2))
        assertEquals(b, KeyFile.binding(listOf(null, b), 1))
        assertNull(KeyFile.binding(listOf(a, b), 3))
        assertNull(KeyFile.binding(listOf(null, null), 1))
    }

    @Test fun bothCopiesWithTheSameDekIdAreCandidatesInOrderAndFallbackWorks() {
        val w = wrap()
        val first = KeyFile.encode(w, "leki_kek_2", 1, dek)     // keys.bin po rotacji (nowy alias)
        val second = KeyFile.encode(w, "leki_kek_1", 1, dek)    // keys.bin.2 ze starym aliasem
        val copies = listOf(KeyFile.decode(first), KeyFile.decode(second))
        assertEquals(listOf("leki_kek_2", "leki_kek_1"), KeyFile.bindingCandidates(copies, 1).map { it.alias })
        assertEquals(0, KeyFile.unwrapBinding(w, copies, 1).usedIndex)
        // pierwsza kopia z uszkodzonym szyfrogramem (GCM): przejście na drugą
        val broken = first.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val r = KeyFile.unwrapBinding(w, listOf(KeyFile.decode(broken), KeyFile.decode(second)), 1)
        assertEquals(1, r.usedIndex); assertContentEquals(dek, r.dek)
        // pierwsza kopia z aliasem, którego nie ma w Keystore: przejście na drugą
        val w1 = FakeKeyWrap().also { it.add("leki_kek_1") }
        assertEquals(1, KeyFile.unwrapBinding(w1, copies, 1).usedIndex)
    }

    @Test fun noWorkingBindingCopyIsKeysBrokenOrKeyError() {
        val w = wrap()
        val good = KeyFile.encode(w, "leki_kek_1", 1, dek)
        val bad = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<KeyFile.KeysBrokenException> { KeyFile.unwrapBinding(w, listOf(KeyFile.decode(bad), KeyFile.decode(bad)), 1) }
        assertFailsWith<KeyFile.KeysBrokenException> { KeyFile.unwrapBinding(w, listOf(null, null), 1) }
        assertFailsWith<KeyFile.KeysBrokenException> { KeyFile.unwrapBinding(w, listOf(KeyFile.decode(good)), 2) }   // dekId niezgodny: brak kandydata
        val e = assertFailsWith<KeyUnavailableException> { KeyFile.unwrapBinding(FakeKeyWrap(), listOf(KeyFile.decode(good), KeyFile.decode(bad)), 1) }
        assertTrue(e.aliasMissing)
    }

    @Test fun garbageOversizedAndBadUtf8NeverThrow() {
        val good = KeyFile.encode(wrap(), "leki_kek_1", 1, dek)
        assertNull(KeyFile.decode(good + ByteArray(KeyFile.MAX_BYTES)), "ponad ok. 1 KB")
        val badUtf8 = good.copyOf().also { it[7] = 0xFF.toByte(); it[8] = 0xFE.toByte() }
        assertNull(KeyFile.decode(badUtf8))
        val ivOutside = good.copyOf().also { it[7 + it[6].toInt() and 0xFF] = 0xFF.toByte() }   // długość IV poza plikiem
        assertNull(KeyFile.decode(ivOutside))
        val rnd = java.util.Random(12345)
        repeat(3000) {
            val b = good.copyOf(1 + rnd.nextInt(good.size)).also { for (k in 0 until 3) it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
            KeyFile.decode(b)   // nie może rzucić wyjątku
            KeyFile.decode(ByteArray(rnd.nextInt(80)).also { rnd.nextBytes(it) })
        }
    }
}

package pl.asystent.leki.core.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataFileTest {
    private val c = SoftCipher()
    private val json = """{"app":"test","patient":"Jan Testowy"}""".toByteArray()
    private fun seal(gen: Long = 1, kind: DataFile.Kind = DataFile.Kind.STATE, person: String = "p1", raw: Boolean = false) =
        DataFile.seal(c, 1, gen, kind, person, json, raw)

    @Test fun roundTripKeepsDataDekIdGenerationAndRawFlag() {
        val o = DataFile.open(c, seal(gen = 42, raw = true), DataFile.Kind.STATE, "p1")
        assertContentEquals(json, o.plain); assertEquals(1, o.dekId); assertEquals(42L, o.generation); assertTrue(o.raw)
        assertFalse(DataFile.open(c, seal(), DataFile.Kind.STATE, "p1").raw)
    }

    @Test fun classifiesPlainEncryptedNewerAndCorrupt() {
        assertEquals(DataFile.Format.ENCRYPTED, DataFile.classify(seal()))
        assertEquals(DataFile.Format.PLAIN_JSON, DataFile.classify(json))
        assertEquals(DataFile.Format.PLAIN_JSON, DataFile.classify("﻿ \n{}".toByteArray()))
        assertEquals(DataFile.Format.CORRUPT, DataFile.classify(ByteArray(0)))
        assertEquals(DataFile.Format.CORRUPT, DataFile.classify("To nie jest kopia".toByteArray()))
        assertEquals(DataFile.Format.CORRUPT, DataFile.classify(seal().copyOf(20)), "ucięty")
        val newer = seal().also { it[4] = 2 }
        assertEquals(DataFile.Format.NEWER_VERSION, DataFile.classify(newer))
        val flags = seal().also { it[5] = 0x02 }
        assertEquals(DataFile.Format.NEWER_VERSION, DataFile.classify(flags))
        assertFailsWith<FormatException> { DataFile.open(c, newer, DataFile.Kind.STATE, "p1") }
    }

    @Test fun anyChangedByteFailsAuthentication() {
        val good = seal(gen = 7)
        // nagłówek (poza magic, wersją i flagami, które klasyfikator odrzuca wcześniej): dekId i generacja są w AAD
        for (i in listOf(6, 8, 9, 10, 11)) {
            val bad = good.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFailsWith<AuthFailedException>("bajt $i") { DataFile.open(c, bad, DataFile.Kind.STATE, "p1") }
        }
        for (i in listOf(DataFile.HEADER, DataFile.HEADER + 5, DataFile.HEADER + DataFile.IV_LEN, good.size - 1)) {
            val bad = good.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFailsWith<AuthFailedException>("bajt $i") { DataFile.open(c, bad, DataFile.Kind.STATE, "p1") }
        }
    }

    @Test fun logicalIdentifierBindsPersonAndKindButFileNameDoesNot() {
        val blob = seal(person = "p2")
        assertContentEquals(json, DataFile.open(c, blob, DataFile.Kind.STATE, "p2").plain)   // nazwa pliku nie istnieje w API: zmiana nazwy nic nie psuje
        assertFailsWith<AuthFailedException> { DataFile.open(c, blob, DataFile.Kind.STATE, "p1") }
        assertFailsWith<AuthFailedException> { DataFile.open(c, blob, DataFile.Kind.USZKODZONY, "p2") }
        assertContentEquals(json, DataFile.open(c, seal(kind = DataFile.Kind.USZKODZONY, person = "p2", raw = true), DataFile.Kind.USZKODZONY, "p2").plain)
    }

    @Test fun ivIsFreshForEveryWrite() {
        val ivs = (1..200).map { seal().copyOfRange(DataFile.HEADER, DataFile.HEADER + DataFile.IV_LEN).toList() }.toSet()
        assertEquals(200, ivs.size)
    }

    @Test fun wrongKeyIsAuthFailureAndKeyErrorsPropagate() {
        assertFailsWith<AuthFailedException> { DataFile.open(SoftCipher(ByteArray(32) { 9 }), seal(), DataFile.Kind.STATE, "p1") }
        val missing = object : DataCipher by c {
            override fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray = throw KeyUnavailableException("brak", aliasMissing = true)
        }
        val e = assertFailsWith<KeyUnavailableException> { DataFile.open(missing, seal(), DataFile.Kind.STATE, "p1") }
        assertTrue(e.aliasMissing)
    }

    @Test fun emptyAndPlainInputsAreNotOpened() {
        assertFailsWith<FormatException> { DataFile.open(c, ByteArray(0), DataFile.Kind.STATE, "p1") }
        assertFailsWith<FormatException> { DataFile.open(c, json, DataFile.Kind.STATE, "p1") }
    }
}

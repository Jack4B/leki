package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiImportTest {
    private fun dec(s: String, name: String? = null) = AiImport.decode(s.toByteArray(Charsets.UTF_8), name)

    @Test fun utf8WithBomAndCrlfIsNormalized() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# Analiza\r\n\r\nŻółć i ćma.\r\n".toByteArray(Charsets.UTF_8)
        val r = AiImport.decode(bytes, "analiza.md")
        assertEquals("# Analiza\n\nŻółć i ćma.", r.text)
        assertEquals("Analiza", r.title)
        assertFalse(r.fallbackCharset)
    }

    @Test fun utf16FilesFromWindowsAreRead() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Zażółć gęślą jaźń".toByteArray(Charsets.UTF_16LE)
        assertEquals("Zażółć gęślą jaźń", AiImport.decode(bytes).text)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Łódź".toByteArray(Charsets.UTF_16BE)
        assertEquals("Łódź", AiImport.decode(be).text)
    }

    @Test fun windows1250FileFallsBackWithPolishLetters() {
        val bytes = "Zażółć gęślą jaźń".toByteArray(java.nio.charset.Charset.forName("windows-1250"))
        val r = AiImport.decode(bytes)
        assertEquals("Zażółć gęślą jaźń", r.text)
        assertTrue(r.fallbackCharset)
    }

    @Test fun rejectsEmptyHugeBinaryAndBackups() {
        assertFailsWith<AiImport.ImportException> { AiImport.decode(ByteArray(0)) }
        assertFailsWith<AiImport.ImportException> { dec("   \n\n  ") }
        assertFailsWith<AiImport.ImportException> { AiImport.decode(ByteArray(AiImport.MAX_BYTES + 1) { 'a'.code.toByte() }) }
        assertFailsWith<AiImport.ImportException> { AiImport.decode(ByteArray(2000) { (it % 7).toByte() }) }                      // dane binarne
        assertFailsWith<AiImport.ImportException> { dec("""{"app":"asystent-lekow","version":3,"days":{}}""") }                // kopia zapasowa
        // zwykły tekst z klamrą na początku nie jest kopią
        assertEquals("{ to jest zdanie }", dec("{ to jest zdanie }").text)
    }

    @Test fun titleFromHeadingOrFileName() {
        assertEquals("Wyniki i leki", AiImport.titleOf("Wstęp\n# **Wyniki** i `leki`\n\ntreść", "x.md"))
        assertEquals("Analiza z października", AiImport.titleOf("bez nagłówka", "/storage/Analiza_z_października.md"))
        assertEquals("raport", AiImport.titleOf("bez nagłówka", "raport"))
        assertNull(AiImport.titleOf("bez nagłówka", null))
        assertNull(AiImport.titleOf("## tylko H2", ".md"))
        assertEquals(80, AiImport.titleOf("# " + "a".repeat(200), null)!!.length)
    }
}

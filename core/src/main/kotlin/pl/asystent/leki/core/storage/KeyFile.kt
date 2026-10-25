package pl.asystent.leki.core.storage

/** Owijanie DEK kluczem KEK z Keystore (w testach atrapa). IV pochodzi z implementacji. */
interface KeyWrap {
    fun wrap(alias: String, dek: ByteArray, aad: ByteArray): DataCipher.Sealed
    /** Rzuca [KeyUnavailableException] (brak aliasu / klucz niedostępny) albo [AuthFailedException]. */
    fun unwrap(alias: String, iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray
}

/** Pole nagłówka jednej kopii `keys.bin` / `keys.bin.2`. */
class KeyHeader(val dekId: Int, val alias: String, val iv: ByteArray, val body: ByteArray)

/**
 * Format `keys.bin`: `LEKK` | wersja(1) | dekId(1) | długość aliasu(1) | alias (UTF-8) | długość IV(1) | IV | AES-GCM(KEK, DEK).
 * AAD = `leki|dek|<dekId>|<alias>`. Alias bieżącego KEK jest zapisany w pliku (plan, rozdział 1).
 */
object KeyFile {
    private val MAGIC = byteArrayOf(0x4C, 0x45, 0x4B, 0x4B)   // "LEKK"
    const val VERSION = 1
    /** `keys.bin` ma ok. 100 bajtów; większy plik jest uszkodzony. */
    const val MAX_BYTES = 1024
    private val ALIAS = Regex("^leki_kek_([1-9][0-9]{0,5})$")

    fun aad(dekId: Int, alias: String): ByteArray = "leki|dek|$dekId|$alias".toByteArray(Charsets.UTF_8)

    fun encode(wrap: KeyWrap, alias: String, dekId: Int, dek: ByteArray): ByteArray {
        require(dekId in 1..255 && ALIAS.matches(alias))
        val a = alias.toByteArray(Charsets.UTF_8)
        val s = wrap.wrap(alias, dek, aad(dekId, alias))
        check(s.iv.size in 1..255)
        return MAGIC + byteArrayOf(VERSION.toByte(), dekId.toByte(), a.size.toByte()) + a + byteArrayOf(s.iv.size.toByte()) + s.iv + s.body
    }

    /** Czyta nagłówek bez deszyfrowania; null = uszkodzony strukturalnie (zła zawartość, ucięty, nieznana wersja). */
    fun decode(bytes: ByteArray): KeyHeader? {
        if (bytes.size < 8 || bytes.size > MAX_BYTES || (0 until 4).any { bytes[it] != MAGIC[it] } || (bytes[4].toInt() and 0xFF) != VERSION) return null
        val dekId = bytes[5].toInt() and 0xFF
        val aliasLen = bytes[6].toInt() and 0xFF
        var p = 7
        if (dekId < 1 || p + aliasLen + 1 > bytes.size) return null
        val alias = String(bytes, p, aliasLen, Charsets.UTF_8); p += aliasLen
        if (!ALIAS.matches(alias)) return null
        val ivLen = bytes[p].toInt() and 0xFF; p++
        if (ivLen == 0 || p + ivLen + DataFile.TAG_LEN + 1 > bytes.size) return null
        return KeyHeader(dekId, alias, bytes.copyOfRange(p, p + ivLen), bytes.copyOfRange(p + ivLen, bytes.size))
    }

    fun unwrap(wrap: KeyWrap, h: KeyHeader): ByteArray = wrap.unwrap(h.alias, h.iv, h.body, aad(h.dekId, h.alias))

    /** Numer z nazwy aliasu `leki_kek_<n>` albo null (inne nazwy są ignorowane). */
    fun aliasNumber(alias: String): Int? = ALIAS.matchEntire(alias)?.groupValues?.get(1)?.toIntOrNull()

    /** Następny alias: najwyższy numer spośród aliasów w Keystore i numerów zapisanych w kopiach `keys.bin`, plus 1 (pierwszy: `leki_kek_1`). */
    fun nextAlias(inKeystore: Collection<String>, inKeyFiles: Collection<String>): String =
        "leki_kek_" + ((inKeystore + inKeyFiles).mapNotNull { aliasNumber(it) }.maxOrNull() ?: 0).plus(1)

    /** Obie kopie (w kolejności `keys.bin`, `keys.bin.2`), których `dekId` zgadza się ze znacznikiem; niezgodne służą tylko diagnostyce. */
    fun bindingCandidates(copies: List<KeyHeader?>, markerDekId: Int): List<KeyHeader> = copies.filterNotNull().filter { it.dekId == markerDekId }

    class Unwrapped(val dek: ByteArray, val dekId: Int, val usedIndex: Int)

    /** Wszystkie wiążące kopie są nieczytelne albo nie dają się rozpakować działającym KEK: klasa F „keys.bin uszkodzony”. */
    class KeysBrokenException : Exception("keys.bin uszkodzony")

    /**
     * Rozpakowuje DEK pierwszą kopią wiążącą; przy błędzie GCM albo klucza przechodzi na drugą. Gdy żadna nie działa: błąd klucza Keystore
     * (brak aliasu lub klucz niedostępny, klasy B/A) ma pierwszeństwo, w przeciwnym razie [KeysBrokenException] (F).
     */
    fun unwrapBinding(wrap: KeyWrap, copies: List<KeyHeader?>, markerDekId: Int): Unwrapped {
        val candidates = bindingCandidates(copies, markerDekId)
        var keyError: KeyUnavailableException? = null
        for ((i, h) in candidates.withIndex()) {
            try { return Unwrapped(unwrap(wrap, h), h.dekId, i) }
            catch (e: KeyUnavailableException) { if (keyError == null) keyError = e }
            catch (e: AuthFailedException) { /* spróbuj następnej kopii */ }
        }
        throw keyError ?: KeysBrokenException()
    }

    /** Wiążąca jest czytelna kopia o `dekId` zgodnym ze znacznikiem (pierwsza w kolejności); niezgodna służy tylko diagnostyce. */
    fun binding(copies: List<KeyHeader?>, markerDekId: Int): KeyHeader? = copies.firstOrNull { it != null && it.dekId == markerDekId }
}

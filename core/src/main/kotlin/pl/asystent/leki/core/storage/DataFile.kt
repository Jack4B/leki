package pl.asystent.leki.core.storage

/** Błąd klucza: brak aliasu w Keystore ([aliasMissing]) albo klucz niedostępny/unieważniony. Nigdy nie jest powodem do skasowania pliku. */
class KeyUnavailableException(message: String, val aliasMissing: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/** Nie przeszło uwierzytelnienie GCM (zła treść, zły nagłówek, zła osoba albo zły klucz). */
class AuthFailedException(message: String = "Uwierzytelnienie nie powiodło się", cause: Throwable? = null) : Exception(message, cause)

/** Plik zapisała nowsza wersja aplikacji (klasa D); nie ruszamy go. */
class NewerVersionException : Exception("nowsza wersja")

/** `dekId` w nagłówku pliku nie zgadza się z oczekiwanym (znacznik / `keys.bin`): osobna klasa, nie błąd uwierzytelnienia. */
class DekMismatchException(val found: Int, val expected: Int) : Exception("dekId $found zamiast $expected")

/** Plik jest strukturalnie uszkodzony (zły nagłówek, ucięty, nie jest ani JSON-em, ani kopertą). */
class FormatException(message: String) : Exception(message)

/**
 * Szyfr danych (DEK). IV pochodzi wyłącznie z implementacji (`cipher.iv`), wywołujący nigdy go nie podaje.
 * W aplikacji to `javax.crypto` z kluczem DEK, w testach zwykły AES-GCM.
 */
interface DataCipher {
    class Sealed(val iv: ByteArray, val body: ByteArray)
    fun encrypt(plain: ByteArray, aad: ByteArray): Sealed
    /** Rzuca [AuthFailedException] albo [KeyUnavailableException]. */
    fun decrypt(iv: ByteArray, body: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Format zaszyfrowanego pliku danych (plan: docs/plan-szyfrowanie.md, rozdział 2):
 * `LEKI` | wersja(1) | flagi(1) | dekId(1) | długość IV(1) | generacja(4, big endian) | IV(12) | szyfrogram + znacznik GCM.
 * AAD = pierwsze 12 bajtów nagłówka + `leki|v1|<rodzaj>|<osoba>`; nazwa pliku nie wchodzi do AAD.
 */
object DataFile {
    private val MAGIC = byteArrayOf(0x4C, 0x45, 0x4B, 0x49)   // "LEKI"
    const val VERSION = 1
    const val FLAG_RAW = 0x01
    const val HEADER = 12
    const val IV_LEN = 12
    const val TAG_LEN = 16
    /** Górny limit rozmiaru pliku danych (kopia importu ma limit 20 MB, `Backup.MAX_IMPORT_BYTES`; zapas na koperty i EKG). Większy plik jest uszkodzony. */
    const val MAX_FILE_BYTES = 32 * 1024 * 1024

    enum class Kind(val id: String) { STATE("state"), USZKODZONY("uszkodzony") }

    enum class Format { PLAIN_JSON, ENCRYPTED, NEWER_VERSION, CORRUPT }

    class Opened(val plain: ByteArray, val dekId: Int, val generation: Long, val raw: Boolean)

    /** Rozpoznaje zawartość pliku: `LEKI` = zaszyfrowany, `{` (po białych znakach i BOM) = jawny JSON, reszta uszkodzona. */
    fun classify(bytes: ByteArray): Format {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_BYTES) return Format.CORRUPT
        if (bytes.size >= 4 && (0 until 4).all { bytes[it] == MAGIC[it] }) {
            if (bytes.size < HEADER) return Format.CORRUPT
            val version = bytes[4].toInt() and 0xFF
            val flags = bytes[5].toInt() and 0xFF
            if (version > VERSION || flags and FLAG_RAW.inv() and 0xFF != 0) return Format.NEWER_VERSION
            if (version < VERSION || (bytes[7].toInt() and 0xFF) != IV_LEN || bytes.size < HEADER + IV_LEN + TAG_LEN) return Format.CORRUPT
            return Format.ENCRYPTED
        }
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && (bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() || bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte())) i++
        return if (i < bytes.size && bytes[i] == '{'.code.toByte()) Format.PLAIN_JSON else Format.CORRUPT
    }

    private fun header(dekId: Int, generation: Long, raw: Boolean): ByteArray {
        require(dekId in 1..255) { "dekId poza zakresem" }
        require(generation in 0..0xFFFFFFFFL) { "generacja poza zakresem" }
        val h = ByteArray(HEADER)
        MAGIC.copyInto(h)
        h[4] = VERSION.toByte(); h[5] = (if (raw) FLAG_RAW else 0).toByte(); h[6] = dekId.toByte(); h[7] = IV_LEN.toByte()
        for (k in 0 until 4) h[8 + k] = (generation shr (8 * (3 - k))).toByte()
        return h
    }

    private fun aad(header: ByteArray, kind: Kind, person: String): ByteArray = header + "leki|v$VERSION|${kind.id}|$person".toByteArray(Charsets.UTF_8)

    fun seal(cipher: DataCipher, dekId: Int, generation: Long, kind: Kind, person: String, plain: ByteArray, raw: Boolean = false): ByteArray {
        val h = header(dekId, generation, raw)
        val s = cipher.encrypt(plain, aad(h, kind, person))
        check(s.iv.size == IV_LEN) { "Nieoczekiwana długość IV" }
        return h + s.iv + s.body
    }

    /** Odszyfrowuje plik. Rzuca [FormatException] (uszkodzony / nowsza wersja), [DekMismatchException], [AuthFailedException] albo [KeyUnavailableException]. */
    fun open(cipher: DataCipher, blob: ByteArray, kind: Kind, person: String, expectedDekId: Int? = null): Opened {
        when (classify(blob)) {
            Format.ENCRYPTED -> {}
            Format.NEWER_VERSION -> throw NewerVersionException()
            else -> throw FormatException("nie jest zaszyfrowanym plikiem danych")
        }
        val h = blob.copyOfRange(0, HEADER)
        val found = h[6].toInt() and 0xFF
        if (expectedDekId != null && found != expectedDekId) throw DekMismatchException(found, expectedDekId)
        val iv = blob.copyOfRange(HEADER, HEADER + IV_LEN)
        val body = blob.copyOfRange(HEADER + IV_LEN, blob.size)
        val plain = cipher.decrypt(iv, body, aad(h, kind, person))
        var gen = 0L
        for (k in 0 until 4) gen = (gen shl 8) or (h[8 + k].toLong() and 0xFF)
        return Opened(plain, h[6].toInt() and 0xFF, gen, (h[5].toInt() and FLAG_RAW) != 0)
    }
}

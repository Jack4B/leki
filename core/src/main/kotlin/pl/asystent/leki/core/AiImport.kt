package pl.asystent.leki.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Wczytywanie odpowiedzi AI z pliku (Markdown albo zwykły tekst): kodowanie, znaki końca linii, rozmiar i podstawowe sprawdzenie, że to naprawdę tekst. */
object AiImport {
    /** Odpowiedź AI to kilka–kilkadziesiąt tysięcy znaków; większy plik to prawie na pewno pomyłka (np. zdjęcie albo kopia danych). */
    const val MAX_BYTES = 1_000_000

    private val backupKeyRe = Regex("\"(app|days|meds|slots|bp|labs|markers)\"\\s*:")

    class ImportException(message: String) : Exception(message)

    /** [title] to propozycja tytułu (pierwszy nagłówek H1 albo nazwa pliku), [fallbackCharset] mówi, że plik nie był w UTF-8 i odczytano go jako Windows-1250. */
    data class Result(val text: String, val title: String?, val fallbackCharset: Boolean)

    fun decode(bytes: ByteArray, fileName: String? = null): Result {
        if (bytes.isEmpty()) throw ImportException("Plik jest pusty.")
        if (bytes.size > MAX_BYTES) throw ImportException("Plik jest za duży (limit ${MAX_BYTES / 1_000_000} MB). Odpowiedź AI to zwykle kilka–kilkadziesiąt tysięcy znaków.")
        var fallback = false
        val raw: String = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                // pliki zapisane w starszych edytorach Windows mają polskie znaki w Windows-1250
                fallback = true
                String(bytes, Charset.forName("windows-1250"))
            }
        }
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) throw ImportException("Plik nie zawiera tekstu.")
        // plik binarny (PDF, obraz, archiwum) zawiera znaki sterujące
        val sample = text.take(4000)
        if (sample.count { it == '\u0000' || (it < ' ' && it != '\n' && it != '\t') } * 100 > sample.length) throw ImportException("To nie jest plik tekstowy. Wybierz plik Markdown (.md) albo tekstowy (.txt) z odpowiedzią AI.")
        // kopia z obwolutą ma klucz "app", a wewnętrzny plik danych (zwarty zapis) "days"/"meds"/"slots" i numer wersji
        if (text.startsWith("{") && backupKeyRe.containsMatchIn(sample)) throw ImportException("To wygląda na kopię zapasową aplikacji, a nie na odpowiedź AI. Kopię wczytasz w Ustawienia → Kopie zapasowe i dane.")
        return Result(text, titleOf(text, fileName), fallback)
    }

    /** Pierwszy nagłówek poziomu 1 („# Tytuł”), a gdy go nie ma, nazwa pliku bez rozszerzenia; najwyżej 80 znaków. */
    fun titleOf(text: String, fileName: String?): String? {
        val h = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.replace(Regex("""[*_`#]"""), "")?.trim()
        if (!h.isNullOrEmpty()) return h.take(80)
        val f = fileName?.substringAfterLast('/')?.substringBeforeLast('.', "")?.takeIf { it.isNotBlank() } ?: fileName?.substringAfterLast('/')?.takeIf { it.isNotBlank() && '.' !in it }
        return f?.replace('_', ' ')?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
    }
}

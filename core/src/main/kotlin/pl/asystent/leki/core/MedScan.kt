package pl.asystent.leki.core

import java.text.Normalizer

/**
 * Dane leku odczytane ze zdjęcia opakowania (po OCR). To tylko propozycja do sprawdzenia: aplikacja nie ma bazy leków (działa bez internetu),
 * więc odczytuje wyłącznie to, co jest wydrukowane na opakowaniu. [names]: kandydaci na nazwę, od najbardziej prawdopodobnej (największy napis).
 */
data class MedDraft(
    val names: List<String>,
    /** Moc, np. „10 mg” albo „5 mg/ml”; pusta, gdy nie znaleziono. */
    val strength: String,
    val substance: String,
    /** Jednostka dawki w aplikacji (tabl., kaps., ml…). */
    val unit: String,
    /** Liczba sztuk w opakowaniu, gdy podano (np. 30 tabletek). */
    val count: Double?,
) {
    val name: String get() = names.firstOrNull().orEmpty()
    /** Nazwa z mocą, np. „Testowit 10 mg”, bez dublowania mocy, gdy nazwa już ją zawiera. */
    fun fullName(n: String = name): String = if (strength.isBlank() || n.contains(strength, ignoreCase = true) || n.isBlank()) n else "$n $strength"
}

object MedScan {
    /** Napisy, które na opakowaniu nie są nazwą leku. */
    private val notName = Regex(
        """(?i)tablet|kapsu|powlek|opakowan|substanc|dawkowan|zawiera|przechow|producent|odpowiedzial|wa[zż]n|serii|recept|ulotk|doust|stosowan|przed u[zż]yciem|przeczytaj|uwaga|www\.|\.pl|\.com|ean|gtin|\bkod\b|\bexp\b|\blot\b|\brp\b|wyr[oó]b|importer|data |miejsce|dzieci|zakres|podmiot|pozwolenie|nr |numer|jednostk|\bszt\b|\bml\b|\bmg\b|\bdla\b|\bna\b|\bi\b|\bz\b|\bw\b"""
    )
    private val strengthRx = Regex("""(?i)(\d{1,4}(?:[.,]\d{1,3})?)\s*(mg|µg|μg|mcg|g|j\.m\.|iu)\b(?:\s*/\s*(?:ml|g|dawk\p{L}*|tabl\p{L}*))?""")
    private val countRx = Regex("""(?i)\b(\d{1,4})\s*(tabletek|tabletki|tabl\.?|kapsułek|kapsułki|kaps\.?|saszetek|saszetki|sasz\.?|ampułek|ampułki|amp\.?|plastrów|plastry|czopków|czopki|dawek|szt\.?)(?=[\s,.;)]|$)""")
    private val volumeRx = Regex("""(?i)\b(\d{1,4})\s*ml\b""")
    private val substanceRx = Regex("""(?i)substanc\p{L}*\s+czynn\p{L}*(?:\s+leku)?\s*(?:jest|to|:)?\s*[:\-]?\s*(.*)$""")

    private fun unitFor(word: String): String = when {
        word.startsWith("tabl", true) -> "tabl."
        word.startsWith("kaps", true) -> "kaps."
        word.startsWith("sasz", true) -> "sasz."
        word.startsWith("amp", true) -> "amp."
        word.startsWith("plast", true) -> "plaster"
        word.startsWith("czop", true) -> "czop."
        word.startsWith("dawek", true) -> "dawka"
        else -> "szt."
    }

    /** WIELKIE LITERY z opakowania zamieniamy na zwykły zapis („CONCOR” → „Concor”); mieszana wielkość zostaje. */
    fun tidyName(raw: String): String {
        val t = raw.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '.', ',', ':', ';', '*', '®', '™', '©', ' ')
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return t.split(' ').joinToString(" ") { w -> if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
        return t
    }

    private fun looksLikeName(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 3..40) return false
        val letters = s.count { it.isLetter() }
        if (letters < 3 || letters < 0.6 * s.length) return false
        if (':' in s) return false
        return !notName.containsMatchIn(Normalizer.normalize(s, Normalizer.Form.NFC))
    }

    /** Odczyt z linii OCR (z położeniem i wysokością napisów, bo nazwa leku to zwykle największy napis na opakowaniu). Null, gdy nic sensownego nie znaleziono. */
    fun parse(lines: List<OcrLine>): MedDraft? {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null
        val text = usable.sortedBy { it.top }.joinToString("\n") { it.text.trim() }

        val names = usable.filter { looksLikeName(it.text) }
            .sortedWith(compareByDescending<OcrLine> { it.height }.thenBy { it.top })
            .map { tidyName(it.text) }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
            .take(4)

        val strength = strengthRx.find(text)?.value?.replace(Regex("""\s+"""), " ")?.replace(" / ", "/")?.trim().orEmpty()

        val countMatch = countRx.find(text)
        var unit = countMatch?.let { unitFor(it.groupValues[2]) }
        var count = countMatch?.groupValues?.get(1)?.toDoubleOrNull()
        if (count == null) {
            // płyny: „120 ml” to pojemność, a dawka liczona jest w ml
            val ml = volumeRx.find(text)
            val liquid = Regex("""(?i)syrop|zawiesin|roztw[oó]r|krople|sprej|aerozol|emulsj""").containsMatchIn(text)
            if (ml != null && liquid) { count = ml.groupValues[1].toDoubleOrNull(); unit = "ml" }
        }
        if (unit == null) {
            unit = when {
                Regex("""(?i)kapsu""").containsMatchIn(text) -> "kaps."
                Regex("""(?i)saszet|proszek""").containsMatchIn(text) -> "sasz."
                Regex("""(?i)plast""").containsMatchIn(text) -> "plaster"
                Regex("""(?i)syrop|krople|zawiesin|roztw[oó]r""").containsMatchIn(text) -> "ml"
                else -> "tabl."
            }
        }

        var substance = ""
        val ls = text.split('\n')
        for ((i, l) in ls.withIndex()) {
            val m = substanceRx.find(l) ?: continue
            val tail = m.groupValues[1].ifBlank { ls.getOrNull(i + 1).orEmpty() }
            substance = tail.split(',', ';', '(').first().replace(Regex("""\s+\d.*$"""), "").trim().take(60)
            if (substance.isNotEmpty()) break
        }

        if (names.isEmpty() && strength.isEmpty() && substance.isEmpty() && count == null) return null
        return MedDraft(names, strength, substance, unit, count)
    }
}

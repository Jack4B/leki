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
    /** Cały rozpoznany tekst, wiersz po wierszu (od góry): do pokazania użytkownikowi, żeby mógł wskazać właściwą nazwę, gdy odczyt się pomylił. */
    val lines: List<String> = emptyList(),
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
    private val strengthRx = Regex("""(?i)(?<![\d.,])((?:\d{1,3}(?: \d{3})+|\d{1,5})(?:[.,]\d{1,4})?)\s*(mg|µg|μg|mcg|ug|g|j\.m\.|iu)\b(?:\s*/\s*(?:ml|g|dawk\p{L}*|tabl\p{L}*))?""")
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
        // z brzegów znikają znaki interpunkcyjne i symbole (®, ™, °, który OCR czyta zamiast ®), zostają litery i cyfry
        val t = raw.replace(Regex("""\s+"""), " ").trim().trim { !it.isLetterOrDigit() }
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return t.split(' ').joinToString(" ") { w -> if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
        return t
    }

    /** Nazwa handlowa w tym samym wierszu co moc („Nazwa® 20 mg”) jest na opakowaniach typowa, więc moc i przecinki wycinamy przed oceną napisu. */
    private fun withoutStrength(t: String): String = strengthRx.replace(t, " ").replace(Regex("""\s+"""), " ").trim().trim(',', ';', ' ')

    private fun looksLikeName(t: String): Boolean {
        val s = withoutStrength(t)
        if (s.length !in 3..40) return false
        val letters = s.count { it.isLetter() }
        if (letters < 3 || letters < 0.6 * s.length) return false
        if (':' in s || ',' in s) return false
        if (s.none { it in "aeiouyąęóAEIOUYĄĘÓ" }) return false
        return !notName.containsMatchIn(Normalizer.normalize(s, Normalizer.Form.NFC))
    }

    /** Napis o prawdziwych proporcjach tekstu (szerokość proporcjonalna do liczby znaków i wysokości); wąskie, wysokie ramki to śmieci z OCR (krawędzie, kropki brajla, ręka). */
    private fun plausibleBox(l: OcrLine, shown: String): Boolean = (l.right - l.left) >= 0.22 * l.height * shown.length

    /** Nazwa substancji czynnej wydrukowana pod nazwą handlową (bez etykiety „Substancja czynna”): sól albo rzeczownik w dopełniaczu, np. „…u chlorowodorek”. */
    private val innRx = Regex("""(?i)chlorowodorek|siarczan|fumaran|maleinian|winian|bursztynian|mesylan|\bsodu\b|\bpotasu\b|\bwapnia\b|\bmagnezu\b|\b\p{L}{8,}u\b""")

    /** Tekst zaznaczony ręcznie (dotknięty napis albo obszar ze zdjęcia) wstawiony jako nazwa: bez mocy, ® i WIELKICH LITER, z mocą z opakowania dopisaną, gdy znana. */
    fun asName(raw: String, strength: String = ""): String {
        val n = tidyName(withoutStrength(raw.replace('\n', ' ')))
        return if (strength.isBlank() || n.isBlank() || n.contains(strength, ignoreCase = true)) n else "$n $strength"
    }

    /** Tekst zaznaczony ręcznie wstawiony jako substancja czynna: bez etykiety „Substancja czynna:” i bez mocy. */
    fun asSubstance(raw: String): String {
        var t = raw.replace('\n', ' ').trim()
        substanceRx.find(t)?.let { m -> t = m.groupValues[1] }
        return tidyName(withoutStrength(t)).take(60)
    }

    /** Odczyt z linii OCR (z położeniem i wysokością napisów, bo nazwa leku to zwykle największy napis na opakowaniu). Null, gdy nic sensownego nie znaleziono. */
    fun parse(lines: List<OcrLine>): MedDraft? {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return null
        val text = usable.sortedBy { it.top }.joinToString("\n") { it.text.trim() }

        // kandydaci na nazwę: wiersze z nazwą handlową (znak ® albo ™ to mocny sygnał) i największe napisy; napisy o nieprawdopodobnych proporcjach odpadają
        val ranked = usable.filter { l -> looksLikeName(l.text) && plausibleBox(l, withoutStrength(l.text)) }
            .sortedWith(compareByDescending<OcrLine> { if ('®' in it.text || '™' in it.text) 1 else 0 }.thenByDescending { it.height }.thenBy { it.top })
            .map { tidyName(withoutStrength(it.text)) }
            .filter { it.length >= 3 }
            .distinctBy { it.lowercase() }
        // wiersz z nazwą substancji (np. „… chlorowodorek”) to substancja, a nie nazwa leku
        val innLine = ranked.drop(1).firstOrNull { innRx.containsMatchIn(it) }
        val names = ranked.filter { it != innLine }.take(4)

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

        // gdy rozpoznano tylko jeden napis i wygląda na nazwę substancji (lek generyczny), służy też jako nazwa
        if (substance.isEmpty()) substance = innLine ?: names.singleOrNull()?.takeIf { innRx.containsMatchIn(it) }.orEmpty()
        if (names.isEmpty() && strength.isEmpty() && substance.isEmpty() && count == null) return null
        val shown = usable.sortedBy { it.top }.map { it.text.trim().replace(Regex("""\s+"""), " ") }.filter { it.length >= 2 }.distinct().take(30)
        return MedDraft(names, strength, substance, unit, count, shown)
    }
}

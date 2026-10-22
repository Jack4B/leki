package pl.asystent.leki.core

import java.text.Normalizer
import java.time.LocalDate

/** Linia tekstu rozpoznana na obrazie wraz z położeniem (piksele). */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** Jeden wiersz tabeli wyników odczytany ze skanu. [qualifier]: "<" albo ">" przy wynikach typu "<0,5". */
data class ScanRow(val raw: String, val name: String, val value: Double, val qualifier: String, val unit: String, val lo: Double?, val hi: Double?)

/** Propozycja zapisu jednego wyniku, którą użytkownik sprawdza i poprawia przed zapisem. */
data class Candidate(
    val index: Int,
    val raw: String,
    val rawName: String,
    /** Id parametru w aplikacji (istniejącego albo proponowanego nowego). */
    val markerId: String,
    /** Gdy parametru jeszcze nie ma w aplikacji: jak go utworzyć. */
    val proposed: Marker?,
    val value: Double,
    val qualifier: String,
    val unit: String,
    val include: Boolean,
    /** Nazwy nie rozpoznano; parametr zgadnięto po jednostce i zakresie odniesienia, więc trzeba to sprawdzić. */
    val guessed: Boolean = false,
)

enum class WarningKind { Unit, Duplicate, Jump, Qualifier, Guess }
data class ScanWarning(val kind: WarningKind, val text: String) {
    /** Ostrzeżenia, przy których wynik domyślnie nie jest zapisywany. */
    val blocking: Boolean get() = kind == WarningKind.Unit || kind == WarningKind.Duplicate || kind == WarningKind.Guess
}

/** Rozpoznawanie wyników badań z tekstu OCR: składanie wierszy tabeli, odczyt wartości, jednostek i zakresów, dopasowanie do parametrów. */
object LabScan {
    // ---------- Wiersze z linii OCR ----------

    /** Linie o zbliżonym położeniu pionowym trafiają do jednego wiersza (kolumny tabeli), od lewej do prawej. */
    fun rows(lines: List<OcrLine>): List<String> {
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val clusters = ArrayList<MutableList<OcrLine>>()
        for (l in sorted) {
            val last = clusters.lastOrNull()
            if (last != null) {
                val cy = last.sumOf { it.centerY } / last.size
                val h = minOf(l.height, last.minOf { it.height })
                if (Math.abs(l.centerY - cy) <= 0.6 * h) { last.add(l); continue }
            }
            clusters.add(mutableListOf(l))
        }
        return clusters.map { c -> c.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }

    // ---------- Odczyt wiersza ----------

    private val numTok = Regex("""^[<>]?=?\d{1,7}(?:[.,]\d{1,4})?$""")
    private val rangeRe = Regex("""(\d{1,7}(?:[.,]\d{1,4})?)\s*-\s*(\d{1,7}(?:[.,]\d{1,4})?)""")
    private val upperRe = Regex("""(?:^|\s|\()(?:<|<=|≤|do)\s*(\d{1,7}(?:[.,]\d{1,4})?)""", RegexOption.IGNORE_CASE)
    private val lowerRe = Regex("""(?:^|\s|\()(?:>|>=|≥|od)\s*(\d{1,7}(?:[.,]\d{1,4})?)""", RegexOption.IGNORE_CASE)
    private val flagTokens = setOf("h", "l", "hh", "ll", "*", "↑", "↓", "+", "-", "(h)", "(l)", "(+)", "(-)", "!", "wysoki", "niski")
    private val noiseWords = listOf(
        "pobrania", "zlecenia", "rejestracji", "urodz", "pesel", "wydruk", "strona", "laborator", "adres", "telefon", "nr ", "numer", "kod ", "godz", "data ", "pacjent",
        "lekarz", "zleceniodawca", "wykonania", "wydania", "materia", "badanie", "wynik", "zakres", "jednostka", "norma", "referenc", "metoda", "diagnost", "alab", "synevo", "medicover",
    )

    private fun normalizeLine(s: String): String = s
        .replace(' ', ' ').replace('\t', ' ')
        .replace('−', '-').replace('–', '-').replace('—', '-')
        .replace('μ', 'µ')
        .trim()

    private val unitRe = Regex(
        """^(?:mmol/l|mmol/mol|mg/dl|mg/l|g/dl|g/l|µg/dl|µg/l|ug/dl|ng/ml|ng/dl|pg/ml|µiu/ml|uiu/ml|ulu/ml|µlu/ml|miu/l|mlu/l|iu/l|iu/ml|u/l|u/ml|ml/min(?:/1[.,]73\s?m2)?|%|fl|pg|µmol/l|umol/l|nmol/l|pmol/l|mm/h|s|sek\.?|g|mg|mm|10\^?\d+/µ?u?l|10\^?\d+/l|[a-zµ]*/µl|tys\.?/µl|tys\.?/ul|mln/µl|mln/ul|t/l|g/l|k/µl|m/µl|mosm/kg|ratio|index|inr)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Poprawia typowe pomyłki OCR w jednostkach: cyfra 1 albo kreska zamiast litery l, wielkie I zamiast l. */
    private fun fixUnit(t: String): String? {
        if (unitRe.matches(t)) return t
        val f = t.replace('1', 'l').replace('|', 'l').replace(Regex("/[iI]$"), "/l").replace("0", "o")
        return if (f != t && unitRe.matches(f)) f else null
    }

    private fun toNum(s: String): Double? = s.replace(',', '.').toDoubleOrNull()

    /** Odczytuje jeden wiersz tabeli wyników albo zwraca null, jeśli to nie wygląda na wynik badania. */
    fun parseRow(line: String): ScanRow? {
        // "< 0,5" z odstępem to ten sam znacznik co "<0,5"
        val s = normalizeLine(line).replace(Regex("""([<>]=?)\s+(?=\d)"""), "$1")
        if (s.length < 4) return null
        if (Regex("""\d{9,}""").containsMatchIn(s.replace(" ", ""))) return null
        val tokens = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val vi = tokens.indexOfFirst { numTok.matches(it) }
        if (vi < 1) return null
        val nameTokens = tokens.subList(0, vi).toMutableList()
        // przecinek lub dwukropek po nazwie
        if (nameTokens.isNotEmpty()) nameTokens[nameTokens.lastIndex] = nameTokens.last().trimEnd(':', ';', ',')
        val name = nameTokens.joinToString(" ").trim().trim('-', '.', ':')
        val letters = name.count { it.isLetter() }
        if (letters < 2 || name.length > 60) return null
        val low = name.lowercase()
        if (noiseWords.any { low.contains(it) }) return null

        val vt = tokens[vi]
        val qualifier = if (vt.startsWith("<")) "<" else if (vt.startsWith(">")) ">" else ""
        val value = toNum(vt.trimStart('<', '>', '='))?.takeIf { it.isFinite() && it < 1e7 } ?: return null

        // jednostka: następny token (po ewentualnych znacznikach H/L) albo dwa sklejone
        var j = vi + 1
        while (j < tokens.size && tokens[j].lowercase() in flagTokens) j++
        var unit = ""
        if (j < tokens.size) {
            val t1 = tokens[j]
            val t2 = if (j + 1 < tokens.size) t1 + tokens[j + 1] else null
            val f1 = fixUnit(t1)
            val f2 = t2?.let { fixUnit(it) }
            if (f1 != null) { unit = f1; j++ }
            else if (f2 != null) { unit = f2; j += 2 }
        }
        val rest = tokens.drop(j).joinToString(" ")
        var lo: Double? = null; var hi: Double? = null
        val rg = rangeRe.find(rest)
        if (rg != null) {
            val a = toNum(rg.groupValues[1]); val b = toNum(rg.groupValues[2])
            if (a != null && b != null && a < b) { lo = a; hi = b }
        } else {
            upperRe.find(rest)?.let { hi = toNum(it.groupValues[1]) }
            lowerRe.find(rest)?.let { lo = toNum(it.groupValues[1]) }
        }
        // wiersz bez jednostki i bez zakresu to zwykle nie wynik (numer, adres, data)
        if (unit.isEmpty() && lo == null && hi == null) return null
        return ScanRow(s, name, value, qualifier, unit, lo, hi)
    }

    /**
     * Odczytuje wiersze tabeli. Obsługuje też układ "nazwa w jednej linii, wynik w następnej" (częsty w aplikacjach i portalach pacjenta):
     * linia bez liczby, po której następuje linia zaczynająca się od liczby, jest łączona z następną.
     */
    fun parseRows(rows: List<String>): List<ScanRow> {
        val out = ArrayList<ScanRow>()
        var i = 0
        while (i < rows.size) {
            val r = parseRow(rows[i])
            if (r != null) { out.add(r); i++; continue }
            if (i + 1 < rows.size && looksLikeNameOnly(rows[i]) && startsWithNumber(rows[i + 1])) {
                val merged = parseRow(rows[i].trim() + "  " + rows[i + 1].trim())
                if (merged != null) { out.add(merged); i += 2; continue }
            }
            i++
        }
        return out
    }

    private val spaces = Regex("""\s+""")

    private fun looksLikeNameOnly(line: String): Boolean {
        val s = normalizeLine(line)
        if (s.length > 50 || s.count { it.isLetter() } < 2) return false
        if (s.split(spaces).any { numTok.matches(it) }) return false
        val low = s.lowercase()
        return noiseWords.none { low.contains(it) }
    }

    private fun startsWithNumber(line: String): Boolean = numTok.matches(normalizeLine(line).split(spaces).firstOrNull().orEmpty())

    // ---------- Data badania ----------

    private val dmy = Regex("""(?<!\d)(\d{1,2})[.\-/](\d{1,2})[.\-/](20\d{2})(?!\d)""")
    private val ymd = Regex("""(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)""")

    private fun datesIn(s: String): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        dmy.findAll(s).forEach { m -> runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()?.let(out::add) }
        ymd.findAll(s).forEach { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()?.let(out::add) }
        return out
    }

    /** Data pobrania materiału (preferowana), wykonania lub badania; pomija daty urodzenia i daty z przyszłości. */
    fun parseDate(rows: List<String>, today: LocalDate): LocalDate? {
        val keys = listOf("pobrania", "pobrano", "wykonania", "badania", "materiału", "materialu", "rejestracji", "data")
        fun ok(d: LocalDate) = !d.isAfter(today) && d.isAfter(today.minusYears(5))
        for (k in keys) {
            for (r in rows) {
                val low = r.lowercase()
                if (!low.contains(k) || low.contains("urodz") || low.contains("ur.") || low.contains("pesel")) continue
                datesIn(r).firstOrNull { ok(it) }?.let { return it }
            }
        }
        return rows.filter { val l = it.lowercase(); !l.contains("urodz") && !l.contains("ur.") && !l.contains("pesel") }.flatMap { datesIn(it) }.firstOrNull { ok(it) }
    }

    // ---------- Dopasowanie do parametrów ----------

    private class Known(val id: String, val name: String, val aliases: List<String>)

    private val known: List<Known> = listOf(
        Known("potas", "Potas", listOf("potas", "kalium", "k")),
        Known("sod", "Sód", listOf("sod", "natrium", "na")),
        Known("magnez", "Magnez", listOf("magnez", "magnesium", "mg")),
        Known("wapn", "Wapń", listOf("wapn", "wapn calkowity", "calcium", "ca")),
        Known("chlorki", "Chlorki", listOf("chlorki", "cl")),
        Known("fosfor", "Fosfor", listOf("fosfor", "fosforany", "fosfor nieorganiczny")),
        Known("kreatynina", "Kreatynina", listOf("kreatynina", "creatinine")),
        Known("egfr", "eGFR", listOf("egfr", "gfr", "przesaczanie klebuszkowe")),
        Known("mocznik", "Mocznik", listOf("mocznik", "urea", "azot mocznika", "bun")),
        Known("moczowy", "Kwas moczowy", listOf("kwas moczowy", "uric acid", "moczany")),
        Known("glukoza", "Glukoza", listOf("glukoza", "glikemia", "glukoza na czczo", "gluk", "glucose")),
        Known("hba1c", "HbA1c", listOf("hba1c", "hemoglobina glikowana", "hemoglobina glikozylowana")),
        Known("hemoglobina", "Hemoglobina", listOf("hemoglobina", "hgb", "hb")),
        Known("hematokryt", "Hematokryt", listOf("hematokryt", "hct")),
        Known("erytrocyty", "Erytrocyty", listOf("erytrocyty", "rbc", "czerwone krwinki")),
        Known("leukocyty", "Leukocyty", listOf("leukocyty", "wbc", "biale krwinki")),
        Known("plytki", "Płytki krwi", listOf("plytki krwi", "plytki", "plt", "trombocyty")),
        Known("mcv", "MCV", listOf("mcv")),
        Known("mch", "MCH", listOf("mch")),
        Known("mchc", "MCHC", listOf("mchc")),
        Known("rdw", "RDW", listOf("rdw", "rdw cv")),
        Known("chol", "Cholesterol całkowity", listOf("cholesterol calkowity", "cholesterol", "chol", "cholesterol total")),
        Known("hdl", "Cholesterol HDL", listOf("cholesterol hdl", "hdl cholesterol", "hdl", "hdl chol")),
        Known("ldl", "Cholesterol LDL", listOf("cholesterol ldl", "ldl cholesterol", "ldl", "ldl chol")),
        Known("tg", "Trójglicerydy", listOf("trojglicerydy", "triglicerydy", "tg", "triglycerides")),
        Known("alt", "ALT", listOf("alt", "alat", "aminotransferaza alaninowa")),
        Known("ast", "AST", listOf("ast", "aspat", "aminotransferaza asparaginianowa")),
        Known("ggtp", "GGTP", listOf("ggtp", "ggt", "gamma gt")),
        Known("bilirubina", "Bilirubina", listOf("bilirubina", "bilirubina calkowita")),
        Known("alp", "Fosfataza zasadowa", listOf("fosfataza zasadowa", "alp")),
        Known("tsh", "TSH", listOf("tsh", "tyreotropina")),
        Known("ft4", "FT4", listOf("ft4", "wolna tyroksyna")),
        Known("ft3", "FT3", listOf("ft3", "wolna trijodotyronina")),
        Known("crp", "CRP", listOf("crp", "bialko c reaktywne", "bialko c-reaktywne")),
        Known("ob", "OB", listOf("ob", "odczyn biernackiego")),
        Known("zelazo", "Żelazo", listOf("zelazo", "fe", "iron")),
        Known("ferrytyna", "Ferrytyna", listOf("ferrytyna")),
        Known("psa", "PSA", listOf("psa")),
        Known("witd", "Witamina D (25-OH)", listOf("witamina d", "25 oh witamina d", "25 oh d", "25-oh witamina d", "witamina d3 25 oh")),
        Known("witb12", "Witamina B12", listOf("witamina b12", "b12")),
    )

    /** Nazwa bez wielkich liter, polskich znaków i interpunkcji: do porównań. */
    fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase().replace('ł', 'l'), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return d.replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }

    private fun containsPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        if (phrase.isEmpty() || phrase.size > tokens.size) return false
        for (i in 0..tokens.size - phrase.size) if (tokens.subList(i, i + phrase.size) == phrase) return true
        return false
    }

    private val notSameTest = setOf("nie", "zjonizowany", "zjonizowane", "zjonizowana", "mocz", "moczu", "dobowa", "dobowej")

    /** Dopasowuje nazwę z wyniku do znanego parametru; wybiera najdłuższy pasujący alias. Zwraca id lub null. */
    fun knownId(rawName: String): String? {
        val n = norm(rawName)
        if (n.isEmpty()) return null
        val tokens = n.split(' ')
        // nazwy z kwalifikatorem ("nie HDL", "zjonizowany", "w moczu") to inne badanie niż znany parametr: nie dopasowujemy ich
        if (tokens.any { it in notSameTest }) return null
        var best: Pair<String, Int>? = null
        for (k in known) for (a in k.aliases) {
            val at = norm(a).split(' ')
            val short = a.length <= 2
            val hit = if (short) tokens.size == 1 && tokens[0] == a else containsPhrase(tokens, at)
            if (hit) { val len = at.sumOf { it.length }; if (best == null || len > best.second) best = k.id to len }
        }
        return best?.first
    }

    fun knownName(id: String): String? = known.firstOrNull { it.id == id }?.name

    private fun levenshteinClose(a: String, b: String): Boolean {
        if (Math.abs(a.length - b.length) > 1 || a.length < 6) return false
        var i = 0; var j = 0; var diff = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            diff++; if (diff > 1) return false
            if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ }
        }
        return diff + (a.length - i) + (b.length - j) <= 1
    }

    // ---------- Jednostki ----------

    private val unitAlias = mapOf(
        "uiu/ml" to "miu/l", "µiu/ml" to "miu/l", "miu/l" to "miu/l", "ulu/ml" to "miu/l", "µlu/ml" to "miu/l", "mlu/l" to "miu/l",
        "tys/µl" to "10^3/µl", "tys./µl" to "10^3/µl", "10^3/µl" to "10^3/µl", "103/µl" to "10^3/µl", "k/µl" to "10^3/µl", "10^9/l" to "10^3/µl", "g/l" to "g/l",
        "mln/µl" to "10^6/µl", "10^6/µl" to "10^6/µl", "106/µl" to "10^6/µl", "m/µl" to "10^6/µl", "10^12/l" to "10^6/µl", "t/l" to "10^6/µl",
        "ug/dl" to "µg/dl", "umol/l" to "µmol/l", "tys./ul" to "10^3/µl", "tys/ul" to "10^3/µl", "mln/ul" to "10^6/µl",
    )

    fun normUnit(u: String): String {
        val k = u.lowercase().replace(" ", "").replace('μ', 'µ').trimEnd('.')
        return unitAlias[k] ?: k
    }

    // ---------- Kandydaci do zapisu ----------

    /** Zamienia odczytane wiersze w propozycje zapisu, dopasowane do parametrów z aplikacji (a gdy brak, proponuje nowy parametr). */
    fun candidates(rows: List<ScanRow>, s: AppState): List<Candidate> {
        val used = HashSet<String>()
        return rows.mapIndexed { i, r ->
            var k = knownId(r.name)
            var guessed = false
            val n = norm(r.name)
            if (k == null && s.markers.none { norm(it.name) == n }) { k = guessByRange(r.unit, r.lo, r.hi); guessed = k != null }
            // dopasowanie "prawie takiej samej" nazwy tylko dla nazw, których nie znamy: inaczej LDL trafiałby do HDL (różnica jednej litery)
            val byName = s.markers.firstOrNull { norm(it.name) == n } ?: k?.let { s.marker(it) }
                ?: (if (k == null) s.markers.firstOrNull { levenshteinClose(norm(it.name), n) } else null)
            val existing = byName
            val c: Candidate = if (existing != null) {
                Candidate(i, r.raw, r.name, existing.id, null, r.value, r.qualifier, r.unit, true, guessed)
            } else {
                val id = k ?: ("m" + norm(r.name).replace(' ', '_').take(24).ifEmpty { newId() })
                val display = k?.let { knownName(it) } ?: r.name.trim().replaceFirstChar { ch -> ch.uppercase() }
                Candidate(i, r.raw, r.name, id, Marker(id, display, r.unit, r.lo, r.hi, Defaults.groupOf[id] ?: ""), r.value, r.qualifier, r.unit, true, guessed)
            }
            // ten sam parametr dwa razy w jednym skanie: drugi wynik domyślnie pomijamy
            val dup = !used.add(c.markerId)
            if (dup) c.copy(include = false) else c
        }
    }

    // ---------- Zgadywanie po zakresie odniesienia ----------

    private class Typical(val id: String, val unit: String, val lo: ClosedFloatingPointRange<Double>, val hi: ClosedFloatingPointRange<Double>)

    private val typical = listOf(
        Typical("potas", "mmol/l", 3.4..3.7, 5.0..5.6), Typical("sod", "mmol/l", 134.0..137.0, 144.0..147.0), Typical("magnez", "mmol/l", 0.6..0.78, 0.95..1.12),
        Typical("wapn", "mmol/l", 2.0..2.25, 2.5..2.7), Typical("chlorki", "mmol/l", 96.0..102.0, 105.0..110.0), Typical("glukoza", "mmol/l", 3.8..4.2, 5.4..6.2),
        Typical("glukoza", "mg/dl", 69.0..75.0, 98.0..111.0), Typical("kreatynina", "mg/dl", 0.5..0.75, 1.0..1.35), Typical("moczowy", "mg/dl", 2.2..3.6, 6.0..7.4),
        Typical("tsh", "miu/l", 0.25..0.5, 3.8..4.5), Typical("hemoglobina", "g/dl", 11.5..14.0, 15.0..17.6), Typical("leukocyty", "10^3/µl", 3.8..4.5, 9.5..11.0),
        Typical("plytki", "10^3/µl", 140.0..155.0, 390.0..450.0),
    )

    /** Parametr zgadnięty po jednostce i zakresie odniesienia (gdy OCR przekręcił nazwę); null, jeśli nic nie pasuje jednoznacznie. */
    fun guessByRange(unit: String, lo: Double?, hi: Double?): String? {
        if (lo == null || hi == null || unit.isEmpty()) return null
        val u = normUnit(unit)
        val hits = typical.filter { normUnit(it.unit) == u && lo in it.lo && hi in it.hi }.map { it.id }.distinct()
        return hits.singleOrNull()
    }

    // ---------- Ostrzeżenia ----------

    fun warnings(c: Candidate, s: AppState, date: LocalDate): List<ScanWarning> {
        val out = ArrayList<ScanWarning>()
        val m = s.marker(c.markerId) ?: c.proposed
        if (c.guessed) out.add(ScanWarning(WarningKind.Guess, "Nazwy \"${c.rawName}\" nie rozpoznano. Dopasowałem parametr po jednostce i zakresie odniesienia, sprawdź, czy to na pewno ${m?.name ?: "ten parametr"}."))
        if (c.qualifier.isNotEmpty()) out.add(ScanWarning(WarningKind.Qualifier, "W wyniku jest znak \"${c.qualifier}\". Zapisana zostanie sama liczba."))
        if (s.marker(c.markerId) != null && m != null && m.unit.isNotEmpty() && c.unit.isNotEmpty() && normUnit(m.unit) != normUnit(c.unit))
            out.add(ScanWarning(WarningKind.Unit, "Jednostka w skanie (${c.unit}) różni się od jednostki parametru w aplikacji (${m.unit}). Sprawdź, czy wartość jest przeliczona."))
        if (s.labs.any { it.marker == c.markerId && it.date == date })
            out.add(ScanWarning(WarningKind.Duplicate, "Wynik tego parametru z dnia ${plDate(date)} już jest zapisany. Zapisanie go zastąpi."))
        val last = s.labsOf(c.markerId).lastOrNull { it.date != date }
        if (last != null && last.value != 0.0 && c.value != 0.0) {
            val ratio = c.value / last.value
            if (ratio > 4 || ratio < 0.25) out.add(ScanWarning(WarningKind.Jump, "Duża zmiana względem poprzedniego wyniku (${lnum(last.value)}). Sprawdź, czy przecinek został dobrze odczytany."))
        }
        return out
    }

    /** Czy wynik powinien być domyślnie zaznaczony do zapisu. */
    fun defaultInclude(c: Candidate, s: AppState, date: LocalDate): Boolean = c.include && warnings(c, s, date).none { it.blocking }

    /** Zapisuje zaznaczone wyniki: tworzy brakujące parametry, a istniejący wynik z tego samego dnia zastępuje. */
    fun apply(s: AppState, date: LocalDate, items: List<Candidate>): AppState {
        var st = s
        for (c in items) {
            if (st.marker(c.markerId) == null) st = st.saveMarker(c.proposed ?: Marker(c.markerId, c.rawName, c.unit))
            val existing = st.labs.firstOrNull { it.marker == c.markerId && it.date == date }
            st = st.saveLab(Lab(existing?.id ?: newId(), date, c.markerId, c.value, "ze skanu"))
        }
        return st
    }
}

package pl.asystent.leki.core

import java.time.LocalDate

/** Wynik porządkowania tekstu odczytanego ze zdjęcia lub PDF wyniku badania: opis i wnioski w akapitach oraz (jeśli udało się ustalić) tytuł, rodzaj i data z nagłówka. */
data class ParsedStudyText(val description: String, val conclusions: String = "", val title: String = "", val kind: StudyKind? = null, val date: LocalDate? = null) {
    val isEmpty get() = description.isBlank() && conclusions.isBlank()
}

/**
 * Porządkowanie tekstu opisu badania. Odczyt (OCR) daje wiersze tak, jak są złamane na papierze, a raport ma mieć akapity: wiersze jednego akapitu są sklejane,
 * nowy akapit zaczyna się po krótkim wierszu kończącym zdanie (albo po wyraźnym odstępie na zdjęciu) i po nagłówku z dwukropkiem. Z wyniku znika nagłówek placówki
 * (wszystko przed wierszem z numerem PESEL) i podpis lekarza na końcu, a „Wnioski” trafiają do osobnego pola. Wszystko heurystyczne: osoba sprawdza tekst przed zapisem.
 */
object StudyText {
    /** Wiersz tekstu z położeniem (współrzędne zdjęcia; dla zwykłego tekstu szerokość to długość wiersza). */
    internal class Row(val text: String, val width: Int, val top: Int, val bottom: Int)

    private val SENTENCE_END = Regex("""[.!?…]["”')\]]*$""")
    private val SIGNATURE = Regex("""(?i)^\s*(lek\.?\s*med|lekarz\b|lek\.|specjalista|dr\s|prof\.|podpis|pieczęć|pieczątka|prawo wykonywania)|(?<!\d)\d{7}(?!\d)""")
    private val CONCLUSIONS = Regex("""(?i)^\s*wniosk(i|ek)\s*:?\s*""")
    private val DATE_ISO = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val DATE_PL = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4})(?!\d)""")

    /** Tekst z odczytu (strony, każda lista wierszy w kolejności czytania) jako opis, wnioski i dane z nagłówka. */
    fun parse(pages: List<List<OcrLine>>): ParsedStudyText {
        var title = ""; var kind: StudyKind? = null; var date: LocalDate? = null
        val bodies = ArrayList<List<OcrLine>>()
        pages.forEachIndexed { pi, raw ->
            var lines = readingOrder(raw.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() })
            if (pi == 0) {
                // wiersz z numerem PESEL (i imieniem) kończy nagłówek placówki; wszystko przed nim to adres, tytuł i data
                val idx = lines.indexOfFirst { Studies.hasIdentifier(it.text) }
                if (idx in 1..14) {
                    val header = lines.take(idx).map { it.text }
                    title = headerTitle(header); kind = headerKind(header.joinToString(" ").lowercase().replace(" ", "")); date = headerDate(header.joinToString(" "))
                    lines = lines.drop(idx)
                }
            }
            lines = lines.filterNot { Studies.hasIdentifier(it.text) }
            // podpis lekarza i pieczątka (kilka ostatnich wierszy): odcinamy od pierwszego wiersza z podpisem do końca
            val sig = lines.indices.filter { it >= lines.size - 5 && SIGNATURE.containsMatchIn(lines[it].text) }.minOrNull()
            if (sig != null) lines = lines.take(sig)
            bodies.add(lines)
        }
        val text = bodies.filter { it.isNotEmpty() }.joinToString("\n\n") { joinRows(it.map { l -> Row(l.text, (l.right - l.left).coerceAtLeast(1), l.top, l.bottom) }) }
        val (d, c) = splitConclusions(text)
        return ParsedStudyText(d, c, title, kind, date)
    }

    /**
     * Kolejność czytania wierszy z odczytu. Przy pochylonym zdjęciu OCR potrafi mieszać wiersze (nagłówek, data i końcówki akapitów lądują w środku opisu), więc kolejność
     * odtwarzamy z położenia: pochylenie wynika z lewego marginesu (jego przesunięcie względem wysokości), wiersze są prostowane o ten kąt, układane od góry i grupowane
     * w rzędy (w rzędzie od lewej do prawej, np. adres placówki i data).
     */
    internal fun readingOrder(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val s = skew(lines)
        val corrected = lines.map { l -> l to ((l.top + l.bottom) / 2.0 - s * (l.left + l.right) / 2.0) }.sortedBy { it.second }
        val minH = lines.minOf { (it.bottom - it.top).coerceAtLeast(1) }
        val out = ArrayList<OcrLine>(lines.size)
        var row = ArrayList<Pair<OcrLine, Double>>(); var rowStart = 0.0
        fun flush() { out.addAll(row.sortedBy { it.first.left }.map { it.first }); row = ArrayList() }
        for (p in corrected) {
            if (row.isNotEmpty() && p.second - rowStart > minH * 0.6) flush()
            if (row.isEmpty()) rowStart = p.second
            row.add(p)
        }
        flush()
        return out
    }

    /** Tangens kąta pochylenia wierszy (dodatni: wiersze opadają ku prawej); 0, gdy zdjęcie jest proste albo za mało pełnych wierszy do oceny. */
    internal fun skew(lines: List<OcrLine>): Double {
        val maxW = lines.maxOf { it.right - it.left }
        val wide = lines.filter { it.right - it.left >= maxW * 0.6 }
        if (wide.size < 4) return 0.0
        val cys = wide.map { (it.top + it.bottom) / 2.0 }
        val minGap = wide.map { (it.bottom - it.top).coerceAtLeast(1) }.sorted()[wide.size / 2] * 0.8
        // mediana nachyleń (Theil–Sen) lewego brzegu względem wysokości: odporna na wiersze wyśrodkowane i wcięte
        val slopes = ArrayList<Double>()
        for (i in wide.indices) for (j in i + 1 until wide.size) {
            val dy = cys[j] - cys[i]
            if (Math.abs(dy) >= minGap) slopes.add((wide[j].left - wide[i].left) / dy)
        }
        if (slopes.size < 3) return 0.0
        val s = -slopes.sorted()[slopes.size / 2]
        return if (Math.abs(s) < 0.008) 0.0 else s.coerceIn(-0.25, 0.25)
    }

    /** Porządkuje już wpisany lub wczytany tekst (twarde łamania wierszy): puste wiersze zostają granicami akapitów, krótkie wiersze kończące zdanie też. */
    fun reflow(text: String): String =
        text.replace("\r", "").split(Regex("\n\\s*\n")).map { block -> block.lines().map { it.trim() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }.let { blocks ->
            // „pełny wiersz” to najdłuższy wiersz całego tekstu, a nie bloku (krótki blok jak „Wnioski:” nie jest wtedy pełnym wierszem)
            val ref = blocks.maxOfOrNull { b -> b.maxOf { it.length } } ?: 1
            blocks.joinToString("\n\n") { lines -> joinRows(lines.mapIndexed { i, l -> Row(l, l.length, i * 100, i * 100 + 60) }, ref) }
        }

    /** Dzieli tekst na opis i wnioski po akapicie zaczynającym się od „Wnioski” (z dwukropkiem lub bez); bez takiego akapitu wnioski są puste. */
    fun splitConclusions(text: String): Pair<String, String> {
        val paras = text.split("\n\n")
        val i = paras.indexOfFirst { CONCLUSIONS.containsMatchIn(it) }
        if (i < 0) return text.trim() to ""
        val first = paras[i].replace(CONCLUSIONS, "").trim()
        val rest = (listOf(first) + paras.drop(i + 1)).filter { it.isNotBlank() }.joinToString("\n\n")
        return paras.take(i).joinToString("\n\n").trim() to rest.trim()
    }

    /** Skleja wiersze w akapity (akapity oddziela pusty wiersz). */
    internal fun joinRows(rows: List<Row>, fullWidth: Int? = null): String {
        if (rows.isEmpty()) return ""
        // szerokość pełnego wiersza: najdłuższy wiersz (pochylenie zdjęcia i margines mieszczą się w progu 85%)
        val ref = (fullWidth ?: rows.maxOf { it.width }).coerceAtLeast(1)
        val sb = StringBuilder(rows[0].text)
        for (i in 1 until rows.size) {
            val a = rows[i - 1]; val b = rows[i]
            val short = a.width < ref * 0.85
            val height = (a.bottom - a.top).coerceAtLeast(1)
            val gapBreak = b.top - a.bottom > height * 0.8
            val headingBreak = a.text.endsWith(":") && short
            val sentenceBreak = SENTENCE_END.containsMatchIn(a.text) && short
            when {
                gapBreak || headingBreak || sentenceBreak -> sb.append("\n\n")
                // przeniesione „korowo-” + „rdzeniowym”: zostaje łącznik, bo to zwykle złożenie (a przy zbędnym łączniku osoba poprawia tekst)
                a.text.endsWith("-") && b.text.first().isLowerCase() -> {}
                else -> sb.append(' ')
            }
            sb.append(b.text)
        }
        return sb.toString().replace(Regex("[ \t]{2,}"), " ")
    }

    private fun headerTitle(header: List<String>): String {
        val l = header.firstOrNull { line ->
            val letters = line.filter { it.isLetter() }
            letters.length >= 6 && letters.count { it.isUpperCase() } >= letters.length * 0.8 && Regex("""(?i)badanie|usg|tomograf|rezonans|rtg|rentgen|echo|endoskop|gastroskop|kolonoskop""").containsMatchIn(line)
        } ?: return ""
        // OCR potrafi oddzielić pierwszą literę od reszty słowa („U LTRASONOGRAFICZNE”): taką pojedynczą wielką literę doklejamy do następnego słowa
        val glued = l.trim().replace(Regex("""(?<![\p{L}])(\p{Lu}) (\p{Lu}{4,})"""), "$1$2")
        return glued.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** [h]: tekst nagłówka małymi literami i bez spacji (odporny na rozdzielone przez OCR litery). */
    private fun headerKind(h: String): StudyKind? = when {
        h.contains("ultrasonograf") || h.contains("usg") -> StudyKind.Usg
        h.contains("tomograf") -> StudyKind.Tk
        h.contains("rezonans") -> StudyKind.Mri
        h.contains("rtg") || h.contains("rentgen") -> StudyKind.Rtg
        h.contains("echo") -> StudyKind.Echo
        h.contains("endoskop") || h.contains("gastroskop") || h.contains("kolonoskop") -> StudyKind.Endo
        else -> null
    }

    private fun headerDate(h: String): LocalDate? =
        DATE_ISO.find(h)?.let { m -> runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }
            ?: DATE_PL.find(h)?.let { m -> runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }
}

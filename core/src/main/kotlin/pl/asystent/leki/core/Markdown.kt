package pl.asystent.leki.core

/**
 * Obsługa tekstu wklejonego z modelu AI (Markdown z wzorami LaTeX): czyszczenie wzorów, podział na sekcje (do skracania)
 * i zamiana na HTML do raportu dla lekarza. Wspiera nagłówki, listy, tabele, cytaty, pogrubienia i kursywę.
 */
object Markdown {
    // ---------- Wzory LaTeX ----------

    private val sup = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻')
    private val sub = mapOf('0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉')
    private val symbols = mapOf(
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "approx" to "≈", "geq" to "≥", "ge" to "≥", "leq" to "≤", "le" to "≤", "neq" to "≠",
        "pm" to "±", "times" to "×", "cdot" to "·", "mu" to "µ", "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "Delta" to "Δ", "uparrow" to "↑", "downarrow" to "↓",
        "degree" to "°", "circ" to "°", "sim" to "~", "quad" to " ", "qquad" to "  ", "ldots" to "…", "dots" to "…",
    )

    /** Zamienia wzór LaTeX (zawartość między znakami dolara) na zwykły tekst: 2{,}8\text{ mmol/l} → 2,8 mmol/l. */
    fun cleanMath(m: String): String {
        var s = m
        s = s.replace("{,}", ",")
        s = Regex("""\\(?:text|mathrm|mathbf|textbf|mathit|operatorname)\{([^{}]*)\}""").replace(s) { it.groupValues[1] }
        s = s.replace("\\%", "%").replace("\\,", " ").replace("\\;", " ").replace("\\ ", " ")
        s = Regex("""\\([A-Za-z]+)""").replace(s) { symbols[it.groupValues[1]] ?: it.groupValues[1] }
        // indeksy górne i dolne: ^+ ^{2+} _{max} _1
        s = Regex("""\^\{([0-9+\-]+)\}""").replace(s) { r -> r.groupValues[1].map { c -> sup[c] ?: c }.joinToString("") }
        s = Regex("""\^([0-9+\-])""").replace(s) { r -> (sup[r.groupValues[1][0]] ?: r.groupValues[1][0]).toString() }
        s = Regex("""_\{([^{}]*)\}""").replace(s) { r -> if (r.groupValues[1].all { it.isDigit() }) r.groupValues[1].map { c -> sub[c] ?: c }.joinToString("") else "_" + r.groupValues[1] }
        s = Regex("""_([0-9])""").replace(s) { r -> (sub[r.groupValues[1][0]] ?: r.groupValues[1][0]).toString() }
        s = s.replace("{", "").replace("}", "")
        return s.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }

    /** Usuwa znaczniki wzorów w całym tekście: $...$ i $$...$$. */
    fun cleanLatex(md: String): String {
        var s = Regex("""\$\$([^$]+)\$\$""").replace(md) { cleanMath(it.groupValues[1]) }
        // pojedyncze $...$ to wzór tylko wtedy, gdy ma znaki wzoru; "koszt $5 i $10" zostaje bez zmian
        s = Regex("""\$([^$\n]+)\$""").replace(s) { m -> if (m.groupValues[1].any { it == '\\' || it == '^' || it == '_' || it == '{' }) cleanMath(m.groupValues[1]) else m.value }
        return s
    }

    // ---------- Sekcje ----------

    data class Section(val heading: String, val body: String)

    private val headingRe = Regex("""^(#{1,6})\s+(.*?)(?:\s+#+)?\s*$""")

    private fun headingText(raw: String): String = cleanLatex(raw).replace("*", "").replace("`", "").trim()

    /** Dzieli tekst na sekcje według nagłówków najwyższego poziomu poniżej tytułu (zwykle ##). Tekst przed pierwszą sekcją to "Wstęp". */
    fun sections(md: String): List<Section> {
        val lines = md.replace("\r\n", "\n").split("\n")
        var fence = false
        val levels = lines.mapNotNull { ln ->
            if (ln.trim().startsWith("```")) { fence = !fence; null } else if (fence) null else headingRe.find(ln)?.groupValues?.get(1)?.length
        }.filter { it >= 2 }
        if (levels.isEmpty()) return if (md.isBlank()) emptyList() else listOf(Section("Całość", md.trim()))
        val split = levels.min()
        val out = ArrayList<Section>()
        var title = "Wstęp"
        var cur = StringBuilder()
        var started = false
        fun flush() {
            val b = cur.toString().trim()
            if (b.isNotEmpty() && (started || b.isNotBlank())) out.add(Section(title, b))
        }
        var inFence = false
        for (ln in lines) {
            if (ln.trim().startsWith("```")) inFence = !inFence
            val h = if (inFence) null else headingRe.find(ln)
            if (h != null && h.groupValues[1].length == split) {
                flush(); cur = StringBuilder(); started = true; title = headingText(h.groupValues[2])
            }
            cur.append(ln).append('\n')
        }
        flush()
        return out
    }

    /** Tekst analizy po pominięciu wykluczonych sekcji. */
    fun selected(md: String, excluded: Collection<String>): String =
        sections(md).filter { it.heading !in excluded }.joinToString("\n\n") { it.body }

    private val shortKeys = listOf("podsumowan", "pilne", "pilna", "pytani", "wnioski", "zalecen", "najważniejsz", "najwazniejsz", "streszczen")

    /** Nagłówki do wykluczenia w wersji skróconej: zostają podsumowanie, sprawy pilne, wnioski i pytania do lekarza (jeśli takich nie ma, nic nie jest wykluczane). */
    fun shortExclusions(md: String): List<String> {
        val secs = sections(md)
        val keep = secs.filter { s -> shortKeys.any { s.heading.lowercase().contains(it) } || s.heading == "Wstęp" }
        if (keep.none { it.heading != "Wstęp" }) return emptyList()
        return secs.filter { it !in keep }.map { it.heading }
    }

    // ---------- HTML ----------

    private val boldRe = Regex("""\*\*(.+?)\*\*""")
    private val italicRe = Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")
    private val codeRe = Regex("""`([^`]+)`""")
    /** Odnośniki [tekst](adres) w raporcie do druku nie działają, więc zostaje tekst z adresem w nawiasie. */
    private val linkRe = Regex("""\[([^\]\[]+)]\((https?://[^\s)]+)\)""")
    private val listRe = Regex("""^(\s*)([-*+•]|\d+[.)])\s+(.*)$""")
    private val sepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    /** Znaczniki ważności i pewności z polecenia dla AI: [WYSOKIE], [ŚREDNIE], [NISKIE] (i bliskie odmiany) zamieniane na plakietki. */
    private val badgeRe = Regex("""\[(WYSOKIE|WYSOKA|POWAŻNA|POWAŻNE|ŚREDNIE|ŚREDNIA|UMIARKOWANA|UMIARKOWANE|NISKIE|NISKA|SŁABA|SŁABE)\]""", RegexOption.IGNORE_CASE)

    private fun badgeClass(word: String): String = when (word.uppercase()) {
        "WYSOKIE", "WYSOKA", "POWAŻNA", "POWAŻNE" -> "bd-hi"
        "ŚREDNIE", "ŚREDNIA", "UMIARKOWANA", "UMIARKOWANE" -> "bd-mid"
        else -> "bd-lo"
    }

    private fun inline(s: String): String {
        // bardzo długie linie bez formatowania: wyrażenia regularne mają tu kwadratowy czas i zamrażały ekran
        if (s.length > 3000) return esc(s)
        var t = esc(s)
        t = linkRe.replace(t) { "${it.groupValues[1]} (${it.groupValues[2]})" }
        t = codeRe.replace(t) { "<code>${it.groupValues[1]}</code>" }
        t = boldRe.replace(t) { "<b>${it.groupValues[1]}</b>" }
        t = italicRe.replace(t) { "<i>${it.groupValues[1]}</i>" }
        t = badgeRe.replace(t) { "<span class=\"bd ${badgeClass(it.groupValues[1])}\">${it.groupValues[1].uppercase()}</span>" }
        return t
    }

    /** Ramka z etykietą z polecenia dla AI („> **Pilne:** …”); null, gdy cytat nie zaczyna się od znanej etykiety. */
    private val calloutRe = Regex("""^\*\*\s*(pilne|uwaga|do omówienia|ważne|informacja|info)\s*[:.]?\s*\*\*""", RegexOption.IGNORE_CASE)

    private fun calloutClass(label: String): String = when (label.lowercase()) {
        "pilne", "uwaga" -> "co-urgent"
        "do omówienia", "ważne" -> "co-talk"
        else -> "co-info"
    }

    /** Sekcje raportu AI wyróżniane ramką: podsumowanie, sprawy pilne i pytania do lekarza (po słowie w nagłówku). */
    private fun cardFor(heading: String): String? {
        val h = heading.lowercase()
        return when {
            "pilne" in h || "pilna" in h -> "card-urgent"
            "pytani" in h -> "card-ask"
            "podsumowan" in h -> "card-sum"
            else -> null
        }
    }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.substring(1)
        if (r.endsWith("|")) r = r.substring(0, r.length - 1)
        return r.split("|").map { it.trim() }
    }

    /** Zamienia Markdown (po oczyszczeniu wzorów) na HTML do raportu. Nagłówki są niższego poziomu niż tytuły sekcji raportu. */
    fun toHtml(md: String): String {
        val lines = cleanLatex(md).replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var card = false
        fun closeCard() { if (card) { out.append("</div>"); card = false } }
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isNotEmpty()) { out.append("<p>").append(para.joinToString(" ") { inline(it) }).append("</p>"); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val ln = lines[i]
            val t = ln.trim()
            if (t.isEmpty()) { flushPara(); i++; continue }
            if (t.startsWith("```")) {
                // blok kodu: bez interpretacji Markdown w środku
                flushPara()
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.add(lines[i]); i++ }
                i++ // zamykające ```
                out.append("<pre><code>").append(esc(code.joinToString("\n"))).append("</code></pre>")
                continue
            }
            val h = headingRe.find(t)
            if (h != null) {
                flushPara()
                val lvl = h.groupValues[1].length
                if (lvl <= 2) {
                    closeCard()
                    cardFor(headingText(h.groupValues[2]))?.let { out.append("<div class=\"card $it\">"); card = true }
                }
                out.append(if (lvl <= 2) "<h4>" else "<h5>").append(inline(h.groupValues[2])).append(if (lvl <= 2) "</h4>" else "</h5>")
                i++; continue
            }
            if (Regex("""^(-{3,}|\*{3,}|_{3,})$""").matches(t)) { flushPara(); out.append("<hr>"); i++; continue }
            if (t.contains("|") && i + 1 < lines.size && sepRe.matches(lines[i + 1]) && lines[i + 1].contains("-")) {
                flushPara()
                val head = cells(t)
                out.append("<div class=\"tbl\"><table><thead><tr>").append(head.joinToString("") { "<th>${inline(it)}</th>" }).append("</tr></thead><tbody>")
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
                    out.append("<tr>").append(cells(lines[i]).joinToString("") { "<td>${inline(it)}</td>" }).append("</tr>")
                    i++
                }
                out.append("</tbody></table></div>")
                continue
            }
            if (t.startsWith(">")) {
                flushPara()
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) { q.add(lines[i].trim().removePrefix(">").trim()); i++ }
                // każda linia z etykietą (Pilne, Do omówienia, Informacja) zaczyna osobną ramkę, także bez pustego wiersza między nimi
                val groups = ArrayList<MutableList<String>>()
                for (line in q) if (groups.isEmpty() || calloutRe.containsMatchIn(line)) groups.add(mutableListOf(line)) else groups.last().add(line)
                for (g in groups) {
                    val text = g.joinToString(" ")
                    val label = calloutRe.find(text)
                    if (label != null) out.append("<div class=\"co ${calloutClass(label.groupValues[1])}\">").append(inline(text)).append("</div>")
                    else out.append("<blockquote>").append(inline(text)).append("</blockquote>")
                }
                continue
            }
            val li = listRe.find(ln)
            if (li != null) {
                flushPara()
                val level = (li.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(4)
                val marker = li.groupValues[2]
                val bullet = if (marker[0].isDigit()) "<b>$marker</b> " else "• "
                out.append("<div class=\"li\" style=\"margin-left:${level * 14}px\">").append(bullet).append(inline(li.groupValues[3])).append("</div>")
                i++; continue
            }
            para.add(t)
            i++
        }
        flushPara()
        closeCard()
        return out.toString()
    }
}

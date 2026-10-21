package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Próbki tekstu AI są zmyślone; odwzorowują typowy układ odpowiedzi modelu (Markdown z wzorami LaTeX). */
class MarkdownTest {
    private val sample = """
# Analiza testowa
**Data analizy:** 21.10.2026

---

## PODSUMOWANIE: SPRAWY PILNE
1. **Wynik ${'$'}2{,}8 \rightarrow 2{,}9\text{ mmol/l}${'$'}** wymaga omówienia.
2. Stężenie ${'$'}\text{K}^+${'$'} i ${'$'}\text{Ca}^{2+}${'$'} oraz ${'$'}C_{\max}${'$'}.

## 1. Interakcje
| Para | Nasilenie |
| :--- | :--- |
| **A** + B | *poważna* |
| C + D | słaba |

## 2. Jedzenie
* **Grejpfrut:** unikać.
  * Zagnieżdżone

> Uwaga: to cytat.

## 7. Pytania do lekarza
1. Czy zmienić dawkę?
""".trimIndent()

    @Test fun cleansLatex() {
        assertEquals("2,8 → 2,9 mmol/l", Markdown.cleanMath("""2{,}8 \rightarrow 2{,}9\text{ mmol/l}"""))
        assertEquals("K⁺", Markdown.cleanMath("""\text{K}^+"""))
        assertEquals("Ca²⁺", Markdown.cleanMath("""\text{Ca}^{2+}"""))
        assertEquals("C_max", Markdown.cleanMath("""C_{\max}"""))
        assertEquals("≥ 5 %", Markdown.cleanMath("""\geq 5 \%"""))
        assertEquals("Tekst 2,8 mmol/l i K⁺.", Markdown.cleanLatex("Tekst ${'$'}2{,}8\\text{ mmol/l}${'$'} i ${'$'}\\text{K}^+${'$'}."))
    }

    @Test fun splitsIntoSections() {
        val s = Markdown.sections(sample)
        assertEquals(listOf("Wstęp", "PODSUMOWANIE: SPRAWY PILNE", "1. Interakcje", "2. Jedzenie", "7. Pytania do lekarza"), s.map { it.heading })
        assertTrue(s[1].body.startsWith("## PODSUMOWANIE"))
        assertEquals(listOf("1. Interakcje", "2. Jedzenie"), Markdown.shortExclusions(sample))
        assertFalse(Markdown.selected(sample, listOf("1. Interakcje", "2. Jedzenie")).contains("Grejpfrut"))
        assertTrue(Markdown.selected(sample, listOf("1. Interakcje")).contains("Grejpfrut"))
    }

    @Test fun noHeadingsMeansOneSectionAndNoExclusions() {
        assertEquals(1, Markdown.sections("Samo zdanie bez nagłówków.").size)
        assertTrue(Markdown.shortExclusions("Samo zdanie bez nagłówków.").isEmpty())
        assertTrue(Markdown.sections("   ").isEmpty())
        assertTrue(Markdown.shortExclusions("# Tytuł\n\n## Dane\nx\n\n## Opis\ny").isEmpty()) // brak sekcji rozpoznawalnych jako streszczenie
    }

    @Test fun rendersHtml() {
        val h = Markdown.toHtml(sample)
        assertTrue(h.contains("<h4>PODSUMOWANIE: SPRAWY PILNE</h4>"))
        assertTrue(h.contains("<b>Wynik 2,8 → 2,9 mmol/l</b>"), h)
        assertTrue(h.contains("K⁺ i Ca²⁺"))
        assertTrue(h.contains("<table>") && h.contains("<th>Para</th>") && h.contains("<td><b>A</b> + B</td>") && h.contains("<i>poważna</i>"))
        assertTrue(h.contains("<blockquote>Uwaga: to cytat.</blockquote>"))
        assertTrue(h.contains("<div class=\"li\" style=\"margin-left:0px\"><b>1.</b> "))
        assertTrue(h.contains("margin-left:14px\">• Zagnieżdżone"))
        assertTrue(h.contains("<hr>"))
        assertFalse(h.contains("\$")); assertFalse(h.contains("\\text"))
    }

    @Test fun escapesHtml() {
        val h = Markdown.toHtml("Tekst <script>alert(1)</script> & \"cudzysłów\"")
        assertFalse(h.contains("<script>")); assertTrue(h.contains("&lt;script&gt;")); assertTrue(h.contains("&amp;"))
    }
}

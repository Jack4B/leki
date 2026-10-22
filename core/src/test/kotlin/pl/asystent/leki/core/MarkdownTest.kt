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

    // ---------- odpowiedź w formacie z polecenia dla AI ----------

    private val styled = """
## Podsumowanie
- Najważniejszy wniosek [WYSOKIE]
- Drugi wniosek [ŚREDNIE]

## Sprawy pilne
> **Pilne:** coś wymaga szybkiej konsultacji.

## Interakcje lek–lek
| Leki | Nasilenie | Mechanizm | Co zrobić | Pewność |
| --- | --- | --- | --- | --- |
| Lek A + Lek B | [ŚREDNIE] | krótko | rozdzielić w czasie | [wysokie] |

> **Do omówienia:** pora przyjmowania.
> **Informacja:** to tylko wskazówka.

## Pytania do lekarza
1. Pierwsze pytanie.
2. Drugie pytanie.
""".trimIndent()

    @Test fun styledAnswerBecomesCalloutsBadgesTablesAndCards() {
        val html = Markdown.toHtml(styled)
        // ramki z etykietą: kolor według rodzaju
        assertTrue("<div class=\"co co-urgent\"><b>Pilne:</b> coś wymaga szybkiej konsultacji.</div>" in html, html)
        assertTrue("class=\"co co-talk\"" in html && "class=\"co co-info\"" in html)
        // plakietki ważności (także małymi literami) i tabela z plakietkami w komórkach
        assertTrue("<span class=\"bd bd-hi\">WYSOKIE</span>" in html && "<span class=\"bd bd-mid\">ŚREDNIE</span>" in html)
        assertEquals(2, Regex("bd bd-hi").findAll(html).count())   // [WYSOKIE] w podsumowaniu i [wysokie] w tabeli
        assertTrue("<table>" in html && "<th>Nasilenie</th>" in html && "<td><span class=\"bd bd-mid\">ŚREDNIE</span></td>" in html)
        // sekcje wyróżnione ramką: podsumowanie, sprawy pilne, pytania do lekarza; reszta bez
        assertTrue("card card-sum" in html && "card card-urgent" in html && "card card-ask" in html)
        assertEquals(3, Regex("""class="card """).findAll(html).count())
        assertEquals(Regex("<div").findAll(html).count(), Regex("</div>").findAll(html).count())   // wszystkie ramki zamknięte
    }

    @Test fun plainQuotesAndUnknownMarkersStayAsBefore() {
        val html = Markdown.toHtml("> zwykły cytat bez etykiety\n\nTekst [NIEZNANE] i [WYSOKIE ryzyko]")
        assertTrue("<blockquote>zwykły cytat bez etykiety</blockquote>" in html && "class=\"co" !in html)
        assertTrue("[NIEZNANE]" in html && "[WYSOKIE ryzyko]" in html && "class=\"bd" !in html)
        // tekst z HTML nie przechodzi: znaczniki są ucinane (esc), a nie wykonywane
        val evil = Markdown.toHtml("> **Pilne:** <script>alert(1)</script> [WYSOKIE]")
        assertTrue("<script>" !in evil && "&lt;script&gt;" in evil)
    }

    @Test fun shortVersionKeepsTheContractSections() {
        val ex = Markdown.shortExclusions(styled)
        assertEquals(listOf("Interakcje lek–lek"), ex)   // zostają podsumowanie, sprawy pilne i pytania do lekarza
        val text = Markdown.selected(styled, ex)
        assertTrue("## Podsumowanie" in text && "## Pytania do lekarza" in text && "Interakcje" !in text)
    }
}

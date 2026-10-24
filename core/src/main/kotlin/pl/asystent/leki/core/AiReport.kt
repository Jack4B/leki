package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Raport dla AI: tekst w Markdown z poleceniem dla modelu i danymi z aplikacji.
 * Powstaje na urządzeniu; użytkownik sam decyduje, czy go skopiować lub zapisać.
 */
object AiReport {
    private fun cell(x: Any?): String = (x?.toString() ?: "").replace("|", "/").replace(Regex("""\s*\n\s*"""), " ").trim()

    private fun table(head: List<String>, rows: List<List<Any?>>): String {
        if (rows.isEmpty()) return ""
        return "| " + head.joinToString(" | ") + " |\n|" + head.joinToString("") { " --- |" } + "\n" +
            rows.joinToString("\n") { r -> "| " + r.joinToString(" | ") { cell(it) } + " |" } + "\n"
    }

    private fun offText(m: Int) = (if (m > 0) "+" else if (m < 0) "−" else "") + Math.abs(m) + " min"

    private fun planLines(s: AppState, d: LocalDate): String {
        val by = LinkedHashMap<String, MutableList<String>>()
        s.scheduleFor(d).forEach { by.getOrPut(it.slot.id) { ArrayList() }.add(it.med.name + " " + num(it.dose) + " " + it.med.unit) }
        return s.sortedSlots().filter { by.containsKey(it.id) }.joinToString("\n") { "- " + it.time.hhmm() + " " + it.name + ": " + by[it.id]!!.joinToString("; ") }
    }

    fun build(s: AppState, per: Period, today: LocalDate, now: LocalDateTime, appVersion: String): String {
        val c = s.settings
        val A = c.effectiveAi()
        val ecgs = if (A.ecg) s.ecgs.filter { it.date >= per.from && it.date <= per.to }.sortedWith(compareBy<Ecg> { it.date }.thenBy { it.time }).takeLast(5) else emptyList()
        val L = ArrayList<String>()
        fun p(x: String = "") { L.add(x) }
        p("# Raport o lekach do analizy przez AI")
        p()
        p("Wygenerowano: ${plDate(today)} ${now.toLocalTime().hhmm()} w aplikacji Asystent leków $appVersion. Okres danych: ${plDate(per.from)} – ${plDate(per.to)}.")
        p()
        p("## Twoje zadanie")
        p()
        p("Jesteś doświadczonym farmaceutą klinicznym. Poniżej są dane pacjenta wpisane przez niego samego w aplikacji (mogą zawierać błędy i luki). Przeanalizuj je i odpowiedz po polsku, w tej kolejności:")
        p()
        p("1. **Interakcje lek–lek:** każda para lub grupa leków z istotną interakcją, z oceną nasilenia (poważna, umiarkowana, słaba), mechanizmem i tym, co zwykle się robi (monitorowanie, rozdzielenie w czasie, rozmowa z lekarzem).")
        p("2. **Interakcje z jedzeniem, napojami i używkami** wynikające z tych leków (np. grejpfrut, nabiał, kawa i herbata, alkohol, produkty bogate w potas lub witaminę K).")
        p("3. **Pory dnia i sposób przyjmowania:** czy pory przyjmowania są dobrane właściwie względem posiłków i względem siebie nawzajem (wchłanianie, odstępy między lekami, leki przyjmowane na czczo lub po posiłku, leki moczopędne i nasenne a pora dnia). Podaj konkretną propozycję zmiany harmonogramu do omówienia z lekarzem.")
        p("4. **Duplikaty i dawki:** powtarzające się substancje czynne (także pod różnymi nazwami handlowymi), dawki poza typowym zakresem, leki wymagające ostrożności w kontekście podanych badań i choroby.")
        p(if (ecgs.isEmpty()) "5. **Powiązania z wynikami badań i ciśnieniem:** czy wyniki lub pomiary mogą wynikać z leków albo wymagają zmiany ich przyjmowania."
        else "5. **Powiązania z wynikami badań, ciśnieniem i EKG z zegarka:** czy wyniki, pomiary lub rytm serca mogą wynikać z leków (np. wydłużających odstęp QT, wpływających na rytm albo na stężenie potasu i magnezu) albo wymagają zmiany ich przyjmowania.")
        p("6. **Systematyczność:** co wynika z pominiętych, spóźnionych i nieodhaczonych dawek i jakie mogą być tego skutki.")
        p("7. **Pytania do lekarza lub farmaceuty** do zadania przy najbliższej wizycie.")
        p()
        p("Zasady odpowiedzi:")
        p("- Pracuj na substancjach czynnych. Jeśli nazwa handlowa jest niejednoznaczna albo jej nie znasz, zapytaj lub zaznacz to, zamiast zgadywać.")
        p("- Przy każdym wniosku podaj pewność (wysoka, średnia, niska) i na czym się opiera (np. Charakterystyka Produktu Leczniczego, wytyczne). Nie wymyślaj źródeł.")
        p("- Oddziel sprawy pilne (do pilnej konsultacji) od drobnych uwag. Jeśli nie widzisz problemów, napisz to wprost.")
        p("- To analiza pomocnicza. Nie zalecaj samodzielnego odstawiania leków ani zmiany dawek bez konsultacji z lekarzem.")
        p("- Jeśli brakuje danych potrzebnych do oceny (wiek, waga, funkcja nerek, leki bez recepty, suplementy, godziny posiłków), wypisz, o co zapytać pacjenta.")
        if (ecgs.isNotEmpty()) p("- EKG z zegarka to jedno odprowadzenie opracowane prostą heurystyką, a nie diagnoza. Nie oceniaj odstępu QT, odcinka ST ani niedokrwienia. Wskaż, kiedy potrzebne jest pełne EKG, zwłaszcza przy lekach wydłużających QT i zaburzeniach elektrolitów, i jakie badania laboratoryjne warto zlecić.")
        p()
        p("## Format odpowiedzi (ważne: aplikacja ostyluje Twoją odpowiedź w raporcie, więc trzymaj się tych reguł)")
        p()
        p("Odpowiedz w Markdown. Bez HTML, wzorów LaTeX, obrazków, linków, emoji i bloków kodu. Użyj dokładnie tych nagłówków poziomu 2, w tej kolejności (podpunkty: nagłówki poziomu 3):")
        p()
        p("- `## Podsumowanie`")
        p("- `## Sprawy pilne`")
        p("- `## Interakcje lek–lek`")
        p("- `## Interakcje z jedzeniem i napojami`")
        p("- `## Pory dnia i harmonogram`")
        p("- `## Duplikaty i dawki`")
        p(if (ecgs.isEmpty()) "- `## Powiązania z wynikami`" else "- `## Powiązania z wynikami i EKG`")
        p("- `## Systematyczność`")
        p("- `## Pytania do lekarza`")
        p()
        p("Sekcja bez uwag: jedno zdanie „Brak uwag.” (nie pomijaj nagłówka). Dozwolone elementy:")
        p()
        p("- **Tabele Markdown.** Interakcje: `| Leki | Nasilenie | Mechanizm | Co zrobić | Pewność |`. Harmonogram: `| Pora | Leki | Uwagi |`.")
        p("- **Ramki** (każda to jeden akapit w jednej linii): `> **Pilne:** …`, `> **Do omówienia:** …`, `> **Informacja:** …`.")
        p("- **Znaczniki ważności i pewności**, dokładnie w tej postaci: `[WYSOKIE]`, `[ŚREDNIE]`, `[NISKIE]`.")
        p("- **Listy** tylko jako „- ” (najwyżej dwa poziomy) lub „1. ”, pogrubienia jako `**tekst**`.")
        p("- **Długość:** " + if (A.detail == 0) "krótko, do około 350 słów łącznie; w Podsumowaniu 3–5 punktów; drobne uwagi pomiń lub zbij w jedno zdanie." else "szczegółowo, do około 1000 słów łącznie; w Podsumowaniu 5–8 punktów.")
        p()
        p("Układ (przykład szkieletu, treść zmyślona):")
        p()
        p("```")
        p("## Podsumowanie")
        p("- Najważniejszy wniosek w jednym zdaniu [WYSOKIE]")
        p("## Sprawy pilne")
        p("> **Pilne:** co wymaga szybkiej konsultacji i dlaczego.")
        p("## Interakcje lek–lek")
        p("| Leki | Nasilenie | Mechanizm | Co zrobić | Pewność |")
        p("| --- | --- | --- | --- | --- |")
        p("| Lek A + Lek B | [ŚREDNIE] | krótko, jak działa | rozdzielić w czasie | [WYSOKIE] |")
        p("## Pytania do lekarza")
        p("1. Pytanie, które warto zadać.")
        p("```")
        p()
        p("## Pacjent")
        p()
        if (A.name && s.patient.isNotEmpty()) p("- Imię i nazwisko: ${s.patient}")
        val ctx = A.context.trim()
        p("- Kontekst podany przez pacjenta (wiek, płeć, choroby, alergie, leki bez recepty, suplementy): " + if (ctx.isNotEmpty()) ctx.replace(Regex("""\s*\n\s*"""), " / ") else "brak, nie podano")
        p("- Przyjmowanie leków zapisuje sam pacjent. Brak odhaczenia może oznaczać pominięcie dawki albo brak wpisu.")

        val ms = s.meds.filter { it.isActive(today) }
        val future = s.meds.filter { it.phases.first().from > today }
        val ended = s.meds.filter { it.end != null && it.end < today && it.end >= per.from }
        fun medRow(m: Med): List<Any?> {
            val ph = m.phaseAt(today) ?: m.phases.first()
            val hint = ArrayList<String>()
            if (m.note.isNotEmpty()) hint.add(m.note)
            if (m.mmol > 0) hint.add("zawiera ${num(m.mmol)} mmol potasu w 1 ${m.unit}")
            if (m.timerMin > 0) hint.add("po dawce pacjent odlicza ${m.timerMin} min")
            return listOf(m.name, m.substance.ifEmpty { "nie podano" }, s.phaseText(m, ph), plDate(m.phases.first().from), m.end?.let { plDate(it) } ?: "bez końca", hint.joinToString("; "))
        }
        val medHead = listOf("Lek", "Substancja czynna (wg pacjenta)", "Dawkowanie", "Początek", "Koniec", "Wskazówka pacjenta")
        if (A.meds) {
            p(); p("## Leki aktualnie w planie (stan na ${plDate(today)})"); p()
            if (ms.isNotEmpty()) {
                p(table(medHead, ms.map(::medRow)))
                p("Pory dnia to etykiety pacjenta. Nazwy (np. Śniadanie, Kolacja) sugerują związek z posiłkami, ale aplikacja nie zapisuje godzin posiłków.")
                p(); p("### Harmonogram dnia (co jest przyjmowane razem)"); p()
                val groups = LinkedHashMap<String, MutableList<Int>>()
                for (i in 0 until 7) { val d = today.plusDays(i.toLong()); groups.getOrPut(planLines(s, d)) { ArrayList() }.add(d.jsDow) }
                groups.forEach { (txt, days) ->
                    val label = if (days.size == 7) "**Codziennie**" else "**" + Defaults.dowOrder.filter { it in days }.joinToString(", ") { Pl.dowFull[it] } + "**"
                    p("$label:"); p(txt.ifEmpty { "- brak leków" }); p()
                }
            } else p("Brak leków w planie.")
            if (future.isNotEmpty()) { p("Zaplanowane na później: " + future.joinToString(", ") { it.name + " (od " + plDate(it.phases.first().from) + ")" } + "."); p() }
            if (ended.isNotEmpty()) { p("### Leki zakończone w tym okresie"); p(); p(table(medHead, ended.map(::medRow))) }
        }
        if (A.history) {
            val ch = s.meds.filter { it.phases.size > 1 && (it.end == null || it.end >= per.from) }
            if (ch.isNotEmpty()) {
                p(); p("## Zmiany dawkowania w czasie"); p()
                ch.forEach { m -> p("- **${m.name}**: " + m.phases.joinToString(" → ") { "od " + plDate(it.from) + " " + s.phaseText(m, it) } + (m.end?.let { " (zakończono ${plDate(it)})" } ?: "")) }
            }
        }
        if (A.adherence) {
            p(); p("## Zgodność z planem w okresie ${plDate(per.from)} – ${plDate(per.to)}"); p()
            val rows = s.adherence(per.from, per.to, today, now.toLocalTime().minutes)
            if (rows.isNotEmpty()) {
                p(table(listOf("Lek", "Pora", "Zaplanowane", "Odhaczone", "Pominięte świadomie (powód)", "Bez odhaczenia", "Spóźnione ponad ${c.lateMin} min", "Odchylenie godziny przyjęcia od pory"),
                    rows.map { a ->
                        val rs = a.reasons.entries.joinToString(", ") { it.key + " (" + it.value + ")" }
                        listOf(a.med.name, a.slot.name + " " + a.slot.time.hhmm(), a.plan, a.taken, a.skipped.toString() + if (rs.isNotEmpty()) ": $rs" else "", a.missing, a.late,
                            a.avgOffset?.let { avg -> offText(avg) + " (min " + offText(a.offsets.min()) + ", max " + offText(a.offsets.max()) + ")" } ?: "brak godzin")
                    }))
                p("Odchylenie dodatnie oznacza przyjęcie później niż pora z planu.")
            } else p("Brak zaplanowanych dawek w tym okresie.")
            if (A.notes) {
                val notes = dayList(per.from, if (per.to < today) per.to else today).mapNotNull { d -> s.days[d]?.note?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(plDate(d), it.take(300)) } }
                if (notes.isNotEmpty()) { p(); p("### Notatki pacjenta z poszczególnych dni"); p(); p(table(listOf("Data", "Notatka"), notes.takeLast(30))) }
            }
        }
        if (A.bp) {
            val list = s.bp.filter { it.date >= per.from && it.date <= per.to }.sortedWith(bpOrder)
            p(); p("## Ciśnienie tętnicze i tętno"); p()
            if (list.isNotEmpty()) {
                val st = bpStats(list)
                p("- Liczba pomiarów: ${st.n}. Średnia: ${st.s}/${st.d} mmHg" + (st.p?.let { ", średnie tętno $it/min" } ?: "") + ".")
                p("- Zakres: skurczowe ${st.minS}–${st.maxS}, rozkurczowe ${st.minD}–${st.maxD} mmHg.")
                if (st.rano.n > 0) p("- Rano średnio ${st.rano.s}/${st.rano.d} (${st.rano.n} ${plN(st.rano.n, "pomiar", "pomiary", "pomiarów")}).")
                if (st.wieczor.n > 0) p("- Wieczorem średnio ${st.wieczor.s}/${st.wieczor.d} (${st.wieczor.n} ${plN(st.wieczor.n, "pomiar", "pomiary", "pomiarów")}).")
                p("- Progi ustawione przez pacjenta: ${c.thresholdText()}.")
                p()
                p(table(listOf("Data", "Godz.", "Ciśnienie", "Tętno", "Pora pomiaru", "Uwagi"),
                    list.takeLast(40).map { r -> listOf(plDate(r.date), r.time.hhmm(), "${r.sys}/${r.dia}", r.pulse ?: "", r.ctx, listOf(c.bpFlag(r), r.note).filter { it.isNotEmpty() }.joinToString("; ")) }))
                if (list.size > 40) p("Pokazano 40 ostatnich z ${list.size} pomiarów.")
            } else p("Brak pomiarów w tym okresie.")
        }
        if (ecgs.isNotEmpty()) {
            p(); p("## EKG z zegarka (jedno odprowadzenie)"); p()
            p("Metoda obliczeń: ${EcgInfo.SHORT}")
            ecgs.forEach { e ->
                val r = EcgAnalysis.analyze(e)
                p(); p("### ${plDate(e.date)} ${e.time.hhmm()}, ${Math.round(e.durationS)} s"); p()
                if (e.watchHr > 0) p("- Tętno wg zegarka: ${e.watchHr}/min.")
                if (e.watchNote.isNotBlank()) p("- Ocena zegarka: ${cell(e.watchNote)}.")
                if (r.hrMedian != null) {
                    p("- Tętno z zapisu: mediana ${Math.round(r.hrMedian)}/min, średnia ${Math.round(r.hrMean ?: r.hrMedian)}/min, zakres ${Math.round(r.hrMin ?: 0.0)}–${Math.round(r.hrMax ?: 0.0)}/min.")
                    p("- Zmienność odstępów RR: SDNN ${Math.round(r.sdnnMs ?: 0.0)} ms, RMSSD ${Math.round(r.rmssdMs ?: 0.0)} ms, pNN50 ${Math.round((r.pnn50 ?: 0.0) * 100)}%. Wykryte uderzenia: ${r.beats.size}.")
                }
                if (r.nRmssd != null && r.tpr != null && r.sampEn != null) p("- Wskaźniki losowej nieregularności RR: RMSSD/RR ${"%.2f".format(java.util.Locale.ROOT, r.nRmssd)}, punkty zwrotne ${Math.round(r.tpr * 100)}%, entropia próbkowa ${"%.1f".format(java.util.Locale.ROOT, r.sampEn)}; wzorzec jak przy migotaniu przedsionków: ${if (r.afPattern) "tak (heurystyka, nie rozpoznanie)" else "nie"}.")
                if (r.qrsWidthMs != null && r.shapeCorr != null) p("- Kształt uderzeń: szerokość QRS około ${Math.round(r.qrsWidthMs)} ms (orientacyjnie, rozdzielczość 4 ms), mediana korelacji uderzeń z szablonem ${"%.2f".format(java.util.Locale.ROOT, r.shapeCorr)}.")
                p("- Jakość zapisu: ${r.quality}" + (if (r.artifacts.isNotEmpty()) "; zakłócenia pominięte w analizie w sekundach: " + r.artifacts.joinToString(", ") { "%.1f–%.1f".format(java.util.Locale.ROOT, it.start, it.endInclusive) } else "") + ".")
                r.flags.forEach { p("- Wynik analizy: ${it.title}. ${it.text}") }
                val rr = r.validRr
                if (rr.isNotEmpty()) p("- Kolejne wiarygodne odstępy RR (ms): " + rr.take(60).joinToString(", ") { Math.round(it * 1000).toString() } + if (rr.size > 60) " (pierwsze 60 z ${rr.size})" else "")
                if (e.note.isNotBlank()) p("- Notatka pacjenta: ${cell(e.note)}")
            }
        }
        if (A.cykl) Cykl.brief(s.cykl.db, today)?.let { b ->
            p(); p("## Dziennik cyklu (opis, bez rozpoznania)"); p()
            Cykl.briefLines(b).forEach { p("- $it") }
        }
        if (A.labs) {
            p(); p("## Wyniki badań (do ${plDate(per.to)})"); p()
            val rows = s.markers.mapNotNull { m ->
                val all = s.labsOf(m.id, per.to)
                if (all.isEmpty()) return@mapNotNull null
                val f = labFlag(all.last(), m)
                listOf(m.name, m.unit, rangeText(all.last(), m).ifEmpty { "brak" }, all.takeLast(8).joinToString("; ") { plDate(it.date) + ": " + lnum(it.value) }, f.ifEmpty { "w zakresie" })
            }
            if (rows.isNotEmpty()) {
                p(table(listOf("Parametr", "Jednostka", "Zakres odniesienia", "Ostatnie wyniki (od najstarszego)", "Ocena ostatniego wyniku"), rows))
                p("Zakresy odniesienia wpisał pacjent (zwykle z wyniku laboratorium). Oznaczenia w aplikacji są tylko orientacyjne.")
            } else p("Brak wyników badań.")
        }
        if (A.events) {
            val evs = s.events.filter { it.date >= per.from && it.date <= today.plusDays(60) }.sortedBy { it.date }
            if (evs.isNotEmpty()) { p(); p("## Kontrole i terminy"); p(); p(table(listOf("Data", "Wydarzenie", "Uwagi"), evs.map { listOf(plDate(it.date), it.title, it.note) })) }
        }
        if (A.notes) {
            val free = ArrayList<String>()
            if (s.rules.trim().isNotEmpty()) free.add("Notatki i zasady pacjenta:\n\n" + s.rules.trim())
            if (c.reportNote.trim().isNotEmpty()) free.add("Dopisek pacjenta do raportów: " + c.reportNote.trim())
            if (free.isNotEmpty()) { p(); p("## Notatki pacjenta (dowolny tekst)"); p(); p(free.joinToString("\n\n")) }
        }
        p(); p("## Ograniczenia danych"); p()
        p("- Dane pochodzą z samodzielnych wpisów pacjenta i nie były weryfikowane.")
        p("- Aplikacja nie zna leków bez recepty ani suplementów, jeśli pacjent ich nie dopisał.")
        p("- Dawki są podane w jednostkach wybranych przez pacjenta (np. tabl., sasz.), bez przeliczenia na miligramy.")
        if (ecgs.isNotEmpty()) p("- Surowy przebieg EKG nie jest dołączony; są tylko wskaźniki i odstępy RR. Odstęp QT nie jest dostępny.")
        return L.joinToString("\n").replace(Regex("\n{3,}"), "\n\n") + "\n"
    }
}

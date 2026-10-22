# Raport o lekach do analizy przez AI

Wygenerowano: 21.10.2026 14:00 w aplikacji Asystent leków 0.2.0-preview. Okres danych: 15.10.2026 – 21.10.2026.

## Twoje zadanie

Jesteś doświadczonym farmaceutą klinicznym. Poniżej są dane pacjenta wpisane przez niego samego w aplikacji (mogą zawierać błędy i luki). Przeanalizuj je i odpowiedz po polsku, w tej kolejności:

1. **Interakcje lek–lek:** każda para lub grupa leków z istotną interakcją, z oceną nasilenia (poważna, umiarkowana, słaba), mechanizmem i tym, co zwykle się robi (monitorowanie, rozdzielenie w czasie, rozmowa z lekarzem).
2. **Interakcje z jedzeniem, napojami i używkami** wynikające z tych leków (np. grejpfrut, nabiał, kawa i herbata, alkohol, produkty bogate w potas lub witaminę K).
3. **Pory dnia i sposób przyjmowania:** czy pory przyjmowania są dobrane właściwie względem posiłków i względem siebie nawzajem (wchłanianie, odstępy między lekami, leki przyjmowane na czczo lub po posiłku, leki moczopędne i nasenne a pora dnia). Podaj konkretną propozycję zmiany harmonogramu do omówienia z lekarzem.
4. **Duplikaty i dawki:** powtarzające się substancje czynne (także pod różnymi nazwami handlowymi), dawki poza typowym zakresem, leki wymagające ostrożności w kontekście podanych badań i choroby.
5. **Powiązania z wynikami badań i ciśnieniem:** czy wyniki lub pomiary mogą wynikać z leków albo wymagają zmiany ich przyjmowania.
6. **Systematyczność:** co wynika z pominiętych, spóźnionych i nieodhaczonych dawek i jakie mogą być tego skutki.
7. **Pytania do lekarza lub farmaceuty** do zadania przy najbliższej wizycie.

Zasady odpowiedzi:
- Pracuj na substancjach czynnych. Jeśli nazwa handlowa jest niejednoznaczna albo jej nie znasz, zapytaj lub zaznacz to, zamiast zgadywać.
- Przy każdym wniosku podaj pewność (wysoka, średnia, niska) i na czym się opiera (np. Charakterystyka Produktu Leczniczego, wytyczne). Nie wymyślaj źródeł.
- Oddziel sprawy pilne (do pilnej konsultacji) od drobnych uwag. Jeśli nie widzisz problemów, napisz to wprost.
- To analiza pomocnicza. Nie zalecaj samodzielnego odstawiania leków ani zmiany dawek bez konsultacji z lekarzem.
- Jeśli brakuje danych potrzebnych do oceny (wiek, waga, funkcja nerek, leki bez recepty, suplementy, godziny posiłków), wypisz, o co zapytać pacjenta.

## Format odpowiedzi (ważne: aplikacja ostyluje Twoją odpowiedź w raporcie, więc trzymaj się tych reguł)

Odpowiedz w Markdown. Bez HTML, wzorów LaTeX, obrazków, linków, emoji i bloków kodu. Użyj dokładnie tych nagłówków poziomu 2, w tej kolejności (podpunkty: nagłówki poziomu 3):

- `## Podsumowanie`
- `## Sprawy pilne`
- `## Interakcje lek–lek`
- `## Interakcje z jedzeniem i napojami`
- `## Pory dnia i harmonogram`
- `## Duplikaty i dawki`
- `## Powiązania z wynikami`
- `## Systematyczność`
- `## Pytania do lekarza`

Sekcja bez uwag: jedno zdanie „Brak uwag.” (nie pomijaj nagłówka). Dozwolone elementy:

- **Tabele Markdown.** Interakcje: `| Leki | Nasilenie | Mechanizm | Co zrobić | Pewność |`. Harmonogram: `| Pora | Leki | Uwagi |`.
- **Ramki** (każda to jeden akapit w jednej linii): `> **Pilne:** …`, `> **Do omówienia:** …`, `> **Informacja:** …`.
- **Znaczniki ważności i pewności**, dokładnie w tej postaci: `[WYSOKIE]`, `[ŚREDNIE]`, `[NISKIE]`.
- **Listy** tylko jako „- ” (najwyżej dwa poziomy) lub „1. ”, pogrubienia jako `**tekst**`.
- **Długość:** szczegółowo, do około 1000 słów łącznie; w Podsumowaniu 5–8 punktów.

Układ (przykład szkieletu, treść zmyślona):

```
## Podsumowanie
- Najważniejszy wniosek w jednym zdaniu [WYSOKIE]
## Sprawy pilne
> **Pilne:** co wymaga szybkiej konsultacji i dlaczego.
## Interakcje lek–lek
| Leki | Nasilenie | Mechanizm | Co zrobić | Pewność |
| --- | --- | --- | --- | --- |
| Lek A + Lek B | [ŚREDNIE] | krótko, jak działa | rozdzielić w czasie | [WYSOKIE] |
## Pytania do lekarza
1. Pytanie, które warto zadać.
```

## Pacjent

- Kontekst podany przez pacjenta (wiek, płeć, choroby, alergie, leki bez recepty, suplementy): Mężczyzna, 60 lat / nadciśnienie
- Przyjmowanie leków zapisuje sam pacjent. Brak odhaczenia może oznaczać pominięcie dawki albo brak wpisu.

## Leki aktualnie w planie (stan na 21.10.2026)

| Lek | Substancja czynna (wg pacjenta) | Dawkowanie | Początek | Koniec | Wskazówka pacjenta |
| --- | --- | --- | --- | --- | --- |
| Lek A | substancja alfa | Śniadanie 1,5 tabl., Kolacja 1 tabl. | 01.10.2026 | bez końca | po posiłku; zawiera 10 mmol potasu w 1 tabl.; po dawce pacjent odlicza 30 min |
| Lek B | nie podano | Przed snem 1 tabl. (Pn, Śr, Pt) | 01.10.2026 | bez końca |  |

Pory dnia to etykiety pacjenta. Nazwy (np. Śniadanie, Kolacja) sugerują związek z posiłkami, ale aplikacja nie zapisuje godzin posiłków.

### Harmonogram dnia (co jest przyjmowane razem)

**środa**:
- 08:00 Śniadanie: Lek A 1,5 tabl.
- 19:00 Kolacja: Lek A 1 tabl.
- 22:00 Przed snem: Lek B 1 tabl.

**czwartek**:
- 08:00 Śniadanie: Lek A 1,5 tabl.
- 19:00 Kolacja: Lek A 1 tabl.

**poniedziałek, piątek**:
- 08:00 Śniadanie: Lek A 1,5 tabl.; Lek D 1 tabl.
- 19:00 Kolacja: Lek A 1 tabl.
- 22:00 Przed snem: Lek B 1 tabl.

**wtorek, sobota, niedziela**:
- 08:00 Śniadanie: Lek A 1,5 tabl.; Lek D 1 tabl.
- 19:00 Kolacja: Lek A 1 tabl.

Zaplanowane na później: Lek D (od 23.10.2026).

### Leki zakończone w tym okresie

| Lek | Substancja czynna (wg pacjenta) | Dawkowanie | Początek | Koniec | Wskazówka pacjenta |
| --- | --- | --- | --- | --- | --- |
| Lek C | beta | Obiad 1 sasz. | 13.10.2026 | 20.10.2026 |  |

## Zmiany dawkowania w czasie

- **Lek A**: od 01.10.2026 Śniadanie 2 tabl., Kolacja 1 tabl. → od 11.10.2026 Śniadanie 1,5 tabl., Kolacja 1 tabl.

## Zgodność z planem w okresie 15.10.2026 – 21.10.2026

| Lek | Pora | Zaplanowane | Odhaczone | Pominięte świadomie (powód) | Bez odhaczenia | Spóźnione ponad 60 min | Odchylenie godziny przyjęcia od pory |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lek A | Śniadanie 08:00 | 7 | 5 | 0 | 2 | 2 | +44 min (min −5 min, max +90 min) |
| Lek C | Obiad 14:30 | 6 | 0 | 0 | 6 | 0 | brak godzin |
| Lek A | Kolacja 19:00 | 6 | 4 | 0 | 2 | 0 | +10 min (min +10 min, max +10 min) |
| Lek B | Przed snem 22:00 | 2 | 1 | 0 | 1 | 0 | +12 min (min +12 min, max +12 min) |

Odchylenie dodatnie oznacza przyjęcie później niż pora z planu.

### Notatki pacjenta z poszczególnych dni

| Data | Notatka |
| --- | --- |
| 19.10.2026 | Zawroty głowy / po południu |

## Ciśnienie tętnicze i tętno

- Liczba pomiarów: 11. Średnia: 131/83 mmHg, średnie tętno 67/min.
- Zakres: skurczowe 118–140, rozkurczowe 76–89 mmHg.
- Rano średnio 129/83 (7 pomiarów).
- Wieczorem średnio 133/83 (4 pomiary).
- Progi ustawione przez pacjenta: podwyższone od 135/85, niskie poniżej 100/60.

| Data | Godz. | Ciśnienie | Tętno | Pora pomiaru | Uwagi |
| --- | --- | --- | --- | --- | --- |
| 15.10.2026 | 08:06 | 126/86 | 68 | rano | podwyższone |
| 15.10.2026 | 20:30 | 138/80 | 70 | wieczór | podwyższone |
| 16.10.2026 | 08:05 | 133/89 | 67 | rano | podwyższone |
| 17.10.2026 | 08:04 | 140/78 |  | rano | podwyższone |
| 17.10.2026 | 20:30 | 128/84 | 70 | wieczór |  |
| 18.10.2026 | 08:03 | 122/81 | 65 | rano | po kawie |
| 19.10.2026 | 08:02 | 129/84 | 64 | rano |  |
| 19.10.2026 | 20:30 | 138/88 | 70 | wieczór | podwyższone |
| 20.10.2026 | 08:01 | 136/87 | 63 | rano | podwyższone |
| 21.10.2026 | 08:00 | 118/76 |  | rano |  |
| 21.10.2026 | 20:30 | 128/80 | 70 | wieczór |  |

## Wyniki badań (do 21.10.2026)

| Parametr | Jednostka | Zakres odniesienia | Ostatnie wyniki (od najstarszego) | Ocena ostatniego wyniku |
| --- | --- | --- | --- | --- |
| Potas | mmol/l | 3,5-5,5 | 09.10.2026: 2,8; 15.10.2026: 3,4; 20.10.2026: 4,3 | w zakresie |
| Sód | mmol/l | 136-146 | 18.10.2026: 134 | poniżej zakresu |
| TSH | mIU/l | 0,27-4,2 | 18.10.2026: 2,15 | w zakresie |

Zakresy odniesienia wpisał pacjent (zwykle z wyniku laboratorium). Oznaczenia w aplikacji są tylko orientacyjne.

## Kontrole i terminy

| Data | Wydarzenie | Uwagi |
| --- | --- | --- |
| 20.10.2026 | Badanie krwi | na czczo |
| 26.10.2026 | Wizyta |  |

## Notatki pacjenta (dowolny tekst)

Notatki i zasady pacjenta:

Zasada 1: <ważne> & "ostrożnie"

Zasada 2

Dopisek pacjenta do raportów: Okres testowy

## Ograniczenia danych

- Dane pochodzą z samodzielnych wpisów pacjenta i nie były weryfikowane.
- Aplikacja nie zna leków bez recepty ani suplementów, jeśli pacjent ich nie dopisał.
- Dawki są podane w jednostkach wybranych przez pacjenta (np. tabl., sasz.), bez przeliczenia na miligramy.

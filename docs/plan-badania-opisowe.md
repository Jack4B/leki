# Badania opisowe i obrazowe: konspekt

Stan: wdrożone w 0.16.0-preview (etap 1). Dokument opisuje decyzje, żeby kolejne etapy (tomografia z PDF, osobne pliki zdjęć) nie zmieniały ich po cichu.
Wszystkie przykłady w repozytorium są zmyślone; prawdziwych wyników, opisów ani zdjęć badań nie wolno wklejać do repo, testów, README ani wiadomości commitów.

## Po co

Poza wynikami liczbowymi (Badania → Przegląd) i EKG z zegarka osoba ma badania, których wynikiem jest **opis słowny** (USG, tomografia, rezonans, RTG, endoskopia, echo serca) i często **zdjęcia** z badania. Chce je mieć w jednym miejscu, wybrać, które trafią do raportu dla lekarza i do tekstu dla AI, i nie wpisywać wszystkiego ręcznie.

## Model

- `Study` (`core/.../Studies.kt`): id, data, rodzaj (`StudyKind`: USG, TK, MRI, RTG, endoskopia, echo, inne), tytuł, miejsce, opis, wnioski, do 8 zdjęć.
- `StudyImage`: JPEG pomniejszony na telefonie (dłuższy bok 1280 px, jakość 75 → 35 aż do limitu), zapisany jako base64, podpis opcjonalny.
- Jedno badanie opisowe i obrazowe to ten sam model: opisowe ma tylko tekst, obrazowe ma też zdjęcia.
- Aplikacja niczego nie ocenia ani nie interpretuje (ani tekstu, ani obrazów). Opis i wnioski to tekst osoby.

## Decyzje

1. **Zdjęcia w stanie osoby (w base64), nie w osobnych plikach.** Dzięki temu działają bez zmian: kopia zapasowa (JSON v3), szyfrowanie danych (etap 2), „Usuń wszystkie dane” i wiele osób. Koszt: plik danych rośnie i jest przepisywany przy każdym zapisie. Dlatego limity: 8 zdjęć na badanie, ok. 260 KB na zdjęcie (`MAX_IMAGE_CHARS`), ok. 4,5 MB zdjęć na osobę (`MAX_TOTAL_CHARS`, tak żeby kopia mieściła się w limicie importu 20 MB). Etap przyszły: osobne zaszyfrowane pliki zdjęć, jeśli limity okażą się za ciasne.
2. **Nowe pola w kopii tylko przy wartościach niedomyślnych** (`studies`, `ReportParts.studies/studyIds`, `AiParts.studies`); stare kopie wczytują się bez zmian. Wczytywanie odrzuca uszkodzone wpisy i zdjęcia (niepoprawny JPEG, za duże, ponad limit łączny).
3. **Wybór do raportów jak przy EKG, ale bez filtra daty przy wyborze ręcznym:** `studyIds == null` oznacza najnowsze badania z okresu (do 5), lista (także pusta) to tylko zaznaczone, **niezależnie od daty**, bo takie badania robi się rzadko i do raportu trafiają starsze. Wybór jest wspólny dla raportu dla lekarza i dla AI.
4. **Domyślnie wyłączone w obu raportach** (jak cykl i imię): to dane wrażliwe, niekoniecznie potrzebne w każdym raporcie.
5. **Raport dla lekarza:** opis, wnioski i zdjęcia jako miniatury (44 mm), z adnotacją, że opisy wpisała osoba, a zdjęcia są pomniejszone i nie nadają się do oceny diagnostycznej. Wydruk nie rozrywa badania (nagłówek, opis i zdjęcia zostają razem; `.study{break-inside:avoid}`).
6. **Raport dla AI: tylko tekst.** Opis (do 3000 znaków na badanie) i wnioski, bez zdjęć, z informacją „Pacjent ma N zdjęć, nie ma ich w tym tekście”. Polecenie mówi modelowi, że obrazów nie widzi i nie ma ich oceniać ani stawiać rozpoznań z samego opisu.
7. **Ukryty moduł Badania ukrywa też te badania w raportach** (`labsOn`), jak EKG.

## Prywatność (najważniejsze)

- Wynik z przychodni zwykle zawiera **imię, nazwisko i PESEL** (przykład: „Pacjent JAN TESTOWY PESEL 00000000000”). Aplikacja ich nie potrzebuje.
- `Studies.stripIdentifiers`: wiersz ze słowem „PESEL” albo ciągiem 11 cyfr jest usuwany z tekstu **wczytanego ze zdjęcia/PDF** (zanim trafi do pola) i **zawsze z tekstu dla AI** (zabezpieczenie dodatkowe, także przy tekście wpisanym ręcznie). Osoba i tak sprawdza tekst przed zapisem.
- Zdjęcie strony z danymi osobowymi dodane jako „zdjęcie z badania” nie jest czyszczone (to byłby osobny etap: rozpoznawanie i zamazywanie). Edytor to mówi wprost.
- Odczyt tekstu (OCR) jest na telefonie, bez sieci (ML Kit, jak przy wynikach badań).

## Przepływ w aplikacji

Badania → **Opisowe** → „Dodaj badanie”: rodzaj, data, tytuł, miejsce, „Wczytaj opis ze zdjęcia lub PDF” (do 6 stron PDF), opis, wnioski, „Dodaj zdjęcie z galerii” (podgląd, podpis, usunięcie), „Zapisz”. Lista ma miniatury, datę, rodzaj i liczbę zdjęć. Usunięcie ma „Cofnij” (przywraca także wybór do raportów). Raport → pigułka „Badania opisowe i obrazowe” (w „Dla lekarza” i „Dla AI”) i lista z polami wyboru.

## Testy

`StudiesTest` (`core`): walidacja i limity zdjęć, usuwanie identyfikatorów, wybór (automatyczny i ręczny), zapis/usunięcie/cofnięcie, kopia (także zaszyfrowana) i stare kopie, uszkodzone wpisy, raport dla lekarza (zdjęcia, wybór, ukryty moduł), raport dla AI (tekst, bez zdjęć i PESEL). Interfejs (`StudyScreen.kt`, `StudyImages.kt`) sprawdza CI i telefon.

## Poza zakresem etapu 1 (pomysły)

- Tomografia i rezonans jako **pliki DICOM lub płyta**: nie wczytujemy, tylko opis i pojedyncze zdjęcia (zrzuty z opisu).
- Zamazywanie danych osobowych na zdjęciach, osobne pliki zdjęć, powiększanie z przesuwaniem (jak w EKG), porównanie badań w czasie, kategorie własne zamiast stałej listy rodzajów.
- Powiązanie badania z wynikami liczbowymi (np. kreatynina przy USG nerek) i z lekami (przypomnienie o kontroli).

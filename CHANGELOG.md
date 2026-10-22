# Zmiany

## 0.5.1-preview

- **Obtainium:** każda kompilacja ma teraz własne wydanie w Releases z unikalnym tagiem `v<wersja>-b<numer>` (wcześniej jedno wydanie było kasowane i tworzone od nowa pod tym samym tagiem, więc Obtainium nie widział zmiany). Zostaje 5 ostatnich wydań kompilacji, starsze są usuwane. Instrukcja dodania aplikacji w README.
- **Podpis jawnie plikiem klucza:** build podaje do Gradle ścieżkę klucza (`-PlekiKeystore`), zamiast polegać na domyślnym kluczu debug, i po zbudowaniu sprawdza, że podpis APK zgadza się z kluczem z pliku (inaczej przerywa build). Wcześniej APK mógł dostać inny podpis niż klucz z sekretu.
- **Stały klucz podpisu:** workflow używa klucza z sekretu `DEBUG_KEYSTORE_BASE64`, żeby aktualizacje wchodziły na wierzch bez odinstalowywania. Opis wydania pokazuje odcisk certyfikatu (SHA-256) albo ostrzega, gdy APK jest podpisany kluczem tymczasowym. Pierwsze przejście na stały klucz wymaga jednorazowej reinstalacji (kopia zapasowa danych przed odinstalowaniem).

## 0.5.0-preview

- **Wykres ciśnienia w czasie, przewijany w poziomie.** Stała skala czasu zamiast ściskania całego okresu na szerokości ekranu: oś ciśnienia zostaje po lewej, wykres otwiera się na najnowszych dniach i przesuwa palcem. Zakres: 7, 30, 90 dni, rok lub wszystko. Do 45 dni pojedyncze pomiary, dłużej słupek min–max dla każdego dnia i linia średnich, podpisy tygodni lub miesięcy, przerywane linie progów.
- **Raport dla lekarza przy długich okresach:** wykres cięty na kawałki po 91 dni, zestawienie tygodniowe (powyżej ok. 6 miesięcy miesięczne) ze średnimi, zakresami, tętnem i liczbą pomiarów podwyższonych i niskich zamiast tabeli każdego pomiaru. Raporty do 45 dni są bez zmian (nadal identyczne z poprzednimi wersjami).
- **Analiza AI jako uzupełnienie raportu dla lekarza.** Pole do wklejenia odpowiedzi modelu AI (przycisk „Wklej ze schowka”), tytuł i data, kilka analiz do wyboru. Markdown i wzory LaTeX są porządkowane do zwykłego tekstu. Długą odpowiedź można skrócić, wyłączając całe sekcje; domyślnie zostają podsumowanie, sprawy pilne, wnioski, zalecenia i pytania do lekarza. W raporcie analiza ma na końcu osobny nagłówek i adnotację, że to tekst AI, a nie ocena lekarska.
- **Analizy są częścią kopii zapasowej** (także zaszyfrowanej), nowe pole `analyses` z wartością domyślną, więc starsze kopie wczytują się bez zmian.
- Testy: czyszczenie LaTeX i Markdown, sekcje i skracanie, geometria i podpisy wykresu dla tygodnia, 90 dni i roku, raport roczny, raport z analizą, zapis i odczyt kopii (także zaszyfrowanej) z analizami. Razem 59 testów logiki.

## 0.4.0-preview

- **Dodawanie wyników badań ze zdjęcia lub PDF (OCR).** W zakładce Badania przycisk „Skanuj wyniki”: zdjęcie z aparatu, zdjęcia z galerii (do 6) albo plik PDF (do 12 stron). Tekst rozpoznaje ML Kit na telefonie (model dołączony do aplikacji), więc nic nie jest wysyłane, a zdjęcia i pliki nie są zapisywane. Aplikacja składa wiersze tabeli z położenia tekstu, odczytuje nazwę, wynik, jednostkę i zakres odniesienia, rozpoznaje datę pobrania i dopasowuje parametry do tych z aplikacji (znane nazwy polskie i skróty, np. HGB, WBC, K, Na) albo proponuje nowe.
- **Ekran sprawdzania przed zapisem:** każdy wynik można poprawić, odznaczyć albo przypisać do innego parametru. Ostrzeżenia: inna jednostka niż w aplikacji (np. mg/dl zamiast mmol/l; takie wyniki są domyślnie odznaczone), wynik z tego dnia już zapisany, duża zmiana względem poprzedniego wyniku (pomyłka w przecinku), znaki „<” i „>”. Widoczny jest też oryginalny wiersz i cały rozpoznany tekst (można go skopiować).
- 19 nowych testów rozpoznawania (układy tabel, przecinki i kropki, skróty, flagi H/L, daty, jednostki, dopasowanie, ostrzeżenia, zapis). Trzy układy (tabela z nagłówkiem, lista „Nazwa: wynik jednostka (zakres)” i „nazwa nad wynikiem”) są sprawdzane na prawdziwym wyniku silnika OCR z typowymi pomyłkami (np. „ulU/ml”, „25-0H”, „Sód” odczytane jako „pos”). Wszystkie próbki są zmyślone.
- Gdy OCR przekręci nazwę badania, aplikacja zgaduje parametr po jednostce i zakresie odniesienia (np. mmol/l i 136–145 to sód), ale oznacza to ostrzeżeniem i domyślnie odznacza wynik do sprawdzenia. Poprawiane są też typowe pomyłki w jednostkach (mmo1/l, mg/d1, mmol/I).
- **Przeprojektowany interfejs:** czytelny górny pasek z ikoną zasad, komunikaty (snackbar) w kolorach aplikacji (wcześniej w ciemnym motywie tekst był niewidoczny na jasnym tle), przyciski w trzech stylach (główny, tonalny, kontur), przełączane pigułki zamiast długich list pól wyboru, zaokrąglone karty.
- **Raport:** okres wybierany jednym ruchem (7, 14, 30 dni, wszystko lub własny zakres) i dwie zakładki „Dla lekarza” oraz „Dla AI” zamiast jednej długiej strony.
- **Ustawienia:** strona główna z grupami (Plan, Badania, Aplikacja) i podstrony z podsumowaniem na każdym wierszu (Leki, Pory dnia, Kontrole, Parametry badań, Ogólne, Przypomnienia, Kopie zapasowe, O aplikacji), z przyciskiem wstecz.
- Leki: wybór daty ikoną kalendarza zamiast szerokiego pola. Badania: przyciski „Skanuj wyniki” i „Dodaj ręcznie” na górze. Jednostka wody („1 l”) nie jest już w czcionce, w której „l” wygląda jak „1”.
- Rozmiar APK: tylko architektury arm64-v8a i armeabi-v7a (telefony), bez x86. Przed zawężeniem plik miał 51 MB, głównie przez natywne biblioteki OCR.
- Manifest jawnie usuwa uprawnienie INTERNET, gdyby dopisała je jakaś biblioteka.
- Zrzuty ekranów w CI w wersji z kilkoma „plasterkami” przewijanych ekranów w czytelnej rozdzielczości, także ekran wyboru źródła i sprawdzania skanu.

## 0.3.0-preview

Aplikacja napisana od nowa jako **natywna aplikacja na Androida** (Kotlin i Jetpack Compose). Capacitor i wersja webowa zostały usunięte.

- **Nowy identyfikator aplikacji** `pl.asystent.leki`: można ją zainstalować obok wersji 0.2 (Capacitor), więc dane da się bezpiecznie przenieść przez kopię zapasową, bez odinstalowywania starej.
- **Pełna zgodność z dotychczasową aplikacją:** te same funkcje (leki, pory dnia, zmiany dawkowania z historią, pominięte dawki, stoper po dawce, woda, ciśnienie, badania, kontrole, raport dla lekarza, raport dla AI, przypomnienia, kopie zapasowe) i ten sam format kopii. Kopie JSON i zaszyfrowane z poprzednich wersji wczytują się bez zmian.
- **Logika w osobnym module `core`** (czysty Kotlin) z 27 testami jednostkowymi. Raporty (dla lekarza i dla AI) są sprawdzane test po teście z wynikami poprzedniej wersji: wychodzą identyczne znak w znak. Testowane są też wczytywanie starych kopii, szyfrowanie zgodne z poprzednią wersją, przypomnienia i operacje na danych.
- **Brak uprawnienia INTERNET.** Aplikacja nie ma technicznej możliwości łączenia się z siecią. Dane nie są wysyłane do chmury (kopia zapasowa Androida wyłączona), a rozpoznawanie tekstu (OCR) w planach działa na urządzeniu.
- Przypomnienia przez alarmy systemowe (przeżywają restart telefonu i aktualizację), z kontrolą zgody na powiadomienia i na dokładne alarmy.
- Raport dla lekarza: podgląd i druk lub zapis PDF z menu Androida. Raport dla AI: kopiowanie i udostępnianie jako plik `.md`.
- Zapis kopii przez systemowy wybór pliku i „Udostępnij”; wczytywanie z pliku (kopia, kopia zaszyfrowana, plik z wynikami badań).
- Dane zapisywane atomowo (plik tymczasowy i zamiana); uszkodzony plik jest odkładany obok, a aplikacja pokazuje komunikat zamiast po cichu tracić dane.
- Wymaga Androida 8.0 lub nowszego (wcześniej 6.0).
- Testy renderowania ekranów (Paparazzi): zrzuty widoków Leki, Ciśnienie, Badania, Raport i Ustawienia (także w trybie ciemnym i na pustych danych) trafiają przy każdym buildzie do wydania „zrzuty-ekranow”, żeby sprawdzać układ bez telefonu.
- Workflow: testy `core`, budowanie przez Gradle, wydanie w Releases po każdym wypchnięciu (`v<wersja>`), opcjonalny stały klucz podpisu z sekretu `DEBUG_KEYSTORE_BASE64`.

## 0.2.0-preview

- **Ikona aplikacji:** własna ikona (kapsułka na zielonym tle), adaptacyjna i klasyczna, ekran startowy w kolorze aplikacji oraz monochromatyczna ikona powiadomień. Rysunek w `assets/*.svg`, pliki PNG w `native/res`.
- **Raport dla AI** (zakładka Raport): tekst w Markdown z gotowym poleceniem dla modelu (interakcje lek–lek, interakcje z jedzeniem, dobór pór dnia i odstępów, duplikaty i dawki, powiązania z badaniami i ciśnieniem, systematyczność, pytania do lekarza) oraz danymi: leki i harmonogram dnia, zmiany dawkowania, zgodność z planem z godzinami przyjęcia, ciśnienie, wyniki badań, kontrole, notatki. Wybór sekcji, pole na kontekst medyczny (wiek, choroby, alergie), imię domyślnie pominięte. Kopiowanie do schowka, zapis pliku `.md` i podgląd.
- Nowe ustawienia `settings.ai` w kopii zapasowej (starsze kopie wczytują się z wartościami domyślnymi).
- `npm run check` sprawdza też zgodność numeru wersji (`package.json` i `APP_VERSION`) i obecność wpisu w CHANGELOG.
- Workflow sam publikuje APK w zakładce Releases (wydanie testowe `v<wersja>`, odświeżane przy każdym wypchnięciu). Wcześniej wydanie powstawało tylko po ręcznym tagu, więc Releases było puste.
- Dodano `CLAUDE.md` z zasadami pracy (m.in. aktualizacja README przy każdej zmianie).

## 0.1.0-preview

Pierwsza publiczna wersja, aplikacja startuje pusta.

- Usunięto wszystkie dane osobowe i domyślne dane medyczne z kodu (imię i nazwisko, leki, zasady, kontrole, wyniki, daty).
- Usunięto jednorazowe migracje ze starych wersji i podpowiedź zmiany konkretnego leku.
- Usunięto synchronizację z własnym serwerem (nie działała w wersji na Androida i wymagała osobnego skryptu).
- Fonty Geist i ikony Phosphor są teraz wbudowane (wcześniej pobierane z Google Fonts i unpkg), więc aplikacja działa bez internetu i niczego nie ujawnia stronom trzecim.
- Kod podzielony na `index.html`, `css/app.css` i `js/app.js`, dodana polityka CSP.
- Przypomnienia: aplikacja wykrywa brak zgody na powiadomienia (np. po wczytaniu kopii na nowym telefonie) i pokazuje komunikat.
- Naprawiono dodawanie wyniku badania, gdy lista parametrów jest pusta lub wskazany parametr już nie istnieje.
- Ekran startowy z pustą listą leków otwiera od razu formularz nowego leku.
- Neutralne płciowo teksty (powody pominięcia dawki).
- Workflow: `npm ci`, kontrola składni, nazwa pliku z wersją, suma SHA-256, wydanie po tagu `v*`, opcjonalny stały klucz podpisu.
- Numer wersji aplikacji (`versionName`, `versionCode`) jest ustawiany automatycznie.

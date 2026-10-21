# Asystent leków

Wersja **0.5.0 (preview)**. Natywna aplikacja na Androida (Kotlin, Jetpack Compose).

Dziennik leków, ciśnienia i wyników badań w jednej aplikacji. Dane zostają **tylko na telefonie**. Aplikacja nie ma kont, serwera ani analityki, a w manifeście nie ma nawet uprawnienia INTERNET, więc technicznie nie może niczego wysłać do sieci.

Aplikacja startuje **pusta**: nie ma w niej żadnych leków, danych ani imienia. Wszystko dodajesz sam w Ustawieniach albo wczytujesz z kopii zapasowej.

## Funkcje

- **Leki:** plan dnia z porami (śniadanie, obiad…), odhaczanie dawek z godziną, pomijanie dawek z powodem, zmiany dawkowania w czasie z zachowaniem historii, zamiana leku na inny, stoper po dawce, licznik wody, notatka dnia.
- **Ciśnienie:** pomiary z tętnem i porą, **przewijany wykres w czasie** (7, 30, 90 dni, rok lub wszystko), średnie, oznaczenia progów ustawianych przez użytkownika. Szczegóły niżej.
- **Badania:** dziennik wyników z zakresami odniesienia, wykresy, widok według parametrów i dat, kontrole z polem na wynik, import wyników z pliku JSON oraz **dodawanie wyników ze zdjęcia lub PDF (OCR)**.
- **Raport dla lekarza:** wybór okresu i sekcji, podgląd, druk lub zapis do PDF z menu Androida. Do raportu można dołączyć **wklejoną odpowiedź AI** jako uzupełnienie.
- **Raport dla AI:** gotowy tekst (Markdown) z poleceniem i danymi z aplikacji, do wklejenia w dowolny model AI. Szczegóły niżej.
- **Przypomnienia o dawkach:** powiadomienia o porach z planu, także przy zamkniętej aplikacji i po restarcie telefonu.
- **Kopie zapasowe:** plik JSON albo plik zaszyfrowany hasłem (AES-256-GCM, PBKDF2), zapis do pliku lub przez systemowe „Udostępnij”.

Aplikacja tylko zapisuje i porządkuje dane wpisane przez użytkownika. Nie ocenia wyników i nie zastępuje lekarza. Dawkowanie zmieniaj zgodnie z jego zaleceniami.

## Raport dla AI

W zakładce **Raport** jest sekcja **Raport dla AI**. Generuje ona jeden tekst w Markdown, który kopiujesz, udostępniasz jako plik `.md` albo wklejasz do wybranego modelu (ChatGPT, Claude, Gemini i inne).

Tekst składa się z dwóch części:

1. **Polecenie dla modelu:** analiza interakcji lek–lek, interakcji z jedzeniem i napojami, doboru pór dnia i odstępów między lekami, duplikatów substancji i nietypowych dawek, powiązań z wynikami badań i ciśnieniem, systematyczności przyjmowania oraz lista pytań do lekarza. Model ma podawać pewność wniosków, oddzielać sprawy pilne i nie zalecać samodzielnych zmian dawek.
2. **Dane:** leki z dawkowaniem i wskazówkami, harmonogram dnia (co jest brane razem), zmiany dawkowania, zgodność z planem (pominięte, spóźnione i nieodhaczone dawki, średnia godzina przyjęcia względem pory), ciśnienie, wyniki badań z zakresami, kontrole i notatki.

Okres jest ten sam co w raporcie dla lekarza. Możesz wybrać sekcje i wpisać kontekst medyczny (wiek, płeć, choroby, alergie, leki bez recepty, godziny posiłków), co mocno poprawia analizę. Imię i nazwisko jest domyślnie pomijane. Tekst powstaje na urządzeniu i opuszcza je dopiero, gdy go skopiujesz lub udostępnisz. Odpowiedź AI nie jest poradą medyczną.

## Wykres ciśnienia w czasie

Wykres ma stałą skalę czasu, więc przy długim okresie nie zlewa się w kreskę, tylko robi się szerszy i przewija się palcem w bok (oś ciśnienia zostaje po lewej, a wykres otwiera się na najnowszych dniach). Zakres wybierasz przyciskami **7 dni, 30 dni, 90 dni, Rok, Wszystko**.

- **Do 45 dni:** każdy pomiar jest punktem, z podpisem dni. Przerywane linie to progi podwyższonego ciśnienia ustawione w aplikacji.
- **Powyżej 45 dni:** dla każdego dnia pionowy słupek od najniższego do najwyższego pomiaru i linia średnich dziennych. Podpisy to tygodnie (do ok. 4 miesięcy) albo początki miesięcy (rok i dłużej).
- **W raporcie do druku** wykres jest cięty na kawałki po 91 dni, a pod nim jest zestawienie tygodniowe (powyżej ok. 6 miesięcy: miesięczne) ze średnimi, zakresami, tętnem i liczbą pomiarów podwyższonych i niskich. Pełna lista każdego pomiaru jest tylko w raportach do 45 dni, żeby roczny wydruk miał kilka stron, a nie kilkadziesiąt.

## Analiza AI jako uzupełnienie raportu dla lekarza

Odpowiedź modelu AI na „Raport dla AI” (patrz wyżej) możesz wkleić do aplikacji i dołączyć do raportu dla lekarza.

1. W zakładce **Raport → Dla lekarza** włącz pigułkę **Analiza AI** i wybierz **Wklej odpowiedź AI** (albo link pod raportem w karcie „Dla AI”). Przycisk **Wklej ze schowka** wstawia tekst, można go też wpisać lub poprawić ręcznie.
2. Wzory LaTeX i Markdown (nagłówki, listy, tabele, pogrubienia, cytaty) są w raporcie porządkowane do zwykłego tekstu, np. `$2{,}8\text{ mmol/l}$` wychodzi jako „2,8 mmol/l”.
3. **Skracanie:** tekst jest dzielony na sekcje według nagłówków; każdą można wyłączyć. Przy długim tekście aplikacja od razu zostawia tylko sekcje o podsumowaniu, sprawach pilnych, wnioskach, zaleceniach i pytaniach do lekarza. Przyciski „Tylko podsumowanie i pytania” oraz „Całość” przełączają to jednym ruchem.
4. W raporcie analiza jest na końcu, z nagłówkiem „Uzupełnienie: analiza farmaceutyczno-kliniczna (AI)” i adnotacją, że to tekst wygenerowany przez AI na podstawie danych z aplikacji, a nie ocena lekarska.
5. Analizy zapisują się **w kopii zapasowej** (także zaszyfrowanej) razem z pozostałymi danymi. Można mieć kilka (np. z różnych dat) i wybierać, która trafia do raportu. Starsze kopie bez analiz wczytują się normalnie.

## Dodawanie wyników ze zdjęcia lub PDF

W zakładce **Badania** przycisk **Skanuj wyniki** otwiera wybór źródła: **aparat**, **galeria** (jedno lub kilka zdjęć albo zrzutów ekranu) albo **plik PDF** (także skany w PDF, do 12 stron).

1. Aplikacja rozpoznaje tekst na telefonie (ML Kit, model dołączony do aplikacji). Zdjęcia i pliki są przetwarzane w pamięci, nie są zapisywane ani nigdzie wysyłane, a aplikacja nie ma uprawnienia INTERNET.
2. Z położenia tekstu składa wiersze tabeli i odczytuje nazwę badania, wynik, jednostkę i zakres odniesienia. Obsługuje układ tabeli, listy („Potas: 4,2 mmol/l (3,5 - 5,1)”) i „nazwa nad wynikiem”. Rozpoznaje datę pobrania (pomija datę urodzenia) i dopasowuje parametry do tych z aplikacji: zna polskie nazwy i skróty (np. Potas, K, Sód, Na, HGB, WBC, TSH, CRP, cholesterol HDL i LDL), a nieznane proponuje jako nowe parametry razem z zakresem z dokumentu. Gdy OCR przekręci nazwę, parametr jest zgadywany po jednostce i zakresie odniesienia i oznaczany do sprawdzenia.
3. **Ekran sprawdzania** pokazuje każdy wynik z oryginalnym wierszem. Możesz poprawić wartość, zmienić parametr, odznaczyć wiersz i ustawić datę. Aplikacja ostrzega, gdy jednostka różni się od zapisanej w aplikacji (np. mg/dl zamiast mmol/l: taki wynik jest domyślnie odznaczony), gdy wynik z tego dnia już istnieje, gdy zmiana względem poprzedniego wyniku jest podejrzanie duża (pomyłka w przecinku) i gdy w wyniku jest „<” lub „>”.
4. Dopiero po kliknięciu **Zapisz** wyniki trafiają do dziennika (z dopiskiem „ze skanu”). Istniejący wynik z tego samego dnia jest zastępowany.

Wskazówki: cała tabela w kadrze, tekst poziomo, bez cienia i odblasków. PDF pobrany z laboratorium daje zwykle lepszy rezultat niż zdjęcie. Na ekranie sprawdzania zawsze porównaj wartości z oryginałem: rozpoznawanie tekstu bywa niedokładne. Jeśli coś się nie rozpoznaje, w sekcji „Rozpoznany tekst” można skopiować to, co aplikacja zobaczyła.

## Pobranie APK z GitHuba

Najnowszy APK jest w zakładce **Releases** repozytorium, w wydaniu **Asystent leków 0.5.0-preview** (sekcja **Assets**: plik `asystent-lekow-0.5.0-preview.apk` i suma kontrolna `.sha256`). Wydanie jest oznaczone jako testowe (prerelease) i odświeża się automatycznie po każdym wypchnięciu zmian na gałąź. Ten sam plik jest też w artefakcie przebiegu **Actions → Zbuduj APK**.

Instalacja: skopiuj APK na telefon i otwórz go (Android poprosi o zgodę na instalację z nieznanych źródeł dla przeglądarki lub menedżera plików). Wymagany Android 8.0 lub nowszy.

## Przenoszenie danych z wersji 0.2 (Capacitor)

Wersja 0.3 ma inny identyfikator (`pl.asystent.leki`), więc instaluje się **obok** starej i niczego jej nie kasuje.

1. W starej aplikacji: **Ustawienia → Dane → Zaszyfrowana kopia** (albo Pobierz JSON) i zapisz plik.
2. W nowej aplikacji: **Ustawienia → Dane → Wczytaj kopię z pliku**, wybierz plik (przy zaszyfrowanym wpisz hasło).
3. Włącz przypomnienia w **Ustawienia → Przypomnienia o lekach** (Android poprosi o zgodę na powiadomienia).
4. Dopiero gdy wszystko się zgadza, odinstaluj starą aplikację.

Format kopii (wersja 3) jest taki sam we wszystkich wersjach. Starsze pliki (wersje 1 i 2 aplikacji sprzed Androida) nie są już obsługiwane.

## Budowanie lokalnie

Wymagane: Java 21 i Android SDK (`ANDROID_HOME`).

```
./gradlew checkChangelog :core:test :app:assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`. Numer wersji jest w `gradle.properties` (`appVersionName`), a `-PversionCode=N` ustawia numer kompilacji (workflow podaje numer przebiegu).

Samą logikę (moduł `core`) można budować i testować bez Androida: `./gradlew :core:test` w projekcie zawierającym tylko ten moduł.

## Aktualizacja aplikacji

Zainstaluj nowy APK **na wierzch** (bez odinstalowywania), a dane zostaną. Android wymaga, żeby każdy APK był podpisany tym samym kluczem. Domyślnie GitHub Actions generuje nowy klucz w każdym przebiegu, więc przy aktualizacji z innego przebiegu Android może odmówić. Są dwa wyjścia:

- zrób kopię danych, odinstaluj aplikację, zainstaluj nową i wczytaj kopię, albo
- ustaw stały klucz: wygeneruj go raz i dodaj w repozytorium jako sekret `DEBUG_KEYSTORE_BASE64` (Settings → Secrets and variables → Actions). Workflow użyje go w każdym kolejnym przebiegu.

```
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # wynik wklej do sekretu; sam plik trzymaj poza repozytorium
```

## Struktura projektu

```
core/          logika bez zależności od Androida (Kotlin/JVM): model danych, harmonogram, statystyki,
               raporty (lekarz, AI), kopie zapasowe i szyfrowanie, geometria wykresów (w tym przewijany wykres ciśnienia), czyszczenie Markdown i LaTeX z odpowiedzi AI, przypomnienia,
               odczyt wyników z tekstu OCR (LabScan)
core/src/test  testy jednostkowe (w tym rozpoznawanie wyników badań) i dane wzorcowe z poprzedniej wersji (fixtures)
app/           aplikacja na Androida (Jetpack Compose): ekrany, magazyn danych, alarmy, druk, udostępnianie,
               ocr/ (ML Kit, wczytywanie zdjęć i PDF)
app/src/test   zrzuty ekranów głównych widoków (Paparazzi, render na JVM), publikowane w wydaniu „zrzuty-ekranow”
assets/        źródłowe rysunki ikony (SVG)
scripts/       generatory: ikony launchera (make-icons.js) i ikon interfejsu Phosphor (gen-icons.js), tylko dev
.github/       workflow budujący APK i publikujący go w Releases
```

## Prywatność

- Dane trafiają wyłącznie do pamięci aplikacji. Kopia zapasowa Androida do chmury Google i przenoszenie między telefonami są wyłączone.
- Aplikacja nie ma uprawnienia INTERNET.
- Kopię do chmury (Dysk Google, Filen) rób w formie zaszyfrowanej. Hasła nie da się odzyskać.
- Repozytorium nie zawiera żadnych danych osobowych ani zdrowotnych.

## Zasady pracy nad projektem

Zasady dla osób i narzędzi rozwijających repozytorium są w `CLAUDE.md`. Najważniejsza: każda zmiana aktualizuje README i CHANGELOG.

## Plany

- Dostrajanie rozpoznawania na prawdziwych układach wyników z różnych laboratoriów (potrzebne anonimowe próbki rozpoznanego tekstu z ekranu sprawdzania).
- Podgląd zdjęcia obok wyników na ekranie sprawdzania.
- Wykres ciśnienia: oznaczanie kolorem pomiarów powyżej progu.
- Ręczne kadrowanie zdjęcia przed rozpoznaniem.

## Znane ograniczenia wersji preview

- APK jest podpisany kluczem debug i nie jest przeznaczony do Google Play.
- Wersja bez minifikacji kodu (większy plik, mniejsze ryzyko błędów); optymalizacja później. Rozmiar APK to kilkadziesiąt MB, głównie przez model i biblioteki rozpoznawania tekstu; działa tylko na telefonach arm64 i armeabi-v7a.
- Interfejs tylko po polsku.
- Analiza AI w raporcie to tekst wklejony przez użytkownika: aplikacja go nie sprawdza, tylko porządkuje zapis. Wzory LaTeX są upraszczane do zwykłego tekstu (rzadkie konstrukcje mogą zostać w surowej postaci).
- Rozpoznawanie wyników z obrazu zależy od jakości zdjęcia i układu dokumentu; aplikacja pokazuje wszystko do sprawdzenia przed zapisem, ale nie gwarantuje bezbłędnego odczytu. Wyników zapisanych w układzie innym niż tabela (nazwa, wynik, jednostka w jednym wierszu) może nie rozpoznać.
- Aplikacja była sprawdzana w testach logiki i w kompilacji na CI, ale nie na różnych modelach telefonów. Zgłaszaj błędy.
- Synchronizacji z własnym serwerem (obecnej w dawnej wersji przeglądarkowej) nie ma. Przenoszenie danych odbywa się przez kopie zapasowe.

# Asystent leków

Wersja **0.6.5 (preview)**. Natywna aplikacja na Androida (Kotlin, Jetpack Compose).

Dziennik leków, ciśnienia i wyników badań w jednej aplikacji. Dane zostają **tylko na telefonie**. Aplikacja nie ma kont, serwera ani analityki, a w manifeście nie ma nawet uprawnienia INTERNET, więc technicznie nie może niczego wysłać do sieci.

Aplikacja startuje **pusta**: nie ma w niej żadnych leków, danych ani imienia. Wszystko dodajesz sam w Ustawieniach albo wczytujesz z kopii zapasowej.

## Funkcje

- **Leki:** plan dnia z porami (śniadanie, obiad…), odhaczanie dawek z godziną, pomijanie dawek z powodem, zmiany dawkowania w czasie z zachowaniem historii, zamiana leku na inny, stoper po dawce, licznik wody, notatka dnia.
- **Ciśnienie:** pomiary z tętnem i porą, **przewijany wykres w czasie** (7, 30, 90 dni, rok lub wszystko), średnie, oznaczenia progów ustawianych przez użytkownika. Szczegóły niżej.
- **Badania:** dziennik wyników z zakresami odniesienia, wykresy, widok według parametrów i dat, kontrole z polem na wynik, import wyników z pliku JSON, **dodawanie wyników ze zdjęcia lub PDF (OCR)** oraz **analiza EKG z zegarka** (wydruk PDF).
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

Do raportu można dołączyć zapisy EKG z zegarka z wybranego okresu (pigułka „EKG z zegarka”): wskaźniki, wyniki analizy i odstępy RR w milisekundach, bez surowego przebiegu, a polecenie dla modelu dostaje wtedy dodatkowy punkt o EKG z zakazem oceny QT i ST. Okres jest ten sam co w raporcie dla lekarza. Możesz wybrać sekcje i wpisać kontekst medyczny (wiek, płeć, choroby, alergie, leki bez recepty, godziny posiłków), co mocno poprawia analizę. Imię i nazwisko jest domyślnie pomijane. Tekst powstaje na urządzeniu i opuszcza je dopiero, gdy go skopiujesz lub udostępnisz. Odpowiedź AI nie jest poradą medyczną.

## EKG z zegarka

W zakładce **Badania → EKG** przycisk **Dodaj EKG z pliku PDF** wczytuje wydruk EKG wyeksportowany z aplikacji zegarka lub opaski (jedno odprowadzenie, zwykle 30 s w trzech pasach po 10 s).

1. Aplikacja renderuje stronę na telefonie i odtwarza przebieg z obrazu: czerwona linia na siatce, wydruk w standardzie 25 mm/s i 10 mm/mV, strona pionowa (pasy obrócone o 90°) lub pozioma.
2. Tekst nagłówka (OCR na urządzeniu) daje datę, godzinę, tętno i ocenę rytmu zegarka. Imię, płeć, wiek, wzrost i waga z wydruku **nie są odczytywane ani zapisywane**. Datę i resztę możesz poprawić przed zapisem.
3. Z przebiegu aplikacja liczy tętno, odstępy RR i ich zmienność, rozpoznaje zakłócenia ruchowe (są pomijane) i wskazuje kandydatów do omówienia z lekarzem: tętno poniżej 50 lub powyżej 100/min, wyraźnie nieregularny rytm, przedwczesne uderzenia z dłuższą przerwą po nich, długie przerwy.
4. W podglądzie można przebieg **powiększać i przewijać** (dwa palce, przyciski „Całość, 10 s, 5 s, 2 s”), z siatką jak na papierze.
5. Zapis (przebieg w 250 próbkach na sekundę, zapisany wektorowo: ok. 4,5 KB na 30 s) jest w kopii zapasowej, a raport dla lekarza może zawierać sekcję „EKG z zegarka” z przebiegiem i wskaźnikami.

**Jak to jest liczone (algorytmy).** Obliczenia robi sama aplikacja na telefonie, bez bibliotek zewnętrznych, internetu i modeli AI (kod: `core/.../Ecg.kt`, opis w `EcgInfo.kt` i w aplikacji pod „Jak to jest liczone”):
1. **Odtworzenie przebiegu:** strona PDF jest renderowana do obrazu (300 dpi, a przy braku pamięci 200 dpi), piksele czerwonej linii zamieniane na przebieg (wiersz obrazu = chwila, odległość od mediany linii = napięcie), skala 25 mm/s i 10 mm/mV. Strona nie jest trzymana jako duża bitmapa: od razu powstaje z niej maska jednobajtowa (czwarta część pamięci), a bitmapa jest zwalniana; do odczytu nagłówka (OCR) służy osobny, mały render 200 dpi. Przebieg jest potem przeliczany do 250 próbek/s i zapisywany wektorowo (punkt 9).
2. **Dryf linii podstawowej** usuwany ruchomą medianą z okna 0,6 s.
3. **Uderzenia (QRS):** metoda energii pochodnej w duchu algorytmu Pana i Tompkinsa (1985), uproszczona: kwadrat różnicy próbek wygładzony oknem 60 ms, próg 25% mediany maksimów z okien 1,5 s, odstęp refrakcji 250 ms, punkt R jako maksimum w ±70 ms, kierunek QRS według większości uderzeń.
4. **Zakłócenia:** pomijane fragmenty ±0,35 s wokół wychyleń ponad 2,5 amplitudy R, wychyleń przeciwnych do QRS o amplitudzie co najmniej 0,9 R i skoków ponad 4 razy bardziej stromych niż QRS.
5. **Wskaźniki:** tętno = 60 / RR (mediana, średnia, zakres), SDNN, RMSSD, pNN50 (różnice kolejnych odstępów większe niż 50 ms).
6. **Wzorzec losowej nieregularności (z samych odstępów RR):** RMSSD/średni RR co najmniej 0,12, odsetek punktów zwrotnych w ciągu od 55% do 82% (losowy ciąg ma ok. 67%, gładka zmienność oddechowa mniej, bigeminia 100%) i entropia próbkowa (m = 1, r = 0,2 SD) co najmniej 1,0; wszystkie trzy naraz i co najmniej 20 odstępów dają oznaczenie „odstępy jak przy migotaniu przedsionków”. Progi dobrano na sygnałach syntetycznych, bez walidacji na prawdziwych zapisach z migotaniem.
7. **Kształt uderzeń i szerokość QRS:** szablon to mediana czystych uderzeń; korelacja z szablonem ocenia jakość; szerokość QRS mierzona na stromych zboczach (próg 15% maksymalnego nachylenia, rozdzielczość 8 ms) służy do porównań. Przedwczesne uderzenie dostaje opis „kształt odmienny (możliwe komorowe)” (korelacja poniżej 0,75 albo QRS o ponad 30% szerszy) albo „zbliżony (możliwe przedsionkowe lub nadkomorowe)”.
8. **Reguły oznaczeń:** wolne tętno (mediana poniżej 50/min), szybkie (powyżej 100/min), rytm wyraźnie nieregularny (co najmniej 35% odstępów odbiega o ponad 15% od mediany i współczynnik zmienności powyżej 0,1), przedwczesne uderzenie (odstęp poniżej 80% mediany, po nim powyżej 110%), przerwa (odstęp powyżej 1,6 mediany i powyżej 1,8 s). Do oceny potrzeba co najmniej 8 uderzeń.

9. **Zapis wektorowy (mały rozmiar bez utraty wierności):** zamiast równych próbek zapisywane są tylko punkty załamania linii (uproszczenie Ramera, Douglasa i Peuckera z błędem pionowym do 0,02 mV, czyli ok. 2 piksele wydruku w 300 dpi), jako różnice indeksów i wartości w zmiennej długości, base64. Odtworzenie przez interpolację liniową różni się od przebiegu o najwyżej 0,02 mV, a szczyty R zostają zachowane (są wierzchołkami). Przebieg w pamięci jest sprowadzany do punktu stałego zapisu, więc wielokrotne zapisywanie nie powoduje dryfu. Na zmierzonym wydruku (30 s): równe próbki 250 Hz w poprzednim zapisie ok. 9,9 KB, zapis wektorowy ok. 4,5 KB (1657 punktów z 7410), pierwotna lista liczb ok. 93 KB.

To zwykła heurystyka, a nie zatwierdzony algorytm medyczny; nie była walidowana klinicznie.

**Wierność odtworzenia przebiegu.** Zmierzono ją na jednym wydruku z zegarka, porównując odtworzenie z obrazu strony z natywnymi obrazami pasów zapisanymi w PDF (454 ppi, czyli jeden piksel to ok. 2,2 ms i 0,006 mV). Wzorzec nie jest „prawdą” o sercu, tylko najlepszą dostępną kopią tego, co narysował zegarek:

| Rozdzielczość renderowania | Błąd średniokwadratowy | R (amplituda) | Tętno z odtworzenia |
|---|---|---|---|
| 200 dpi (do wersji 0.6.3) | 0,12 mV | o ok. 2–3% większa | różnica poniżej 0,3/min |
| 300 dpi (obecnie w aplikacji) | 0,05 mV | o ok. 2–3% większa | różnica poniżej 0,3/min |
| 400 dpi | 0,03–0,04 mV | o ok. 2–3% większa | różnica poniżej 0,3/min |

Zapis wektorowy (punkt 9) nie pogarsza tego istotnie: błąd średniokwadratowy względem wzorca wynosi 0,050 mV tak samo jak dla równych próbek (0,049 mV), bo o błędzie decyduje rastrowa kopia linii w PDF, a nie sposób zapisu. Największe odchylenia (do ok. 1–2 mV) są w miejscach zakłóceń ruchowych, gdzie linia na wydruku ma strome skoki. Zawyżenie amplitudy R o 2–3% wynika z grubości linii na obrazie. Tętno, liczba uderzeń i odstępy RR zgadzają się z wzorcem, co jest najważniejsze dla analizy rytmu; **drobne szczegóły kształtu (np. początek i koniec załamków) nie są odwzorowane dokładnie** i nie nadają się do pomiarów.

**Ograniczenia, które trzeba znać:**
- To narzędzie pomocnicze do rozmowy z lekarzem, a nie diagnoza ani wyrób medyczny. Brak oznaczeń nie wyklucza choroby serca.
- Aplikacja **nie mierzy odstępu QT ani odcinka ST** i nie rozpoznaje niedokrwienia ani zawału. Zegarek daje jedno odprowadzenie, które do tego się nie nadaje. Przy lekach wydłużających QT albo zaburzeniach potasu potrzebne jest pełne EKG z gabinetu.
- Odtworzenie z obrazu jest przybliżone (rozdzielczość czasowa około 5 ms). Przed zaufaniem analizie porównaj przebieg na ekranie z wydrukiem.
- Algorytm sprawdzono na jednym prawdziwym wydruku z zegarka i na sygnałach syntetycznych. Inne marki i formaty wydruków mogą nie działać (aplikacja zgłosi, że nie znalazła przebiegu). Nie ma walidacji klinicznej.
- Przy bólu w klatce piersiowej, duszności, omdleniu lub kołataniu serca nie czekaj na analizę: skontaktuj się z lekarzem lub zadzwoń pod 112.

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

Najnowszy APK jest w zakładce **Releases** repozytorium, w wydaniu **Asystent leków <wersja> (kompilacja N)** (sekcja **Assets**: plik `asystent-lekow-<wersja>.apk` i suma kontrolna `.sha256`). Każde wypchnięcie zmian na gałąź tworzy nowe wydanie testowe (prerelease) z tagiem `v<wersja>-b<N>`; zostaje 5 ostatnich. Ten sam plik jest też w artefakcie przebiegu **Actions → Zbuduj APK**.

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

Nowy APK instaluje się **na wierzch** (bez odinstalowywania) i dane zostają, ale tylko wtedy, gdy każda wersja jest podpisana **tym samym kluczem**. Dlatego workflow podpisuje APK stałym kluczem z sekretu repozytorium `DEBUG_KEYSTORE_BASE64`. Build przekazuje plik klucza do Gradle jawnie i po zbudowaniu sprawdza, że podpis APK zgadza się z tym kluczem (gdy się nie zgadza, przebieg kończy się błędem). Bez sekretu każdy przebieg generuje nowy klucz i Android odmawia aktualizacji (workflow ostrzega o tym, a opis wydania zawiera uwagę).

### Stały klucz podpisu (jednorazowa konfiguracja)

1. Wygeneruj klucz (albo użyj gotowego pliku `.keystore`):

   ```
   keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
     -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Asystent lekow,O=Asystent lekow,C=PL"
   base64 -w0 debug.keystore
   ```

2. Wynik `base64` wklej w repozytorium: **Settings → Secrets and variables → Actions → New repository secret**, nazwa `DEBUG_KEYSTORE_BASE64`.
3. **Zachowaj kopię klucza** (plik albo napis base64) w menedżerze haseł, poza repozytorium. Bez niego nie da się podpisać aktualizacji tym samym podpisem, a zgubienie klucza oznacza jednorazową reinstalację aplikacji (z kopii zapasowej danych). Klucz nigdy nie może trafić do repozytorium (`*.keystore` jest w `.gitignore`, a repozytorium jest publiczne).
4. Po poprawnej konfiguracji opis każdego wydania zawiera linię „Podpis: stały klucz” z odciskiem certyfikatu (SHA-256). Ten odcisk ma być taki sam w każdej wersji.

Przy **pierwszym** przejściu na stały klucz trzeba raz odinstalować dotychczasową aplikację, bo była podpisana innym, przypadkowym kluczem: najpierw zrób kopię (Ustawienia → Dane), potem odinstaluj, zainstaluj nową wersję i wczytaj kopię. Później aktualizacje wchodzą na wierzch.

### Aktualizacje przez Obtainium

[Obtainium](https://obtainium.imranr.dev/) sprawdza wydania w GitHubie i instaluje aktualizacje. Każda kompilacja ma własne wydanie z unikalnym tagiem w postaci `v<wersja>-b<numer>` (np. `v0.5.1-preview-b16`), więc Obtainium widzi każdą nową kompilację. Zostaje 5 ostatnich wydań kompilacji, starsze są usuwane automatycznie.

1. W Obtainium: **Dodaj aplikację**, adres `https://github.com/Jack4B/leki`.
2. Włącz **Uwzględniaj wersje wstępne** (Include prereleases), bo wydania są oznaczone jako testowe.
3. Jeśli Obtainium zgłosi brak APK w najnowszym wydaniu, włącz „Wróć do starszych wydań” (fallback to older releases) albo ustaw filtr tytułów wydań `^Asystent` (wydanie „Zrzuty ekranów” zawiera tylko obrazki).
4. Pierwszą instalację zrób przez Obtainium, ale pamiętaj o jednorazowej reinstalacji z poprzedniego punktu, jeśli masz zainstalowaną wersję podpisaną innym kluczem.

## Wydanie aplikacji (procedura)

Dziś dystrybucja jest prywatna: GitHub Releases i Obtainium (opis wyżej). Do publicznego wydania w sklepie potrzeba więcej. Poniższa lista jest przeglądem; wymagania sklepów się zmieniają, więc przed zgłoszeniem sprawdź aktualne zasady w Play Console.

**Co jest gotowe:** stałe `applicationId` (`pl.asystent.leki`), rosnący `versionCode` (numer przebiegu CI), `targetSdk 35`, 64-bitowe biblioteki (arm64-v8a), brak uprawnienia INTERNET (sprawdzane w CI), `allowBackup=false`.

**Google Play (kolejność):**
1. Konto w Google Play Console (jednorazowa opłata, weryfikacja tożsamości). Konta osobiste założone w ostatnich latach muszą przed publikacją przejść **zamknięty test** (kilkanaście osób przez 14 dni).
2. **Własny klucz wysyłkowy (upload key)** zamiast klucza debug. Klucz o haśle „android” jest tylko do instalacji prywatnej. Klucz wygeneruj osobno, z silnym hasłem, i trzymaj poza repozytorium; w Play włącz Play App Signing.
3. **Plik AAB** (`./gradlew :app:bundleRelease`) zamiast APK. Warto włączyć minifikację R8 i sprawdzić aplikację po niej (teraz jest wyłączona).
4. **Uprawnienie `USE_EXACT_ALARM`** z manifestu nie przejdzie weryfikacji dla przypomnień o lekach (Play dopuszcza je dla budzików i kalendarzy). Do Play trzeba użyć `SCHEDULE_EXACT_ALARM` z deklaracją albo alarmów niedokładnych.
5. **Polityka prywatności** pod publicznym adresem (wymagana przy danych zdrowotnych, także gdy zostają na telefonie), formularz **Bezpieczeństwo danych** (aplikacja nie zbiera i nie wysyła danych), **deklaracja aplikacji zdrowotnych**, klasyfikacja treści.
6. **Wyrób medyczny:** aplikacja ma tylko zapisywać dane i porządkować je, bez diagnozy i zaleceń. Oznaczanie wyników jako „podwyższone” i dołączanie analizy AI wymaga zachowania zastrzeżeń w opisie i w aplikacji (są). Przed publicznym wydaniem w UE warto skonsultować klasyfikację z prawnikiem (MDR, RODO przy danych zdrowotnych).
7. Materiały do sklepu: ikona 512×512, grafika 1024×500, zrzuty ekranu (można wziąć z testów renderowania), opis, adres kontaktowy.
8. Każda kolejna wersja: wyższy `versionCode`, wpis w CHANGELOG, aktualizacja przez ścieżkę testową, potem produkcyjną (najlepiej wdrożenie etapowe).

**Inne drogi:** F-Droid wymaga wyłącznie wolnego oprogramowania, a ML Kit (OCR) jest zamknięty, więc wymagałoby to zamiany silnika OCR; IzzyOnDroid ma łagodniejsze zasady (warto zapytać). Najprostsza trwała droga bez sklepu to Obtainium ze stałym kluczem.

## Struktura projektu

```
core/          logika bez zależności od Androida (Kotlin/JVM): model danych, harmonogram, statystyki,
               raporty (lekarz, AI), kopie zapasowe i szyfrowanie, analiza EKG (odtworzenie przebiegu z obrazu, wykrywanie uderzeń, odstępy RR), geometria wykresów (w tym przewijany wykres ciśnienia), czyszczenie Markdown i LaTeX z odpowiedzi AI, przypomnienia,
               odczyt wyników z tekstu OCR (LabScan)
core/src/test  testy jednostkowe (w tym rozpoznawanie wyników badań) i dane wzorcowe z poprzedniej wersji (fixtures)
app/           aplikacja na Androida (Jetpack Compose): ekrany, magazyn danych, alarmy, druk, udostępnianie,
               ocr/ (ML Kit, wczytywanie zdjęć i PDF)
app/src/test   zrzuty ekranów głównych widoków (Paparazzi, render na JVM), publikowane w wydaniu „zrzuty-ekranow”
assets/        źródłowe rysunki ikony (SVG)
scripts/       generatory: ikony launchera (make-icons.js) i ikon interfejsu Phosphor (gen-icons.js), tylko dev
.github/       workflow budujący APK i publikujący go w Releases
```

## Licencje i składniki zewnętrzne

Lista składników dołączonych do aplikacji, ich licencje i noty praw autorskich są w pliku `THIRD-PARTY-NOTICES.md` i w aplikacji (O aplikacji → Licencje i źródła algorytmów); kod danych: `core/.../Licenses.kt`. Algorytmy EKG napisano od zera na podstawie publikacji, bez kopiowania kodu. Przy każdym dodaniu cudzego kodu, modelu, danych lub zależności aktualizuj ten plik, listę w aplikacji, README i CHANGELOG.

**HeartKit (AmbiqAI), BSD-3-Clause:** oceniony, **niedołączony**. Licencja pozwala na użycie komercyjne i w zamkniętej aplikacji pod warunkiem zachowania noty i zastrzeżenia w dystrybucji (dla APK: w aplikacji i w `THIRD-PARTY-NOTICES.md`) oraz niewykorzystywania nazwy Ambiq do promocji. Przed dołączeniem trzeba jeszcze sprawdzić licencje modeli i zbiorów treningowych oraz zależności.

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
- **Modele HeartKit w TensorFlow Lite** (migotanie przedsionków, klasyfikacja uderzeń) jako drugi głos obok reguł: wstrzymane, bo pliki modeli (Model Zoo) są niedostępne ze środowiska budowania. Potrzebne: pliki `.tflite` z opisem wejścia (częstotliwość próbkowania, długość okna, normalizacja, klasy) i licencją, pobrane z Model Zoo i dołączone do projektu; wtedy można je sprawdzić na syntetycznych sygnałach i na zapisie z zegarka, przed dodaniem zależności TensorFlow Lite. Uwaga: modele były trenowane na EKG z plastra na klatce piersiowej, więc na odtworzonych zapisach z zegarka mogą działać gorzej.
- Wykres ciśnienia: oznaczanie kolorem pomiarów powyżej progu.
- Ręczne kadrowanie zdjęcia przed rozpoznaniem.

## Znane ograniczenia wersji preview

- APK jest podpisany kluczem debug i nie jest przeznaczony do Google Play.
- Wersja bez minifikacji kodu (większy plik, mniejsze ryzyko błędów); optymalizacja później. Rozmiar APK to kilkadziesiąt MB, głównie przez model i biblioteki rozpoznawania tekstu; działa tylko na telefonach arm64 i armeabi-v7a.
- Interfejs tylko po polsku.
- Raport dla lekarza liczy zgodność z planem tak jak raport dla AI: dzisiejsze dawki, których pora jeszcze nie minęła, nie są brakiem. Od wersji 0.5.2 różni się to od wyniku poprzednich wersji (JavaScript), więc wzorcowe pliki raportów w testach mają w tych liczbach nowe wartości.
- Analiza AI w raporcie to tekst wklejony przez użytkownika: aplikacja go nie sprawdza, tylko porządkuje zapis. Wzory LaTeX są upraszczane do zwykłego tekstu (rzadkie konstrukcje mogą zostać w surowej postaci).
- Rozpoznawanie wyników z obrazu zależy od jakości zdjęcia i układu dokumentu; aplikacja pokazuje wszystko do sprawdzenia przed zapisem, ale nie gwarantuje bezbłędnego odczytu. Wyników zapisanych w układzie innym niż tabela (nazwa, wynik, jednostka w jednym wierszu) może nie rozpoznać.
- Aplikacja była sprawdzana w testach logiki i w kompilacji na CI, ale nie na różnych modelach telefonów. Zgłaszaj błędy.
- Synchronizacji z własnym serwerem (obecnej w dawnej wersji przeglądarkowej) nie ma. Przenoszenie danych odbywa się przez kopie zapasowe.

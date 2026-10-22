# Zmiany

## 0.8.5-preview

Poprawki odczytu leku ze zdjęcia po teście na prawdziwym opakowaniu (nazwa handlowa w jednym wierszu z mocą, pod nią nazwa substancji; odczyt wybrał nazwę substancji albo szum z OCR).

- **Nazwa handlowa w wierszu z mocą** („Nazwa® 20 mg”): moc jest wycinana przed oceną napisu, więc wiersz nie odpada jako „zawierający mg”. Wiersze ze znakiem ® lub ™ są na pierwszym miejscu, potem największe napisy.
- **Substancja czynna pod nazwą handlową** (bez etykiety „Substancja czynna”): wiersz wyglądający na nazwę substancji (sól, np. „…chlorowodorek”, albo rzeczownik w dopełniaczu) trafia do pola Substancja, a nie jest proponowany jako nazwa leku.
- **Śmieci z OCR** (wąskie, wysokie ramki, napisy bez samogłosek, wiersze z przecinkiem) nie są już proponowane jako nazwa.
- **„Pokaż cały rozpoznany tekst”** w edytorze: wszystkie odczytane wiersze od góry jako przyciski, dotknięcie użyje wiersza jako nazwy (gdy odczyt się pomyli, można wskazać właściwy napis zamiast przepisywać). Dzięki temu widać też, co dokładnie rozpoznał OCR.
- 3 nowe testy na zmyślonych tekstach opakowań (zmyślone nazwy; prawdziwych nazw leków w repozytorium nie ma).

## 0.8.4-preview

- Dokumentacja: w README (sekcja Plany) lista kontrolna do testów na telefonie, rzeczy do zrobienia po testach, pomysły do decyzji i rozstrzygnięcia (HeartKit). Bez zmian w aplikacji.

## 0.8.3-preview

- **Wydruk PDF EKG oznacza miejsca zdarzeń na przebiegu.** Każde zdarzenie z listy (przedwczesne uderzenie, przerwa w rytmie, zakłócenie) ma na wykresie **numer** w kółku, a przedwczesne uderzenia i przerwy także **pomarańczowy pas z przerywanymi brzegami** (przedwczesne: ±0,35 s wokół uderzenia; przerwa: od uderzenia przed nią do następnego); zakłócenia zostają na szarym tle z numerem. Te same numery są na liście zdarzeń nad wykresem (np. „1. Przedwczesne uderzenie, 10,6 s”), a pod wykresem jest objaśnienie. Zdarzenie przechodzące przez granicę pasa 10 s ma numer w obu pasach. Dotyczy zarówno osobnego dokumentu EKG (A4 poziomo), jak i sekcji EKG w raporcie dla lekarza. Czytelne także w druku czarno-białym (numery i przerywane brzegi).
- 1 nowy test (numery, pasy, lista, zapis bez zdarzeń).

## 0.8.2-preview

- **Jedna lista leków ze stanem zapasu (bez dublowania).** Ustawienia → Leki to teraz jedna lista: nazwa, dawkowanie, a gdy zapas jest śledzony, linia „Zapas: zostało N, starczy do …” z paskiem. Dotknięcie leku otwiera edycję, gdzie uzupełniasz zapas. Usunięto osobną kartę „Stan zapasów”.
- **Kolory paska zapasu:** zielony (wystarczy), **pomarańczowy** (poniżej progu ostrzeżenia z Ustawień → Przypomnienia), **czerwony** (krytycznie mało: połowa progu, najwyżej 3 dni, co najmniej 1 dzień; przy wyłączonym ostrzeganiu pasek zawsze zielony). Ten sam kolor ma linia z opisem zapasu.
- **Ekran Leki bez długiej karty na dole:** zamiast niej odnośnik „Sprawdź zapasy leków ›” (otwiera Ustawienia → Leki). Gdy coś się kończy, na górze ekranu Leki jest ostrzeżenie z odnośnikiem „Sprawdź zapasy i uzupełnij ›”.
- 1 nowy test (poziomy kolorów paska).

## 0.8.1-preview

- **Podgląd stanu zapasów leków.** Wpisana liczba tabletek nie była nigdzie widoczna poza linią przy dawce. Teraz jest karta **„Zapas leków”** na dole ekranu Leki oraz **„Stan zapasów”** na górze Ustawienia → Leki (dotknięcie wiersza otwiera edycję leku, żeby poprawić liczbę po przeliczeniu opakowań). Każdy wiersz pokazuje, ile zostało, na ile dni starczy według planu i do kiedy, z paskiem (skala do 30 dni; pomarańczowy poniżej progu ostrzeżenia). Lista jest posortowana od leku, któremu zabraknie najszybciej, i nie zawiera leków zakończonych. Linia „Zapas” jest też przy każdym leku na liście w Ustawieniach.
- 1 nowy test (kolejność i zakończone leki).

## 0.8.0-preview

- **Dodawanie leku ze zdjęcia opakowania** (Ustawienia → Leki → „Ze zdjęcia”, także w pustej liście leków): aparat albo galeria, rozpoznawanie tekstu na telefonie (ML Kit, nic nie jest wysyłane). Z tekstu odczytywane są **nazwa** (największy napis na opakowaniu, z alternatywami do wyboru), **moc** (np. 10 mg, 5 mg/ml), **substancja czynna**, **jednostka dawki** (tabl., kaps., ml, sasz., plaster…) i **liczba sztuk w opakowaniu**, która od razu ustawia zapas leku. Wynik otwiera się w edytorze leku jako propozycja z ostrzeżeniem „sprawdź”; dawkowanie ustawia użytkownik. Aplikacja nie ma bazy leków (działa bez internetu), więc nie podpowiada dawkowania ani interakcji, tylko odczytuje to, co jest wydrukowane na opakowaniu.
- Logika (`MedScan` w module `core`): wybór nazwy po wysokości napisu z odrzucaniem napisów pomocniczych (tabletki, producent, data ważności, numery), normalizacja WIELKICH LITER, obsługa płynów, kapsułek i plastrów, odczyt substancji także z następnej linii i z ulotkowego „Substancją czynną leku jest…”. 6 nowych testów na zmyślonych tekstach opakowań.

## 0.7.3-preview

- Trend tętna EKG: brakował pierwszego podpisu daty (północ dnia pierwszego zapisu wypadała przed osią). Naprawione.

## 0.7.2-preview

- **Trend tętna z zapisów EKG jest skalowalny jak wykres ciśnienia:** wybór zakresu (30 dni, 90 dni, rok, wszystko), stała skala czasu (co najmniej 7 dp na dzień), więc przy wielu zapisach wykres jest szerszy i przewija się palcem w bok (otwiera się na najnowszych), a oś tętna zostaje po lewej. Podpisy dat dobierane do skali. Punkty zapisów z oznaczeniami są pomarańczowe (wcześniej podpis mówił błędnie „czerwone”).

## 0.7.1-preview

- Poprawka testów zrzutów ekranu (zdublowana nazwa testu), które w 0.7.0 nie powstały. Bez zmian w aplikacji.

## 0.7.0-preview

Siedem nowych funkcji.

1. **Blokada aplikacji** (Ustawienia → Blokada aplikacji): odcisk palca, twarz albo blokada ekranu telefonu przez systemowe okno uwierzytelniania (AndroidX Biometric). Włączenie i wyłączenie wymagają potwierdzenia. Opóźnienie: zaraz, 30 s lub 5 min (czas liczony zegarem monotonicznym); opcja ukrycia w ostatnich aplikacjach i blokady zrzutów ekranu. Ustawienia są tylko na tym telefonie, poza kopią zapasową. Ekran blokady zasłania całą aplikację, także edytory i komunikaty, a czytnik ekranu nie czyta tego, co jest pod spodem.
2. **Zapas leków:** pole „Ile zostało w opakowaniu” w edycji leku, odliczanie odhaczonych dawek, prognoza „starczy na około N dni” (według planu, z dawkami z kolejnych dni), baner na ekranie Leki, linia „Zapas” przy leku. Nowe pola leku w kopii (`stock`, `stockDate`; starsze pliki wczytują się bez zmian).
3. **Powiadomienia o kopii zapasowej i kończącym się zapasie** (niezależne od przypomnień o dawkach), z progami w Ustawieniach → Przypomnienia (zapas: 7 dni, kopia: 30 dni, 0 = wyłączone). Planowane na dzień, w którym stają się aktualne, potem codziennie do załatwienia sprawy. Neutralna treść na zablokowanym ekranie. Nowe ustawienia w kopii (`remind.stock`, `remind.backup`).
4. **Trend tętna z zapisów EKG:** wykres nad listą zapisów (od dwóch zapisów), z wyróżnionymi punktami zapisów z oznaczeniami do omówienia.
5. **Eksport CSV** (Ustawienia → Kopie zapasowe i dane): ciśnienie, wyniki badań, dawki z ostatnich 90 dni, podsumowanie EKG. Średnik, przecinek dziesiętny, UTF-8 z BOM, CRLF, ochrona przed formułami arkusza.
6. **Skrót na ikonie i widżet „dodaj pomiar ciśnienia”:** otwierają ekran dodawania pomiaru. Widżet pokazuje tylko czas ostatniego pomiaru (bez wartości) i odświeża się po zapisie danych.
7. **Wykres ciśnienia: czerwone punkty** dla pomiarów na progu podwyższonego ciśnienia lub powyżej (próg z Ustawień), z podpisem pod wykresem.

Inne: nowa zależność `androidx.biometric` i `androidx.fragment` (Apache-2.0, wpis w licencjach); uprawnienie `USE_BIOMETRIC`; aplikacja nadal bez uprawnienia INTERNET. Nowe zrzuty ekranów w CI (dane, blokada, trend EKG). 12 nowych testów (zapas, przypomnienia systemowe, CSV, trend EKG, zasady blokady, kopia z nowymi polami).

## 0.6.12-preview

- **Szybkie usuwanie EKG z listy.** Przy każdym zapisie w Badania → EKG jest przycisk „Usuń” (jak przy pomiarach ciśnienia), z pytaniem o potwierdzenie. Nie trzeba wchodzić w zapis. Stuknięcie w sam wiersz nadal otwiera zapis.

## 0.6.11-preview

- **Regresja z 0.6.9: głębokie skoki w przeciwną stronę (ruch, odklejenie elektrody) były liczone jako uderzenia** (na wykresie pojawiały się trójkąty przy zakłóceniach, a z nimi fałszywe odstępy RR). Ratowanie dużych uderzeń przed uznaniem za zakłócenie dotyczy teraz tylko wychyleń tego samego kierunku co zwykły QRS; skoki przeciwne zostają zakłóceniem. Test: duże uderzenie tego samego kierunku jest uderzeniem, głęboki skok przeciwny jest zakłóceniem i nie tworzy uderzenia.
- **Podgląd EKG: opis „Pokazane na wykresie” mówi prawdę.** Pojawia się tylko, gdy zdarzenie faktycznie mieści się w widocznym oknie; po przesunięciu widoku karta wraca do „Pokaż na wykresie ›”, a nad mapą jest przycisk „Wróć do zdarzenia: …”.
- **Mniej przypadkowych zmian widoku:** przesuwanie jednym palcem używa standardowego detektora z progiem ruchu (dotknięcie i pionowe przewijanie ekranu nic nie zmieniają), powiększanie to osobny detektor dwóch palców, a mapa zapisu reaguje na przeciągnięcie, nie na samo dotknięcie (wcześniej przypadkowy dotyk przy przewijaniu strony mógł przestawić widok na koniec zapisu).
- Nowy zrzut ekranu `ekg-powiekszenie-zdarzenie` (widok otwarty na pierwszym zdarzeniu) do kontroli w CI.

## 0.6.10-preview

- **Decyzja: zostajemy przy własnych algorytmach EKG.** Modele HeartKit (TensorFlow Lite) nie będą dodawane: były trenowane na EKG z plastra i mogłyby pogarszać odczyt zapisów odtworzonych z zegarka, a własne reguły są jawne, opisane w aplikacji i sprawdzone testami. Usunięto je z planów w README; licencja HeartKit pozostaje w wykazie jako oceniona i niedołączona. Bez zmian w kodzie.

## 0.6.9-preview

Poprawki z niezależnego przeglądu modułu EKG i całej aplikacji (każdą uwagę sprawdzono w kodzie, kilka potwierdzono uruchomieniem).

**EKG, analiza**
- **Przerwy dłuższe niż 3 s były niewidoczne** (zapis z przerwą 3,2 s lub 4,5 s dostawał „Rytm regularny”). Odstępy 3–10 s bez zakłócenia są teraz zgłaszane jako przerwa (nie wchodzą do SDNN i RMSSD).
- **Duże, szerokie lub odwrócone uderzenie (np. komorowe) było uznawane za zakłócenie**, więc wychodziło „Rytm regularny”. Wychylenie z zespołem o stromości zwykłego QRS w pobliżu (i amplitudą co najmniej 0,5 mV) zostaje uderzeniem do oceny; powolne falowanie i szum nadal są zakłóceniem.
- **Zapis słabej jakości** (zakłócenia w ponad 35% czasu albo niska korelacja kształtu) nie dostaje już oznaczenia „Rytm regularny”, tylko „Zapis słabej jakości”.
- **Błędna skala czasu dla stron poziomych:** obraz był ograniczany osobno w szerokości i wysokości, a skala liczona z nominalnych 300 dpi, więc A4 poziomo dawał czas skrócony o ok. 15% i tętno zawyżone. Teraz jedna skala dla obu osi i faktyczna rozdzielczość obrazu. Edytor ostrzega, gdy tętno z przebiegu różni się od tętna z zegarka o ponad 15%.
- Pasy dużo krótsze od typowego (ikona, plama, pojedyncza kreska) nie są doklejane do przebiegu (przesuwały całą oś czasu). Zapisy krótsze niż 3 s nie są importowane.
- **Uszkodzony zapis wektorowy** w kopii (przepełnienie indeksu) rzucał wyjątek i unieważniał wczytywanie całego pliku; teraz takie jedno EKG jest pomijane, a reszta danych się wczytuje.
- Mediana ruchoma (usuwanie dryfu) liczona oknem przesuwnym zamiast sortowania okna dla każdej próbki; wynik analizy jest zapamiętywany (po identyfikatorze i zawartości), więc lista EKG, raporty i raport dla AI nie liczą go od nowa przy każdym odświeżeniu.
- Opis metody zgodny z kodem: stromość (energia pochodnej 4×, nachylenie 2×), rozdzielczość 4 ms przy 250 Hz, zakres odstępów RR 0,25–3,0 s, warunki koloru piksela, pasy poziome i pionowe, ograniczenie słabych uderzeń.
- Liczby w tekstach z kropką dziesiętną zawsze w układzie `Locale.ROOT`; wykres w PDF: zakres amplitudy dopasowany do wysokości pasa (nie wchodzi w następny pas); informacja, gdy zapis jest dłuższy niż 30 s (wykres pokazuje pierwsze 30 s).

**Interfejs**
- Edytory pełnoekranowe uwzględniają wcięcie ekranu (`safeDrawing`), czytnik ekranu nie czyta zasłoniętej aplikacji, komunikaty są widoczne nad klawiaturą.
- Badania, zakładka EKG: bez przycisków „Skanuj wyniki” i „Dodaj ręcznie” (dotyczą wyników badań). „Wstecz” w szczegółach parametru wraca do listy badań.
- Import EKG: komunikat, gdy nie odczytano daty z wydruku; ostrzeżenie o zapisie z tą samą datą i godziną (duplikat). Potwierdzenie wczytania kopii podaje też liczbę wyników badań i EKG.
- Raport dla lekarza: zdanie „Brak zapisów EKG w wybranym okresie” (gdy EKG są, ale poza okresem) i informacja, że pokazano najnowsze z większej liczby; podpowiedź o limitach (6 dla lekarza, 5 dla AI).
- „Kopiuj JSON” nie wysypuje aplikacji przy bardzo dużej kopii (podpowiedź: „Zapisz JSON”); pliki tymczasowe udostępnienia są usuwane przy starcie aplikacji.
- Cele dotykowe co najmniej 48 dp (małe przyciski, „Usuń to EKG”, „Jak to jest liczone”), mapa zapisu wyższa; tekstowe wartości statystyk (np. „umiarkowana”) mniejszą czcionką, bez łamania wyrazu.
- 5 nowych testów (przerwy powyżej 3 s, duże i odwrócone uderzenia, analiza przy 250 Hz, uszkodzony zapis wektorowy w kopii, ikona doklejana jako pas) i rozszerzony test raportu.

## 0.6.8-preview

- **Poprawka: dotknięcie nieprawidłowości pokazywało zły fragment wykresu.** Podgląd EKG był przewijalnym paskiem, którego położenie zależało od chwilowego układu ekranu, więc przejście do zdarzenia bywało nietrafione (np. do zakłóceń na końcu zapisu zamiast do przedwczesnego uderzenia). Podgląd przepisano: położenie okna (sekunda przy lewej krawędzi i liczba widocznych sekund) jest teraz stanem, a rysowana jest tylko widoczna część. Dotknięcie karty zawsze ustawia zdarzenie na środku okna (co najmniej 4 s, dla dłuższych zdarzeń z zapasem) i zaznacza je pasem.
- **Przeciąganie i powiększanie:** jeden palec przesuwa wykres poziomo (ruch pionowy nadal przewija ekran), dwa palce powiększają wokół punktu dotyku (od całego zapisu do 2 s). Przyciski „Całość, 10 s, 5 s, 2 s” zachowują środek bieżącego zdarzenia.
- **Mapa zapisu** pod wykresem: pokazuje okno i zdarzenia (żółte: wymagające uwagi); dotknięcie przeskakuje do wskazanego miejsca.
- Okno podglądu (`EcgViewport`) leży w module `core`: ograniczanie do zapisu, przejście do zdarzenia, powiększanie z zachowaniem sekundy pod palcem, przesuwanie.
- Podpis osi czasu przy prawej krawędzi nie jest już ucinany.
- 3 nowe testy (przejście do każdego zdarzenia, w tym przy brzegach zapisu; powiększanie; przesuwanie i krótkie zapisy).

## 0.6.7-preview

- **Eksport EKG do PDF.** W edytorze zapisu przycisk „Eksport PDF” (obok „Powiększ przebieg”) i w zakładce Raport → Dla lekarza lista zapisów EKG z wybranego okresu z przyciskiem „PDF” przy każdym. Powstaje samodzielny dokument jednego zapisu: **A4 poziomo, przebieg w skali papieru** (25 mm/s i 10 mm/mV, 100% skali, więc 10 s to 250 mm), wskaźniki, wyniki ogólne i zdarzenia z czasem (przedwczesne uderzenia, przerwy, zakłócenia), notatka, opis metody i zastrzeżenie, że to nie jest badanie EKG ani diagnoza. Zapis do PDF lub druk przez okno systemowe, jak w raporcie dla lekarza. Do eksportu trafiają bieżące wartości z formularza (data, godzina, tętno, ocena, notatka), także dla zapisu jeszcze niezapisanego.
- **Sekcja EKG w raporcie dla lekarza** ma teraz też zdarzenia z czasem (zamiast powtarzania ich w ogólnych wynikach).
- Wydruk w skali wymaga drukowania „w 100%” (bez dopasowania do strony) i pozycji poziomej; aplikacja ustawia A4 poziomo.
- 1 nowy test (dokument EKG: skala papieru, A4 poziomo, zdarzenia, ucieczka znaków HTML, sekcja w raporcie).

## 0.6.6-preview

- **Lista nieprawidłowości pod wykresem w podglądzie EKG.** Pod powiększanym przebiegiem są teraz karty z opisem każdej wykrytej nieprawidłowości i jej położeniem w czasie: przedwczesne uderzenia (z odstępami RR i opisem kształtu), przerwy w rytmie i zakłócenia pominięte w analizie. **Dotknięcie karty powiększa wykres, przewija go do tego miejsca i zaznacza je** (pas z pionowymi liniami). Ogólne wyniki, które dotyczą całego zapisu (wolne lub szybkie tętno, rytm nieregularny, odstępy jak przy migotaniu), mają osobne karty z przyciskiem „pokaż całość”. Gdy nic nie wykryto, widać o tym informację.
- Analiza zwraca teraz zdarzenia z czasem (`EcgEvent`), posortowane w kolejności zapisu.
- 1 nowy test (czasy i opisy zdarzeń, przerwa, zakłócenie, kolejność).

## 0.6.5-preview

- **Zapis EKG wektorowy: wierny i mały.** Przebieg (250 próbek/s) jest zapisywany jako punkty załamania linii (uproszczenie Ramera, Douglasa i Peuckera, błąd pionowy do 0,02 mV, czyli ok. 2 piksele wydruku w 300 dpi): na zmierzonym wydruku 30 s to ok. 4,5 KB (1657 punktów z 7410) wobec 9,9 KB poprzedniego zapisu z równymi próbkami i 93 KB pierwotnej listy liczb. Pomiar: błąd względem natywnych obrazów z PDF to 0,050 mV, tak samo jak dla równych próbek (0,049 mV), bo o wierności decyduje rastrowa kopia linii w PDF, a nie zapis. Szczyty R zostają zachowane. Przebieg w pamięci jest sprowadzany do punktu stałego zapisu, więc wielokrotne zapisywanie i odczyt nie zmieniają go ani o jedną próbkę. Zapisy w starszych formatach (lista liczb, zapis różnicowy) wczytują się i są przepakowywane.
- **Mniej pamięci przy renderowaniu 300 dpi.** Strona PDF nie jest trzymana jako duża bitmapa ani jako tablica pikseli: czytana wierszami zamienia się od razu na maskę jednobajtową (ok. 8,7 MB zamiast 35 MB tablicy pikseli) i jest zwalniana, a OCR nagłówka ma osobny, mały render 200 dpi. Szacunkowy szczyt pamięci spada z ok. 100 MB do ok. 44 MB (wartość policzona z rozmiarów, nie zmierzona na telefonie).
- Częstotliwość próbkowania zapisu 250 Hz (wcześniej 125 Hz): dokładniejsze położenie szczytów R i pomiar szerokości QRS (rozdzielczość 4 ms zamiast 8 ms).
- 4 nowe testy (granica błędu i rozmiar zapisu wektorowego, brak dryfu przy wielokrotnym zapisie, uszkodzone dane, zgodność digitalizacji z maski i z pikseli).

## 0.6.4-preview

- **Podgląd EKG z powiększaniem.** W edytorze zapisu przycisk „Powiększ przebieg” otwiera jeden ciągły pas całego zapisu: przesuwasz go palcem, dwoma palcami powiększasz i zmniejszasz (od całego zapisu do 2 s na ekranie), a przyciski „Całość, 10 s, 5 s, 2 s” ustawiają skalę jednym ruchem. Siatka jak na papierze (mała kratka 0,04 s i 0,1 mV), trójkąty przy uderzeniach, szare tło przy zakłóceniach, podpisy sekund.
- **Renderowanie PDF w 300 dpi** (wcześniej 200): według pomiaru błąd odtworzenia przebiegu spada z 0,12 do 0,05 mV (README). Gdy brakuje pamięci, aplikacja sama wraca do 200 dpi. OCR nagłówka działa na zmniejszonej kopii 200 dpi, żeby nie trzymać w pamięci kilku dużych bitmap.
- **Licencje składników zewnętrznych.** Nowy plik `THIRD-PARTY-NOTICES.md` (pełne teksty i obowiązki) oraz sekcja „Licencje i źródła algorytmów” w O aplikacji: Phosphor Icons (MIT, z notą praw autorskich), Compose i AndroidX (Apache-2.0), Kotlin i biblioteki kotlinx (Apache-2.0), ML Kit (warunki Google), publikacje, na których oparto algorytmy. Zapisano też dane o HeartKit (BSD-3-Clause, Copyright (c) 2022 Ambiq) wraz z obowiązkami na wypadek jego dołączenia; **HeartKit nie jest dołączony do aplikacji**.
- **Modele HeartKit (TensorFlow Lite) nie zostały dodane**: pliki modeli są w Model Zoo, który nie jest dostępny ze środowiska budowania, więc nie dało się sprawdzić formatu wejścia, normalizacji ani klas ani przetestować wnioskowania. Szczegóły i warunki w README (Plany).
- 2 nowe testy (digitalizacja w skali 300 dpi w rozsądnym czasie, spójność danych o licencjach).

## 0.6.3-preview

- **Zwarty zapis EKG w kopii zapasowej.** Przebieg był zapisywany jako czytelna lista liczb i jedno 30-sekundowe EKG zajmowało w kopii około 93 KB (100 zapisów to prawie 9 MB, a plik danych jest serializowany przy każdej zmianie, ok. pół sekundy). Teraz przebieg jest pakowany (różnice kolejnych próbek, zmienna długość, base64) i zajmuje około 8 KB, bez utraty dokładności. Zapisy w starym formacie wczytują się i przepakują przy następnym zapisie danych; uszkodzony zapis jest pomijany, a reszta kopii się wczytuje.
- **Zmierzona wierność odtworzenia przebiegu** (opis w README): porównanie z natywnymi obrazami pasów zapisanymi w PDF (454 ppi) na jednym wydruku z zegarka.
- 2 nowe testy (pakowanie bez strat i rozmiar, wczytywanie starego formatu i uszkodzonego zapisu).

## 0.6.2-preview

- **EKG: wzorzec losowej nieregularności odstępów RR.** Trzy wskaźniki z literatury o wykrywaniu migotania przedsionków z samych odstępów między uderzeniami (znormalizowane RMSSD, odsetek punktów zwrotnych, entropia próbkowa) dają oznaczenie „Odstępy jak przy migotaniu przedsionków”, gdy spełnione są wszystkie trzy. Bigeminia, trigeminia, zmienność oddechowa i pojedyncze przedwczesne uderzenie nie są tak oznaczane. Na sygnałach syntetycznych wzorzec jest wykrywany w ok. 90% zapisów losowych, bez fałszywych oznaczeń w zdrowym rytmie. **Progi nie były walidowane na prawdziwych zapisach z migotaniem** (brak dostępu do baz PhysioNet w środowisku budowania) i aplikacja nie rozpoznaje migotania, więc oznaczenie jest tylko sygnałem do konsultacji.
- **Kształt uderzeń i szerokość QRS.** Z czystych uderzeń powstaje szablon; zgodność kształtu z szablonem wpływa na ocenę jakości zapisu, a przedwczesne uderzenie dostaje opis „kształt odmienny (możliwe komorowe)” albo „zbliżony (możliwe przedsionkowe lub nadkomorowe)”. Szerokość QRS (z rozdzielczością 8 ms, zwykle niższa niż pomiar kliniczny) służy do porównania uderzeń.
- Detektor uderzeń ma niższy próg (25% zamiast 40% mediany maksimów), żeby nie pomijać szerokich zespołów o łagodniejszych zboczach (typowych dla pobudzeń komorowych). Na sprawdzonym prawdziwym wydruku wynik się nie zmienił.
- Nowe wskaźniki są w widoku zapisu, w raporcie dla lekarza (szerokość QRS) i dla AI (wskaźniki i kształt). Opis algorytmów w aplikacji i README jest rozszerzony, a test pilnuje zgodności liczb z kodem.
- 6 nowych testów (wzorzec jak przy migotaniu, wzorce regularne, własności wskaźników, kształt przedwczesnego uderzenia, szerokość i jakość, opis metody).

## 0.6.1-preview

- **Przyciski na dole okien edycji nie chowają się już za paskiem systemowym** (zgłoszone dla ekranu „Nowe EKG z zegarka”). Pełnoekranowe edytory (EKG, lek, wynik, analiza AI i inne) są rysowane w głównym oknie aplikacji zamiast w osobnym oknie dialogu, więc marginesy pasków systemowych i klawiatury działają tak samo jak na zwykłych ekranach. Komunikaty są widoczne także nad edytorem.
- **Czytelniejszy wynik analizy EKG:** wskaźniki w trzech kafelkach, lista „Co wykryto” z ikoną, pogrubionym tytułem i opisem, ramka „Ważne” z krótkimi punktami zamiast jednego bloku tekstu.
- **Pole „Ocena zegarka” zawiera już tylko ocenę rytmu** (OCR sklejał w nim kolumny nagłówka, np. „Rytm zatokowy Średnie tętno:73”).
- **Opis obliczeń i algorytmów:** w aplikacji (rozwijane „Jak to jest liczone”), w README, w skrócie w raporcie dla lekarza i dla AI. Test pilnuje, żeby liczby w opisie zgadzały się ze stałymi w kodzie.
- **EKG w raporcie dla AI:** nowa pigułka „EKG z zegarka” w zakładce Raport → Dla AI. Do raportu trafiają wskaźniki (tętno, zmienność RR, jakość, zakłócenia), wyniki analizy i kolejne odstępy RR w milisekundach, ale nie surowy przebieg. Polecenie dla modelu dostaje punkt o EKG (z zakazem oceny QT i ST oraz prośbą o wskazanie, kiedy potrzebne jest pełne EKG, szczególnie przy lekach wydłużających QT i zaburzeniach elektrolitów). Raport bez zapisów EKG jest identyczny jak wcześniej.
- Komunikaty EKG używają polskiego przecinka dziesiętnego, a „Rytm regularny” nie pojawia się już obok wykrytego przedwczesnego uderzenia.
- 4 nowe testy (czyszczenie oceny rytmu, raport AI z EKG i bez, kopia z flagą, zgodność opisu metody z kodem).

## 0.6.0-preview

- **EKG z zegarka.** W zakładce Badania nowa karta „EKG”: wczytujesz PDF wyeksportowany z aplikacji zegarka lub opaski (jedno odprowadzenie, zwykle 30 s). Aplikacja na telefonie odtwarza przebieg z obrazu (czerwona linia na siatce, 25 mm/s i 10 mm/mV), odczytuje z nagłówka datę, godzinę, tętno i ocenę rytmu zegarka (OCR, bez imienia, płci, wieku, wzrostu i wagi), a potem liczy tętno, odstępy między uderzeniami (RR) i ich zmienność.
- **Wskazuje kandydatów do omówienia z lekarzem:** tętno poniżej 50 lub powyżej 100/min, rytm wyraźnie nieregularny (możliwe migotanie przedsionków albo częste pobudzenia dodatkowe), przedwczesne uderzenia z dłuższą przerwą po nich, przerwy w rytmie. Zakłócenia ruchowe są rozpoznawane i pomijane w analizie (zaznaczone na przebiegu). Aplikacja nie mierzy odstępu QT ani odcinka ST i nie rozpoznaje niedokrwienia; każdy wynik ma tę adnotację i wskazówkę, kiedy nie czekać na analizę.
- Przebieg (125 próbek/s, setne części mV) i dane zapisu są w kopii zapasowej, w tym zaszyfrowanej (nowe pole `ecgs`, starsze kopie wczytują się bez zmian). Wskaźniki liczy się na żądanie, więc ulepszenia algorytmu działają także na starych zapisach.
- **Raport dla lekarza:** sekcja „EKG z zegarka” (pigułka „EKG z zegarka” w zakładce Raport) z wskaźnikami, wynikiem oceny zegarka i przebiegiem w układzie jak na wydruku, z oznaczonymi uderzeniami.
- Algorytm napisany od zera w module `core` (bez bibliotek zewnętrznych, bez sieci), sprawdzony na jednym prawdziwym wydruku z zegarka (tętno z odtworzonego przebiegu zgodne z podanym przez zegarek, zakłócenia ruchowe pomijane) oraz na zmyślonych sygnałach w 15 nowych testach (rytm regularny, nieregularny, przedwczesne uderzenie, bradykardia, tachykardia, pauza, artefakt, krótki zapis, odtworzenie z obrazu pionowego i poziomego, kopia zapasowa, raport, ucieczka znaków HTML). Prawdziwy wydruk nie jest częścią repozytorium.

## 0.5.2-preview

Przegląd kodu aplikacji i poprawki znalezionych problemów.

- **Bezpieczniejszy zapis danych:** zapis przez `AtomicFile` (synchronizacja z dyskiem, poprzedni dobry plik zostaje, gdy zapis się przerwie), zapis także przy opuszczeniu ekranu (nie tylko przy zatrzymaniu), ostrzeżenie w aplikacji, gdy zapis na dysk się nie udał (np. brak miejsca). Uszkodzony plik danych jest odkładany jako jedna kopia (`state.uszkodzony.json`) zamiast nowego pliku przy każdym starcie.
- **Przypomnienia:** okno alarmów jest kroczące, więc po każdym przypomnieniu uzupełnia się o kolejne dni (wcześniej wygasały po 14 dniach bez otwarcia aplikacji). Alarmy ustawiają się od nowa po zmianie strefy czasu, zmianie zegara i zmianie zgody na dokładne alarmy. Błąd ustawiania alarmów nie przerywa już uruchomienia aplikacji (wcześniej mógł powodować awarię przy każdym starcie). Od Androida 13 dokładne alarmy nie wymagają prośby (`USE_EXACT_ALARM`). Po wczytaniu kopii z włączonymi przypomnieniami ekran ustawień pokazuje przyciski „Zezwól na powiadomienia” i „Zezwól na dokładne alarmy”, jeśli brakuje zgód.
- **Prywatność:** powiadomienia o lekach pokazują na zablokowanym ekranie wersję neutralną, gdy ukryta jest wrażliwa treść; skopiowany raport i kopia JSON są oznaczone w schowku jako wrażliwe (Android 13+); pliki tymczasowe z udostępniania i aparatu są kasowane przy starcie aplikacji.
- **Formularze nie tracą wpisanych danych** przy obrocie ekranu, zmianie motywu lub rozmiaru czcionki (aktywność sama obsługuje zmiany konfiguracji). Edycja istniejącej analizy AI nie otwiera się już po obrocie jako nowa.
- **Klawiatura** nie zasłania pól wpisywania na Androidzie 15 (odstęp na klawiaturę).
- **OCR:** poprawione zmniejszanie dużych zdjęć i stron PDF oraz komunikat zamiast awarii, gdy zdjęcie jest za duże dla pamięci telefonu.
- Drobne: pola liczbowe w Ustawieniach nie zapisują danych samym otwarciem ekranu; pola wyboru dawek i wyników skanu są dla TalkBack przełącznikami z odczytywanym stanem; wczytanie uszkodzonego pliku pokazuje komunikat zamiast awarii; podgląd i druk raportu zwalniają WebView.
- CI sprawdza, że zbudowany APK nie ma uprawnienia INTERNET.
- **Poprawki logiki (przegląd modułu `core`):**
  - Raport dla lekarza nie liczy już dzisiejszych dawek, których pora jeszcze nie minęła, jako braków (zgodność z planem była zaniżona, np. 54% zamiast 56%; raport dla AI liczył to już poprawnie). Wzorcowe pliki raportów z poprzedniej wersji zostały w tych trzech liczbach świadomie zaktualizowane.
  - Statystyki i raporty za długie okresy (ponad 800 dni) uwzględniają teraz najnowsze dni; wcześniej po cichu ucinany był koniec okresu.
  - Dawka przyjęta po północy (pora 23:30, przyjęcie 00:15) liczy się jako +45 minut spóźnienia, a nie -1395.
  - Zmiana daty kontroli przenosi wpisany wynik razem z kontrolą (wcześniej wynik znikał z dziennika badań).
  - Wczytanie pliku z wynikami nie kasuje zakresów odniesienia, gdy plik ich nie ma.
  - Kopie z absurdalnymi wartościami ciśnienia (np. miliardy) są odrzucane przy wczytaniu zamiast zawieszać wykresy; plik z bardzo głębokim zagnieżdżeniem JSON daje komunikat zamiast awarii.
  - Odszyfrowanie: sprawdzanie liczby iteracji przed zamianą na liczbę całkowitą (ogromna wartość zawijała się do małej), górny limit 2 mln, błędny base64 daje komunikat o błędnym pliku.
  - Liczby: „5f”, „5d” czy „0x1p3” nie są już przyjmowane jako liczby.
  - Skan wyników: LDL nie trafia do parametru HDL (dopasowanie nazw „prawie takich samych” działa tylko dla nazw nieznanych), nazwy z kwalifikatorem („nie-HDL”, „zjonizowany”, „w moczu”) nie są mylone z podstawowym badaniem, zapis „< 0,5” z odstępem zachowuje znak „<”.
  - Analiza AI: tekst typu „koszt $5 i $10” nie jest traktowany jak wzór, nagłówek „## Gen C#” zachowuje znak „#”, a bardzo długie linie nie zamrażają ekranu.
  - 12 nowych testów regresji.
- README: procedura wydania aplikacji (Google Play i inne drogi).

## 0.5.1-preview

- **Poprawka komunikatów (snackbar) w ciemnym motywie:** po wczytaniu kopii pojawiała się jasna belka bez widocznego tekstu. Styl tekstu w motywie miał na stałe ustawiony jasny kolor, który wygrywał z kolorem paska. Teraz tekst bierze kolor z kontenera, więc na jasnym pasku jest ciemny. Dodane zrzuty testowe komunikatu w obu motywach.
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

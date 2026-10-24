# Plan: szyfrowanie danych aplikacji na telefonie

Status: **szkic do zatwierdzenia przez Nexus i Jack4B. Nie ma jeszcze żadnego kodu.** Implementacja zaczyna się dopiero po zatwierdzeniu planu (patrz „Etapy”). To punkt z listy „Otwarte uwagi z review” w `CLAUDE.md`.

## 0. Po co i czego to nie załatwia

Dziś dane zdrowotne leżą w pamięci aplikacji jako jawny JSON: `state.json` (pierwsza osoba), `state-p2.json`… (kolejne), `profiles.json` (lista osób), do tego pliki pomocnicze `state.przed-osobami.json` i `*.uszkodzony.json`. Blokada aplikacji (biometria/PIN) zasłania tylko interfejs, nie pliki.

Co już chroni dane bez naszej pracy: piaskownica Androida (inne aplikacje nie czytają naszego katalogu), szyfrowanie całego urządzenia (FBE) przy zablokowanym telefonie, `allowBackup="false"` i reguły `data_extraction_rules`.

Co szyfrowanie plików kluczem z Android Keystore dodaje:

- plik wyniesiony z odblokowanego, zrootowanego telefonu, z obrazu pamięci albo z narzędzi diagnostycznych serwisu jest nieczytelny bez klucza, który nie opuszcza układu zabezpieczeń (TEE/StrongBox);
- pozostałości po usuniętych plikach i kopie robocze (np. `.bak`) nie zawierają jawnych danych zdrowotnych.

Czego **nie** załatwia: złośliwej aplikacji z uprawnieniami roota, odblokowanego telefonu w cudzych rękach (aplikacja czyta dane sama), danych w kopiach zapasowych JSON zapisanych bez hasła, zrzutów ekranu, schowka ani udostępnionych raportów.

Cena: **ryzyko utraty danych, gdy klucz zniknie, a pliki zostaną** (rozdział 4). To ryzyko jest główną przyczyną ostrożnego planu poniżej. Rekomendacja: wdrożyć etapami, najpierw jako funkcję opcjonalną z wymuszoną kopią zapasową przed migracją, a dopiero po okresie testów rozważyć włączenie domyślne.

## 1. Mechanizm

**Wybór:** klucz AES-256-GCM w Android Keystore (`AndroidKeyStore`), generowany na telefonie przy pierwszym włączeniu, **bez eksportu klucza** i bez wysyłania go gdziekolwiek (aplikacja nie ma uprawnienia INTERNET). Szyfrujemy całą zawartość pliku jednym przebiegiem (pliki mają od kilku KB do kilku MB, EKG największe).

Parametry klucza (`KeyGenParameterSpec`):

- `KEY_ALGORITHM_AES`, 256 bit, tryb GCM, bez dopełnienia, cele `ENCRYPT | DECRYPT`; losowy wektor IV generuje Keystore (`setRandomizedEncryptionRequired(true)`, domyślnie);
- **bez** `setUserAuthenticationRequired` i **bez** `setUnlockedDeviceRequired`: dane muszą być czytelne w tle przy zablokowanym ekranie, bo czyta je kod bez interfejsu (alarmy przypomnień o dawkach, `BpWidget`, odbiorniki powiadomień przez `Store.readAll` w `Reminders.kt` i `BpWidget.kt`);
- `setIsStrongBoxBacked(true)` próbujemy, a przy `StrongBoxUnavailableException` wracamy do zwykłego TEE (sprzętowego lub programowego, zależnie od telefonu); informacja, który wariant działa, ląduje tylko w Ustawieniach (diagnostyka), nie w plikach;
- `minSdk 26`: AES-GCM w Keystore jest dostępny od API 23, więc nie ma osobnej ścieżki dla starych systemów.

**Powiązanie z biometrią: nie w pierwszej wersji.** Klucz wymagający uwierzytelnienia przy każdym użyciu (lub unieważniany po zmianie biometrii) nie działa w tle: alarm o dawce nie mógłby odczytać listy leków, a widżet pokazałby błąd. Do tego dodanie nowego odcisku palca albo usunięcie blokady ekranu trwale unieważnia taki klucz, co zamienia zwykłą zmianę ustawień telefonu w utratę danych. Zostaje obecna blokada interfejsu (biometria/PIN w aplikacji). Opcjonalna **faza późniejsza**, osobna decyzja: dziennik cyklu i wpisy intymne w drugim pliku szyfrowanym kluczem powiązanym z biometrią (`BiometricPrompt` z `CryptoObject`), czytanym tylko na pierwszym planie. Wymaga to osobnego planu, bo zmienia raporty i przypomnienia.

**Alternatywa rozważona i odrzucona:** hasło użytkownika (PBKDF2 jak w kopiach zapasowych, `Crypto.kt`) zamiast Keystore. Plusy: dane niezależne od telefonu. Minusy: hasło przy każdym uruchomieniu i w tle (alarmy), zapomniane hasło to utrata danych, hasło w pamięci procesu. Hasło pozostaje wyłącznie dla kopii zapasowych.

**Opcja do decyzji (nie w pierwszej wersji):** koperta kluczy. Losowy klucz danych (DEK) zaszyfrowany kluczem z Keystore (KEK) i dodatkowo hasłem z kopii zapasowej. Pozwala odzyskać dane po utracie KEK bez ostatniej kopii, ale komplikuje format i obsługę haseł. Zapisane w „Pytaniach otwartych”.

## 2. Format pliku

Każdy zaszyfrowany plik osoby to jeden blok binarny:

| Pole | Rozmiar | Opis |
|---|---|---|
| magic | 4 B | `LEKI` (0x4C 0x45 0x4B 0x49) |
| wersja formatu | 1 B | `1` |
| flagi | 1 B | zarezerwowane (0), w przyszłości np. kompresja |
| id klucza | 1 B | numer wersji klucza w Keystore (alias `leki_data_v1` ↔ 1), pozwala na rotację |
| długość IV | 1 B | 12 |
| IV | 12 B | losowy, nowy przy każdym zapisie |
| szyfrogram + znacznik GCM | n + 16 B | zawartość = dotychczasowy JSON kopii wersji 3 (`Backup.serialize`), w UTF-8 |

**Dane dodatkowe uwierzytelniane (AAD):** nagłówek (pierwsze 8 bajtów) + nazwa docelowa pliku (np. `state-p2.enc`). Dzięki temu podmiana pliku jednej osoby innym plikiem albo obniżenie wersji nagłówka kończy się błędem uwierzytelnienia.

**Rozpoznawanie:** plik zaczyna się od `LEKI` = zaszyfrowany; od `{` (po ewentualnych białych znakach, BOM) = jawny JSON kopii v3; cokolwiek innego = uszkodzony. JSON nigdy nie zaczyna się od `L`, więc nie ma niejednoznaczności. Rozszerzenie pliku: nowe nazwy `state.enc`, `state-p2.enc`… (nie nadpisujemy `state*.json`, patrz migracja). Wersja formatu wyższa niż znana = „plik z nowszej wersji aplikacji”, osobny komunikat, bez ruszania pliku.

`profiles.json` (lista identyfikatorów `p1…` i aktywna osoba) nie zawiera danych zdrowotnych ani imion i zostaje jawny (do decyzji, „Pytania otwarte”). Format kopii zapasowych JSON (v3) **nie zmienia się**.

Zapis fizyczny zostaje przez `android.util.AtomicFile` (nowy plik, fsync, podmiana), więc odporność na utratę zasilania jest taka jak dziś. Szyfrowanie jest warstwą przed `AtomicFile`: `Store.write` szyfruje bajty, `Store.readAll` odszyfrowuje.

## 3. Migracja

Zasada: **jawny plik zostaje, dopóki zaszyfrowany nie został zapisany i odczytany z powrotem z poprawnym wynikiem.** Kolejność kroków (osobno dla każdej osoby; kolejność osób nie ma znaczenia):

0. **Warunki wstępne** (przed jakąkolwiek zmianą na dysku):
   - klucz utworzony, a próba na bajtach testowych (zaszyfruj, odszyfruj, porównaj) zakończona powodzeniem; w przeciwnym razie migracja się nie zaczyna i nic się nie zmienia;
   - wolne miejsce co najmniej 2× największy plik;
   - użytkownik ma kopię zapasową z ostatnich 24 h (inaczej aplikacja proponuje „Zrób kopię teraz”; można pominąć świadomie, ale nie domyślnie).
1. Odczyt jawnego `state*.json` i sparsowanie `Backup.parseState` (plik uszkodzony nie jest migrowany, zostaje w trybie odzyskiwania jak dziś).
2. Zapis `state*.enc` przez `AtomicFile` (zaszyfrowane bajty ze **znormalizowanego** `Backup.serialize`).
3. Odczyt `state*.enc` z dysku od nowa, odszyfrowanie, `Backup.parseState` i **porównanie z danymi z kroku 1** (równość stanów albo skrótów SHA-256 serializacji). Niezgodność: usuń `state*.enc`, jawny plik zostaje, migracja tej osoby oznaczona jako nieudana (ponowna próba przy następnym starcie, najwyżej 3 razy, potem komunikat).
4. Dopiero teraz usuń jawny `state*.json` (i jego `.bak`), z nadpisaniem zerami przed usunięciem, jako najlepsza próba (pamięć flash i systemy plików z kopiowaniem przy zapisie nie gwarantują fizycznego wymazania, to jest opisane w ograniczeniach).
5. Po wszystkich osobach: pliki pomocnicze (patrz rozdział 5) i wpis „migracja zakończona” w pliku lokalnych ustawień (nie jest warunkiem poprawności, tylko wskaźnikiem dla interfejsu).

**Odporność na zabicie procesu w dowolnym momencie.** Reguła odczytu przy starcie, dla każdej osoby:

| Stan na dysku | Co robi aplikacja |
|---|---|
| tylko `state.json` | czyta jawny, planuje migrację |
| `state.json` + `state.enc` poprawny (odszyfrowuje się, parsuje, **nie jest starszy** od jawnego według `lastModified` lub liczby w nagłówku danych) | używa zaszyfrowanego, kończy krok 4 |
| `state.json` + `state.enc` niepoprawny lub nieczytelny | używa jawnego, usuwa wadliwy `.enc` |
| tylko `state.enc` poprawny | czyta zaszyfrowany |
| tylko `state.enc` niepoprawny | tryb odzyskiwania (rozdział 4), pliku nie ruszamy |
| `.bak` obok pliku (po przerwanym `AtomicFile`) | `AtomicFile.readFully` sam wybiera kopię; jawne `.bak` po migracji są usuwane |

Dla zabicia procesu w każdym punkcie kroków 2 do 4 wynik jest jeden z dwóch dopuszczalnych: dane z jawnego pliku (migracja do powtórzenia) albo dane z zaszyfrowanego (reszta sprzątania do dokończenia). Nigdy stan, w którym nie ma poprawnej kopii. Ważny wyjątek: jeśli między krokiem 2 a 4 użytkownik zdążył zmienić dane, zapis idzie już tylko do `.enc` (po kroku 3), więc „nie starszy” w tabeli to realny warunek, a nie formalność. Dlatego zapis w trakcie migracji jest wstrzymany (blokada zapisu w `Store`, krótka) do końca kroku 3.

Wczytanie kopii „Zastąp wszystkie dane” (`replaceAll`) po wprowadzeniu szyfrowania: nowe pliki `.enc` → lista osób → dopiero kasowanie starych (kolejność z 0.14.4 zostaje, `Profiles.commitReplace`).

## 4. Zniknięcie klucza

**Kiedy klucz może zniknąć, a pliki zostać:**

- reset do ustawień fabrycznych albo odinstalowanie aplikacji: znikają **i klucz, i pliki** (dane są w prywatnym katalogu aplikacji), więc nie ma niespójności;
- przywrócenie z kopii Androida / przeniesienie na nowy telefon: `allowBackup="false"` i `data_extraction_rules` wykluczają nasz katalog, więc pliki nie przechodzą na nowy telefon (klucze Keystore i tak nie są przenoszone). **Do sprawdzenia przed wdrożeniem:** że reguły wykluczają `files/` także dla przenoszenia telefon-telefon (D2D) na Androidzie 12+; jeśli nie, dopisać wykluczenie;
- zmiana albo usunięcie blokady ekranu: dla naszego klucza (bez wymagania uwierzytelnienia) **nie unieważnia go**; to jedna z przyczyn, dla których nie wiążemy klucza z biometrią;
- uszkodzenie magazynu kluczy przez producenta telefonu, błędy po aktualizacji systemu (rzadkie, ale udokumentowane na niektórych urządzeniach): klucz „jest”, ale operacja kończy się `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `KeyStoreException` albo `AEADBadTagException`.

**Zachowanie aplikacji (zasada: nic nie kasujemy, nic nie nadpisujemy):**

1. Błąd odszyfrowania pliku osoby = ta osoba trafia do istniejącego trybu odzyskiwania (`Store.recovered`): pusty stan w pamięci, **plik `.enc` nie jest ruszany ani przenoszony**, zmiany automatyczne go nie zapisują, automatyczna kopia zapasowa jest wstrzymana (to ta sama ochrona, którą dodano w 0.14.4).
2. Osobne rozróżnienie przyczyn w logice: „klucz niedostępny” (wyjątki Keystore), „plik uszkodzony” (zły nagłówek, ucięty), „plik z nowszej wersji”. Każda ma własny komunikat.
3. Komunikat na ekranie Leki (do dopracowania językowo, neutralny płciowo): **„Nie można odczytać zaszyfrowanych danych. Klucz zabezpieczający w telefonie jest niedostępny, ale pliki z danymi zostały zachowane. Wczytaj ostatnią kopię zapasową w Ustawieniach (kopia z hasłem nie zależy od klucza w telefonie) albo zacznij od nowa. Po rozpoczęciu od nowa nie da się już odzyskać zachowanych plików.”** Przycisk „Zaczynam od nowa” wymaga potwierdzenia; stary plik `.enc` zostaje na dysku jako `*.nieczytelny.enc` do czasu „Usuń wszystkie dane” (jeśli klucz kiedyś wróci, np. po poprawce systemu, da się go odzyskać).
4. Zalecenie w Ustawieniach → Dane: przy włączonym szyfrowaniu stałe przypomnienie o kopii zapasowej z hasłem (to jedyna kopia niezależna od klucza w telefonie). Przy włączaniu szyfrowania: ostrzeżenie „Dane będą czytelne tylko na tym telefonie. Zrób kopię z hasłem.” i wymuszenie kopii (krok 0 migracji).
5. Z kopii zapasowej niezależnej od klucza (hasło) odtwarzamy dane już zaszyfrowane **nowym** kluczem (`replaceAll` zapisuje przez normalną ścieżkę).

Opcja koperty kluczy z hasłem (rozdział 1) zmniejszyłaby ryzyko, ale nie wchodzi do pierwszego etapu.

## 5. Wpływ na kopie zapasowe i pliki pomocnicze

- **Eksport i import JSON, kopie z hasłem (`Crypto.kt`, PBKDF2 600 000 iteracji + AES-GCM):** bez zmian. Format kopii v3 zostaje, pliki ze starszych wersji (`core/src/test/resources/fixtures`) nadal się wczytują. Kopie nie zależą od klucza z Keystore, więc są właściwą drogą ratunku (rozdział 4).
- **`AutoBackup`:** korzysta z danych w pamięci (`Store.exportStates()`), a nie z plików na dysku, więc zapis szyfrowany go nie dotyka. Zmiany: (a) nadal wstrzymany w trybie odzyskiwania; (b) przy włączonym szyfrowaniu danych aplikacja **domyślnie proponuje kopię z hasłem** i ostrzega, że kopia JSON bez hasła w wybranym folderze jest jawna (szyfrowanie dysku telefonu tego folderu nie chroni przed innymi aplikacjami z dostępem do plików).
- **Udostępnianie i eksport do pliku:** bez zmian (to świadoma czynność użytkownika). Pliki tymczasowe w `cache/share` i `cache/camera` są dalej sprzątane przy starcie.
- **Widżet i alarmy** (`BpWidget`, `Reminders`, odbiorniki): czytają przez `Store.readAll(dir)` bez uruchomionego interfejsu. Odczyt zaszyfrowany musi działać w tle, przy zablokowanym ekranie (stąd klucz bez wymagania uwierzytelnienia). Przed pierwszym odblokowaniem po restarcie telefonu katalog aplikacji i tak jest niedostępny (szyfrowanie FBE), więc nic się nie zmienia.
- **`*.uszkodzony.json`:** to jawna kopia uszkodzonego pliku jawnego. Po wprowadzeniu szyfrowania nowe kopie powstają jako `*.uszkodzony.enc` (bajty uszkodzonego pliku bez zmian, jeśli plik był już zaszyfrowany; jeśli był jawny, kopia jest szyfrowana jako surowe bajty w kopercie `LEKI` z flagą „surowe”). Istniejące `*.uszkodzony.json` są szyfrowane w kroku 5 migracji (po weryfikacji) i usuwane jawne. „Usuń wszystkie dane” kasuje wszystko, jak dziś.
- **`state.przed-osobami.json`:** to jawny zapas pierwszej osoby z czasu przejścia na wiele osób. W kroku 5 migracji jest zamieniany na `state.przed-osobami.enc` (po weryfikacji) i usuwany w formie jawnej; dalej znika razem z pierwszą osobą albo przy „Usuń wszystkie dane”. Alternatywa (do decyzji): po udanej migracji po prostu go usunąć, bo ma już tylko wartość historyczną.
- **Pliki `.bak` z `AtomicFile`:** po przerwanym zapisie mogą zawierać jawne dane; krok sprzątania po migracji usuwa je dla jawnych plików.
- **Pliki ML Kit / OCR i zdjęcia aparatu:** poza zakresem (nie są w katalogu danych osób).

## 6. Testy

**`core` (JVM, bez Androida; wymóg z `CLAUDE.md`):**

- Nowy `DataFile` w `core`: budowa i odczyt koperty (`LEKI`, wersja, id klucza, IV, AAD) na interfejsie `DataCipher` (`encrypt(plain, aad)`, `decrypt(blob, aad)`); w testach zwykła implementacja AES-GCM z `javax.crypto` zamiast Keystore.
- Format: rundy zapis-odczyt; wykrywanie jawny / zaszyfrowany / uszkodzony / z nowszej wersji; zmiana pojedynczego bajtu nagłówka, IV, szyfrogramu i znacznika (każda kończy się błędem uwierzytelnienia); podmiana pliku jednej osoby plikiem innej (inna AAD); IV unikalny przy kolejnych zapisach; pliki puste i obcięte; zgodność z fixtures kopii v3.
- Migracja jako czysta maszyna stanów (`Migration` w `core`) nad interfejsem `FileOps` (read, write atomowy, delete, list, exists) z **wstrzykiwaniem przerwania**: test wykonuje migrację z przerwaniem po każdym k-tym kroku (k od 1 do ostatniego, osobno dla jednej i dla ośmiu osób, z plikami pomocniczymi), po każdym przerwaniu „uruchamia aplikację ponownie” (`Profiles.load` z regułą odczytu z rozdziału 3) i sprawdza, że dane są równe oryginałowi i że ponowne dokończenie migracji kończy w stanie docelowym. Dodatkowo: awaria zapisu (brak miejsca), niezgodna weryfikacja (symulowane uszkodzenie `.enc`), zapis w trakcie migracji (zablokowany), trzykrotna nieudana próba.
- Odzyskiwanie: błąd odszyfrowania daje tryb odzyskiwania, plik `.enc` bez zmian, automatyczna kopia wstrzymana (rozszerzenie `ProfilesTest`).
- Dane testowe wyłącznie zmyślone (bez prawdziwych danych, jak w `CLAUDE.md`).

**`app` (CI: kompilacja i zrzuty; brak emulatora, więc bez prawdziwego Keystore):**

- Cienka warstwa: `KeystoreCipher : DataCipher` (generowanie klucza, `StrongBox` z awaryjnym TEE, mapowanie wyjątków na „klucz niedostępny”), wywołania w `Store`, ekran Ustawienia → Dane (włącz/wyłącz, stan, ostrzeżenie), komunikat i przyciski w trybie odzyskiwania.
- Zrzuty ekranów (Paparazzi): komunikat „klucz niedostępny”, ekran włączania szyfrowania, ostrzeżenie o kopii.
- Robolectric nie zastępuje Keystore (nie obsługuje `AndroidKeyStore`), więc nie dodajemy tej zależności; testy Keystore są ręczne.

**Ręcznie na telefonie (lista trafia też do README, sekcja „Do sprawdzenia na telefonie”):**

- aktualizacja z wersji z jawnymi plikami (kilka osób, duże EKG) i sprawdzenie, że wszystko jest na miejscu;
- zabicie aplikacji w trakcie migracji (`adb shell am force-stop` w różnych momentach na dużych danych, tryb samolotowy bez znaczenia) i ponowne uruchomienie;
- alarm o dawce i widżet ciśnienia przy zablokowanym ekranie oraz po restarcie telefonu;
- zmiana blokady ekranu, dodanie odcisku palca, usunięcie blokady ekranu: dane muszą się dalej czytać;
- odinstalowanie i instalacja z kopii z hasłem; wczytanie kopii na drugim telefonie;
- telefon bez `StrongBox` i z nim; telefony z nakładkami producentów (Realme, Oppo, Xiaomi, Samsung);
- mało wolnego miejsca w trakcie migracji;
- przeniesienie telefon-telefon (D2D) nie przenosi plików (patrz rozdział 4);
- brak INTERNET w APK nadal sprawdza CI.

## 7. Ryzyka i wycofanie

| Ryzyko | Skutek | Ograniczenie |
|---|---|---|
| utrata klucza przy zachowanych plikach | dane nieczytelne | tryb odzyskiwania bez kasowania, kopia z hasłem wymuszona przed migracją, przypomnienia o kopii, opcja koperty z hasłem |
| błąd w migracji | utrata lub rozjazd danych | jawny plik do chwili weryfikacji, test przerwań po każdym kroku w `core`, wstrzymanie zapisu w trakcie |
| odczyt w tle zawodzi (widżet, alarmy) | brak przypomnień o dawkach | klucz bez wymagania uwierzytelnienia, test ręczny przy zablokowanym ekranie, obsługa błędu: przypomnienia z ostatniej znanej listy zamiast braku alarmów |
| błąd Keystore na konkretnym telefonie | niemożność włączenia funkcji | próba na bajtach testowych przed migracją, funkcja opcjonalna, wyłączenie bez utraty danych |
| koszt czasu zapisu (EKG kilka MB) | opóźnienie | szyfrowanie w wątku zapisu (jak dziś, z opóźnieniem 350 ms), pomiar na dużych danych |
| fałszywe poczucie bezpieczeństwa | użytkownik zakłada ochronę kopii JSON bez hasła | jasny opis w README i w Ustawieniach, domyślna propozycja kopii z hasłem |
| wymazanie fizyczne na flash nie jest gwarantowane | resztki jawnych danych | opisane jako ograniczenie; zabezpieczeniem jest szyfrowanie dysku telefonu (FBE) |

**Wycofanie:**

1. Etapy 1 i 2 (niżej) nie zmieniają zapisu: wycofanie to zwykłe cofnięcie wydania.
2. Po włączeniu szyfrowania (etap 3): Ustawienia → Dane → „Wyłącz szyfrowanie” odszyfrowuje pliki tą samą bezpieczną kolejnością w odwrotną stronę (zapis `.json`, odczyt i porównanie, dopiero potem usunięcie `.enc`). Starsze wersje aplikacji (bez czytnika `.enc`) nie odczytają zaszyfrowanych plików, dlatego etap 3 wymaga wcześniejszego etapu 2 u wszystkich (czytnik w obie strony przed pierwszym zapisem szyfrowanym).
3. Kopia zapasowa z hasłem zawsze wczytuje się w każdej wersji aplikacji (format v3 bez zmian).

## Etapy wdrożenia (po zatwierdzeniu planu)

Każdy etap to osobny, mały PR (gałąź `feat/…`), z testami i wpisem w CHANGELOG, bez samodzielnego scalania.

1. **Format i migracja w `core`:** `DataFile`, `DataCipher`, `FileOps`, `Migration`, reguła odczytu w `Profiles`, testy z przerwaniami. Bez zmian w `app` i bez zmiany zachowania aplikacji.
2. **Czytnik obu formatów w `app`:** `Store` umie czytać `.enc` i `.json`, ale **nadal zapisuje jawnie**; `KeystoreCipher` istnieje, ale nie jest używany do zapisu. Wydanie testowe sprawdza odczyt (plik `.enc` podłożony ręcznie na telefonie testowym).
3. **Włączenie (opcjonalne, domyślnie wyłączone):** ekran w Ustawieniach → Dane, krok 0 z wymuszoną kopią, migracja, tryb odzyskiwania dla klucza, komunikaty, zrzuty ekranów, lista testów ręcznych.
4. **Decyzja po okresie testów:** czy włączać domyślnie nowym użytkownikom; ewentualnie koperta kluczy z hasłem; ewentualnie dziennik cyklu pod biometrią (osobny plan).

## Pytania otwarte do Nexus i Jack4B

1. Czy pierwsze wydanie ma być **opcjonalne** (rekomendacja) czy od razu domyślnie włączone?
2. Czy wprowadzać **kopertę kluczy** (klucz danych zaszyfrowany kluczem z Keystore i hasłem z kopii zapasowej) już w etapie 3, czy zostawić na później?
3. `profiles.json` (identyfikatory, aktywna osoba) zostaje jawny: akceptowalne?
4. `state.przed-osobami.json`: szyfrować i zachować, czy po udanej migracji usunąć?
5. Czy kopia JSON bez hasła ma przy włączonym szyfrowaniu wymagać dodatkowego potwierdzenia (rekomendacja: tak)?
6. Czy dziennik cyklu pod biometrią (faza późniejsza) ma być w ogóle rozważany?

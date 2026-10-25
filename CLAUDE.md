# Zasady pracy nad projektem

- **Przy każdej zmianie aktualizuj dokumentację:** `README.md` (funkcje, struktura, ograniczenia, instrukcje) oraz wpis w `CHANGELOG.md`. Zmiana bez zaktualizowanego README jest niedokończona.
- Numer wersji jest w `gradle.properties` (`appVersionName`). `./gradlew checkChangelog` pilnuje, żeby CHANGELOG miał wpis `## <wersja>`; workflow uruchamia to przy każdym buildzie.
- Repozytorium nie może zawierać danych osobowych ani zdrowotnych (imiona, leki, wyniki, daty z prawdziwego życia). Dane testowe i przykłady mają być zmyślone i neutralne.
- Aplikacja nie ma uprawnienia INTERNET i nie łączy się z siecią. Nie dodawaj zależności ani funkcji wymagających internetu (analityka, CDN, zdalne modele).
- Logika (model, harmonogram, statystyki, raporty, kopie zapasowe, kryptografia) leży w module `core` bez zależności od Androida i musi mieć testy w `core/src/test`. Interfejs (Compose) jest w module `app`.
- Format kopii zapasowej (wersja 3) musi pozostać wczytywalny, także pliki z poprzednich wersji (`core/src/test/resources/fixtures`). Nowe pola dodawaj z wartościami domyślnymi w `Backup.normalize()`.
- Teksty w interfejsie po polsku, neutralne płciowo.
- Przed wypchnięciem uruchom lokalnie testy modułu `core` (`./gradlew :core:test`, gdy jest dostępny SDK, albo osobny projekt z samym `core`). Moduł `app` buduje CI.

# Stan projektu (aktualizuj przy większych zmianach)

- **Wersja:** `appVersionName` w `gradle.properties` (obecnie 0.14.x-preview). Gałąź domyślna to `ccr-f03160aa-sp7qz7` (do niej idą PR; nie pushujemy na nią bezpośrednio). Praca z modułem Cykl jest na `cykl-modul` (PR #1), nowe zmiany na gałęziach `feat/...` i `fix/...`. Każde wypchnięcie buduje APK w CI, więc **łącz zmiany w jeden push** i nie wypychaj „na próbę”.
- **Wydania:** wydanie testowe (prerelease `v<wersja>-b<numer>`) powstaje tylko z gałęzi domyślnej, z tagu `v*` albo po ręcznym uruchomieniu workflow z zaznaczonym „Opublikuj wydanie testowe” (Actions → Zbuduj APK → Run workflow), np. do testów na telefonie z gałęzi roboczej. CI zostawia 5 ostatnich wydań `-b<numer>` i usuwa osierocone tagi `-b<numer>`. Z sesji Claude Code nie da się usuwać wydań ani tagów (proxy zwraca 403): nie próbuj obchodzić tego innymi poświadczeniami.
- **Zgłoszenie do GitHub Support** o usunięcie osieroconych commitów z wcześniejszego wycieku danych (SHA: 203fbf5, 300c5b5, 7958c07, 1324bd5, c5836e4, 8392920, 55a7cf0, b450ad2) wysyła właściciel konta; do tego czasu commity są osiągalne po SHA. Historia gałęzi `cykl-modul` została przepisana (squash), więc nie cofaj się do tych commitów.

## Co jest zrobione (nie wymaga ponownej pracy)

- Leki, pory dnia, odhaczanie, zapas, przypomnienia (także dla wielu osób), raport dla lekarza i tekst dla AI, badania z odczytem OCR, EKG z zegarka (PDF wektorowy lub obraz), ciśnienie z wykresami, kopie zapasowe (JSON, szyfrowane hasłem, automatyczne do folderu), blokada aplikacji, widżet ciśnienia.
- **Kilka osób** (do 8): osobne dane, wspólny motyw i blokada; kopia całości lub wybranych osób; dopisywanie osób z pliku (`Backup.selectForExport`, `Backup.uniqueName`, `Store.addProfilesFrom`).
- **Moduły aplikacji per osoba:** Ciśnienie, Badania (z EKG) i Cykl można ukryć (`Settings.bpOn/labsOn/cyklOn`); ukryty moduł nie wchodzi do raportów (`effectiveRp/effectiveAi`), dane zostają w kopii. Leki i Raport są zawsze.
- **Moduł Cykl:** źródła TypeScript w `cykl/` (testy `npm test`), zbudowana strona `app/src/main/assets/cykl/cykl.html` w WebView bez sieci, `host.js`/`host.css` (most do aplikacji i motyw), logika po stronie JVM w `Cykl.kt`/`CyklReport.kt`. Okno płodne z prawdopodobieństwa (≥50% „wysokie ryzyko”, 20–50% „możliwe”) i metody kalendarzowej (od 6 cykli), pasma wieku do 50+ i powyżej, tryby prognoz (zwykły, antykoncepcja hormonalna, ciąża/karmienie), FIGO/STRAW+10, szczegóły dnia jako strona (nie arkusz przypięty do dołu).
- **Ostrzeżenia bezpieczeństwa:** ciśnienie ≥180/110 i bardzo niskie, EKG słabej jakości bez oceny rytmu, wskazówki o 112, zastrzeżenia „nie antykoncepcja, nie diagnoza”.
- **Prywatność:** brak INTERNET (sprawdzane w CI), `allowBackup=false`, wpisy intymne (stosunek, zabezpieczenie, solo) tylko w dzienniku i kopii, a w raportach tylko na wyraźne życzenie (solo nigdy).

## Czego nie ruszać bez ważnego powodu (i bez testów)

- Format kopii v3 i `Backup.normalize()` (nowe pola tylko z wartościami domyślnymi, zapisywane tylko gdy różne od domyślnych); fixtures w `core/src/test/resources/fixtures` i `ParityTest` (zgodność z poprzednią wersją JS: zmiana tekstu raportu często wymaga świadomej zmiany fixtures).
- Klucze i nazwy w module Cykl: `luna_db_v6`, `luna_db_v6_prev`, typy wpisów (`period_start`, `sex`, `him`, `masturb` itd.); `cykl.html` musi być **identyczny** z wynikiem `npm run build` (CI sprawdza `cmp`).
- Zabezpieczenia WebView (`CyklScreen.kt`, CSP w `Cykl.page`): brak sieci, brak dostępu do plików, wąski most `LekiHost`. Nie dodawaj zewnętrznych skryptów, fontów ani odnośników.
- Kryptografia (`Crypto.kt`: PBKDF2-HMAC-SHA256 600 000 iteracji, AES-GCM) i zapis atomowy w `Store`.
- Progi i teksty medyczne (ciśnienie 135/85 i 180/110, FIGO, STRAW+10, okno płodne, EKG): zmieniaj tylko ze źródłem i testem; nie dodawaj diagnoz ani zaleceń leczenia.
- Pliki w `przyklady/` generuje test `SampleDataTest` (nie edytuj ręcznie; odtwarzanie: `WRITE_SAMPLES=1 ./gradlew :core:test --tests '*SampleDataTest*'`); README ma początek ze zrzutami (z wydania `zrzuty-ekranow`) i opisem plików, więc zmieniając nazwy testów zrzutów, popraw linki.
- Dane w testach i przykładach tylko zmyślone (np. „Jan Testowy”, „Lek A”, daty 2030–2031 w Cyklu). Nigdy nie wklejaj prawdziwych plików użytkownika ani ich fragmentów do repo, testów, README ani wiadomości commitów.

## Co wymaga testów na telefonie (CI i JVM tego nie sprawdzą)

Pełna lista jest w README, sekcja „Do sprawdzenia na telefonie”. Najważniejsze: powiadomienia i alarmy (Realme/Oppo/Xiaomi, Doze), widżet i skrót ciśnienia przy ukrytym module, moduł Cykl w prawdziwym WebView (szczegóły dnia, menu, klawiatura, motyw jasny i ciemny, duża czcionka), odczyt EKG i wyników z prawdziwych plików, TalkBack, kopie i wczytanie na drugim telefonie, aktualizacja na wierzch (Obtainium, stały klucz podpisu).

## Znane, niewprowadzone (backlog; szczegóły w CHANGELOG 0.14.3)

Leki doraźne („w razie potrzeby”), kontrast obramowań kontrolek i nazwy pól dla TalkBack, błędy OCR badań (liczba w nazwie badania, kilka zakresów w wierszu), kontrola jednostek przy imporcie wyników z pliku, wykrywanie przedwczesnych uderzeń i szum w EKG, CSP bez `unsafe-inline`, ukrywanie pojedynczych wpisów solo, synchronizacja między telefonami (tylko koncepcja w README).

## Jak pracować (praktyczne)

- Testy `core`: osobny projekt Gradle z samym `core` (poza repo) albo `./gradlew :core:test`; cisza w wyniku = sukces. **Nie kopiuj takiego projektu poleceniem `cp -r`, gdy `core` w nim jest dowiązaniem do repo** (usunięcie plików w kopii kasuje prawdziwe pliki). Moduł Cykl: `cd cykl && npm test` (Node ≥ 22.18), potem skopiuj `cykl/dist/cykl.html` do `app/src/main/assets/cykl/cykl.html`.
- Kolejność przed wypchnięciem: testy `core`, `npm test` i `cmp`, README i CHANGELOG (wpis `## <wersja>` zgodny z `gradle.properties`), commit, jeden push, sprawdzenie CI i wydania.
- Moduł `app` nie kompiluje się lokalnie bez SDK: czytaj Kotlin ostrożnie (importy, nazwy ikon w `Ph`, smart-cast, lokalne `@Composable`), a o wyniku decyduje CI.
- Podagenci (recenzje, testy) mają być tylko do odczytu repozytorium; własne skrypty i projekty trzymaj poza repo (scratchpad).

## Współpraca z Nexus (reviewer)

- Nie pushuj na gałąź domyślną. Każda zmiana: nowa gałąź `feat/...` lub `fix/...` + PR do domyślnej.
- W opisie PR: co zmieniono, jak testowano (`./gradlew :core:test`), ryzyka.
- Review robi Nexus komentarzami w PR, każdy zaczyna się od `[Nexus]`, z priorytetem P0/P1/P2.
- Przed kolejną pracą nad PR przeczytaj uwagi: `gh pr view <nr> --comments` (w sesjach w chmurze REST, patrz niżej).
- Popraw wszystkie P0 i P1, na każdą uwagę odpowiedz w PR (`gh pr comment`): "Poprawione w <commit>" albo uzasadnienie, czemu nie.
- Nie merguj sam. Merge dopiero po komentarzu `[Nexus] APPROVE` i decyzji Kareša.
- Jeśli Nexus zrobi commit na gałęzi PR, zrób `git pull` przed dalszą pracą.

Uwagi praktyczne (dopisane przy wdrożeniu tej zasady): w sesjach Claude Code w chmurze `gh pr ...` używa GraphQL, które jest tam zablokowane. Odpowiednik przez REST: `gh api repos/<właściciel>/<repo>/pulls/<nr>/comments` (uwagi do linii), `.../issues/<nr>/comments` (komentarze ogólne i odpowiedzi: `gh api -X POST .../issues/<nr>/comments -f body=...`) albo narzędzia GitHub MCP. Domyślna gałąź tego repozytorium to na razie `ccr-f03160aa-sp7qz7` (0.10.2); aktualna praca jest na `cykl-modul`, więc PR z niej jest pierwszym wdrożeniem tej zasady. Nowe zmiany po scaleniu: nowe gałęzie `feat/...` i `fix/...`. Każde wypchnięcie nadal buduje APK w CI, więc łącz poprawki z uwag w jeden push.

### Otwarte uwagi z review (P2 i testy)

- `state.json` z danymi zdrowotnymi jest jawnym JSON w `filesDir` (`Store.kt`). Blokada biometryczna zasłania tylko UI. Rozważyć szyfrowanie kluczem z Android Keystore z migracją.
- `Store` (po PR #1, wiele osób, `AtomicFile`): uszkodzony plik trafia do `*.uszkodzony.json` i nie jest nadpisywany pustym stanem. Zostaje do sprawdzenia kopiowanie `.bak` i blokada zapisu do decyzji użytkownika.
- Ręczne `Thread {}` w `Reminders.kt` (receivery z `goAsync`, limit ok. 10 s) i w `AutoBackup.maybeRun` (PBKDF2 600 tys. iteracji). Przenieść AutoBackup do WorkManager.
- `versionCode` domyślnie 1 przy buildzie lokalnym (`app/build.gradle.kts`). Wyliczać go z `appVersionName`.
- Brak R8/minifikacji w release (potrzebne przed Google Play, z regułami dla ML Kit i serialization). Brak AAB (`bundleRelease`).
- Job `zrzuty` w CI ma `contents: write` i nadpisuje wydanie `zrzuty-ekranow` przy każdym pushu. Lepiej publikować artefakt z `contents: read`.
- Mieszane JDK: `core` używa toolchain 21, `app` ma `jvmTarget 17`. Ujednolicić albo opisać.
- `abiFilters` bez x86_64: OCR (ML Kit) nie zadziała na emulatorze x86_64. Do testów dodać x86_64 w debug.
- Testy: `app` ma tylko zrzuty Paparazzi (`continue-on-error`). Brak testów `Store` (zapis atomowy, uszkodzony plik), `AutoBackup` (seal/open, rotacja), `Reminders.reschedule`, intencji SEND/VIEW. CI nie uruchamia `lint`.
- Gałąź domyślna ma nazwę wygenerowaną (`ccr-…`). Rozważyć zmianę na `main`. PR #1 (`cykl-modul`, 0.14.x) czeka na scalenie.
- Szyfrowanie danych (opcjonalne, eksperymentalne, 0.15): logika w `core/.../storage` (format, klucze, znacznik, migracja, `StorageReader`), integracja w `Store`, `StorageAndroid.kt` (Keystore, `AndroidFileOps`) i `ui/EncryptionUi.kt`; plan i zasady w `docs/plan-szyfrowanie.md`. Nie zmieniaj kolejności zapisu ani reguł kasowania bez testów przerwań w `core`; w logach nie wolno zapisywać treści danych.
- Logika magazynu osób jest w `core` (`Profiles`, `ProfilesTest`). Sam zapis na dysk (`AtomicFile`) zostaje w `Store` (`app`) bez testów na JVM; zadanie `fix/store-tests` może to uzupełnić (Robolectric albo atrapa `FileIo`).

## Tryb pracy (wydajność, tokeny, testy)

- Czytaj tylko potrzebne pliki. Najpierw `rg`/`grep` (np. `rg -n "fun parseState" core/src/main`), potem otwórz konkretny fragment. Nie czytaj `build/`, `.gradle/`, `app/src/test/snapshots/`, PNG z `res/mipmap-*`, `gradle-wrapper.jar` ani dużych fixtures w całości. `README.md` i `CHANGELOG.md` przeszukuj zamiast czytać od początku.
- Małe, skupione zmiany: jeden temat na PR. Edytuj diffem i nie przepisuj całych plików (szczególnie dużych ekranów w `app/.../ui/*Screen.kt` i `core/.../Backup.kt`).
- Pętla testów, od najszybszej:
  1. `./gradlew :core:test --tests 'pl.asystent.leki.core.<Klasa>Test' --offline` dla dotkniętej klasy.
  2. Pełne `./gradlew :core:test --offline` przed PR.
  3. `./gradlew :app:assembleDebug :app:lintDebug` tylko wtedy, gdy zmieniło się coś w `app/` (wymaga Android SDK; bez SDK uruchom `core` w osobnym projekcie albo zostaw to CI).
  Daemon Gradle zostaw włączony (bez `--no-daemon` lokalnie).
- Nowa logika trafia do `core` (czysty Kotlin) z testem w `core/src/test`. Przy błędach stosuj TDD: najpierw test, który pada, potem poprawka. `app` ma tylko UI i wywołania platformy.
- Bez nowych zależności bez uzasadnienia w opisie PR (co daje, rozmiar, licencja, brak sieci). Zależności dodawaj przez `gradle/libs.versions.toml`.
- Podsumowania mają być krótkie. Nie wklejaj całych logów, tylko linie z błędem (np. `FAILED`, `e: file.kt:LINIA`).
- Commity w formacie Conventional Commits: `fix(core): …`, `feat(app): …`, `ci: …`, `docs: …`, `test: …`.

**Definicja ukończenia (DoD):**
- [ ] `./gradlew :core:test` zielone, nowy kod w `core` ma test.
- [ ] Jeśli dotknięto `app/`: build i lint przechodzą (lokalnie albo w CI na gałęzi PR).
- [ ] `appVersionName` podbity, wpis `## <wersja>` w `CHANGELOG.md` (`./gradlew checkChangelog`), `README.md` zaktualizowany.
- [ ] Brak uprawnienia INTERNET, sekretów i prawdziwych danych zdrowotnych w repo.
- [ ] Kopie v3 i starsze fixtures nadal się wczytują.
- [ ] PR opisuje zmiany, testy i ryzyka. Gałąź `feat/`/`fix/`, bez samodzielnego scalania.

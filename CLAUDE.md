# Zasady pracy nad projektem

- **Przy każdej zmianie aktualizuj dokumentację:** `README.md` (funkcje, struktura, ograniczenia, instrukcje) oraz wpis w `CHANGELOG.md`. Zmiana bez zaktualizowanego README jest niedokończona.
- Numer wersji jest w `gradle.properties` (`appVersionName`). `./gradlew checkChangelog` pilnuje, żeby CHANGELOG miał wpis `## <wersja>`; workflow uruchamia to przy każdym buildzie.
- Repozytorium nie może zawierać danych osobowych ani zdrowotnych (imiona, leki, wyniki, daty z prawdziwego życia). Dane testowe i przykłady mają być zmyślone i neutralne.
- Aplikacja nie ma uprawnienia INTERNET i nie łączy się z siecią. Nie dodawaj zależności ani funkcji wymagających internetu (analityka, CDN, zdalne modele).
- Logika (model, harmonogram, statystyki, raporty, kopie zapasowe, kryptografia) leży w module `core` bez zależności od Androida i musi mieć testy w `core/src/test`. Interfejs (Compose) jest w module `app`.
- Format kopii zapasowej (wersja 3) musi pozostać wczytywalny, także pliki z poprzednich wersji (`core/src/test/resources/fixtures`). Nowe pola dodawaj z wartościami domyślnymi w `Backup.normalize()`.
- Teksty w interfejsie po polsku, neutralne płciowo.
- Przed wypchnięciem uruchom lokalnie testy modułu `core` (`./gradlew :core:test`, gdy jest dostępny SDK, albo osobny projekt z samym `core`). Moduł `app` buduje CI.

## Współpraca z Nexus (reviewer)

- Żadnych pushy na gałąź domyślną. Każda zmiana trafia na gałąź `feat/...` albo `fix/...` i idzie przez PR.
- Opis PR mówi: co się zmieniło, jak to przetestowano (`./gradlew :core:test`) i jakie są ryzyka.
- Nexus robi review w komentarzach PR z prefiksem `[Nexus]` i priorytetem P0/P1/P2.
- Przed pracą nad PR przeczytaj review: `gh pr view <nr> --comments`.
- Popraw wszystkie P0/P1 i odpowiedz na każdy komentarz: „Poprawione w <commit>” albo uzasadnienie.
- Nigdy nie scalaj sam. Scalenie następuje dopiero po `[Nexus] APPROVE` i decyzji Karesha.
- Jeśli Nexus dodał commit na gałęzi PR, zrób `git pull` przed dalszą pracą.

### Otwarte uwagi z review (P2 i testy)

- `state.json` z danymi zdrowotnymi jest jawnym JSON w `filesDir` (`Store.kt`). Blokada biometryczna zasłania tylko UI. Rozważyć szyfrowanie kluczem z Android Keystore z migracją.
- `Store.read`: przy uszkodzonym pliku kopiowany jest tylko `state.json` (bez `.bak`), a pierwszy zapis nadpisuje dane pustym stanem. Kopiować też `.bak` i blokować zapis do decyzji użytkownika.
- Ręczne `Thread {}` w `Reminders.kt` (receivery z `goAsync`, limit ok. 10 s) i w `AutoBackup.maybeRun` (PBKDF2 600 tys. iteracji). Przenieść AutoBackup do WorkManager.
- `versionCode` domyślnie 1 przy buildzie lokalnym (`app/build.gradle.kts`). Wyliczać go z `appVersionName`.
- Brak R8/minifikacji w release (potrzebne przed Google Play, z regułami dla ML Kit i serialization). Brak AAB (`bundleRelease`).
- Job `zrzuty` w CI ma `contents: write` i nadpisuje wydanie `zrzuty-ekranow` przy każdym pushu. Lepiej publikować artefakt z `contents: read`.
- Mieszane JDK: `core` używa toolchain 21, `app` ma `jvmTarget 17`. Ujednolicić albo opisać.
- `abiFilters` bez x86_64: OCR (ML Kit) nie zadziała na emulatorze x86_64. Do testów dodać x86_64 w debug.
- Testy: `app` ma tylko zrzuty Paparazzi (`continue-on-error`). Brak testów `Store` (zapis atomowy, uszkodzony plik), `AutoBackup` (seal/open, rotacja), `Reminders.reschedule`, intencji SEND/VIEW. CI nie uruchamia `lint`.
- Gałąź domyślna ma nazwę wygenerowaną (`ccr-…`), a `cykl-modul` ma nowsze, niescalone commity. Ustalić `main` i scalić przez PR.

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

# Zasady pracy nad projektem

- **Przy każdej zmianie aktualizuj dokumentację:** `README.md` (funkcje, struktura, ograniczenia, instrukcje) oraz wpis w `CHANGELOG.md`. Zmiana bez zaktualizowanego README jest niedokończona.
- Numer wersji jest w `gradle.properties` (`appVersionName`). `./gradlew checkChangelog` pilnuje, żeby CHANGELOG miał wpis `## <wersja>`; workflow uruchamia to przy każdym buildzie.
- Repozytorium nie może zawierać danych osobowych ani zdrowotnych (imiona, leki, wyniki, daty z prawdziwego życia). Dane testowe i przykłady mają być zmyślone i neutralne.
- Aplikacja nie ma uprawnienia INTERNET i nie łączy się z siecią. Nie dodawaj zależności ani funkcji wymagających internetu (analityka, CDN, zdalne modele).
- Logika (model, harmonogram, statystyki, raporty, kopie zapasowe, kryptografia) leży w module `core` bez zależności od Androida i musi mieć testy w `core/src/test`. Interfejs (Compose) jest w module `app`.
- Format kopii zapasowej (wersja 3) musi pozostać wczytywalny, także pliki z poprzednich wersji (`core/src/test/resources/fixtures`). Nowe pola dodawaj z wartościami domyślnymi w `Backup.normalize()`.
- Teksty w interfejsie po polsku, neutralne płciowo.
- Przed wypchnięciem uruchom lokalnie testy modułu `core` (`./gradlew :core:test`, gdy jest dostępny SDK, albo osobny projekt z samym `core`). Moduł `app` buduje CI.

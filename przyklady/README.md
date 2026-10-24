# Dane przykładowe

Zmyślone dane do wypróbowania aplikacji „Asystent leków”: nie pochodzą z żadnych prawdziwych danych (osoby „Jan Przykładowy” i „Ola Przykładowa”, leki „Lek A/B/C”, okres 02–10.2026). Opis plików i sposób wczytania jest w głównym `README.md` (sekcja „Dane przykładowe do wczytania”).

Pliki są wygenerowane testem `SampleDataTest` (`core/src/test/kotlin/pl/asystent/leki/core/SampleData.kt`); nie edytuj ich ręcznie. Po zmianie generatora odtwórz je poleceniem `WRITE_SAMPLES=1 ./gradlew :core:test --tests '*SampleDataTest*'`.

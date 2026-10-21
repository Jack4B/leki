# Zmiany

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

# Asystent leków

Wersja **0.3.0 (preview)**. Natywna aplikacja na Androida (Kotlin, Jetpack Compose).

Dziennik leków, ciśnienia i wyników badań w jednej aplikacji. Dane zostają **tylko na telefonie**. Aplikacja nie ma kont, serwera ani analityki, a w manifeście nie ma nawet uprawnienia INTERNET, więc technicznie nie może niczego wysłać do sieci.

Aplikacja startuje **pusta**: nie ma w niej żadnych leków, danych ani imienia. Wszystko dodajesz sam w Ustawieniach albo wczytujesz z kopii zapasowej.

## Funkcje

- **Leki:** plan dnia z porami (śniadanie, obiad…), odhaczanie dawek z godziną, pomijanie dawek z powodem, zmiany dawkowania w czasie z zachowaniem historii, zamiana leku na inny, stoper po dawce, licznik wody, notatka dnia.
- **Ciśnienie:** pomiary z tętnem i porą, wykres, średnie, oznaczenia progów ustawianych przez użytkownika.
- **Badania:** dziennik wyników z zakresami odniesienia, wykresy, widok według parametrów i dat, kontrole z polem na wynik, import wyników z pliku JSON.
- **Raport dla lekarza:** wybór okresu i sekcji, podgląd, druk lub zapis do PDF z menu Androida.
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

## Pobranie APK z GitHuba

Najnowszy APK jest w zakładce **Releases** repozytorium, w wydaniu **Asystent leków 0.3.0-preview** (sekcja **Assets**: plik `asystent-lekow-0.3.0-preview.apk` i suma kontrolna `.sha256`). Wydanie jest oznaczone jako testowe (prerelease) i odświeża się automatycznie po każdym wypchnięciu zmian na gałąź. Ten sam plik jest też w artefakcie przebiegu **Actions → Zbuduj APK**.

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
               raporty (lekarz, AI), kopie zapasowe i szyfrowanie, geometria wykresów, przypomnienia
core/src/test  testy jednostkowe i dane wzorcowe z poprzedniej wersji (fixtures)
app/           aplikacja na Androida (Jetpack Compose): ekrany, magazyn danych, alarmy, druk, udostępnianie
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

- Dodawanie wyników badań ze zdjęcia (aparat lub galeria) i z pliku PDF przez rozpoznawanie tekstu (OCR) na urządzeniu, z obowiązkowym ekranem sprawdzenia wartości przed zapisem. Szkic rozwiązania: ML Kit Text Recognition (model w aplikacji, bez internetu), `PdfRenderer` do zamiany stron PDF na obrazy, parser wyników z dopasowaniem do parametrów w aplikacji.

## Znane ograniczenia wersji preview

- APK jest podpisany kluczem debug i nie jest przeznaczony do Google Play.
- Wersja bez minifikacji kodu (większy plik, mniejsze ryzyko błędów); optymalizacja później.
- Interfejs tylko po polsku.
- Aplikacja była sprawdzana w testach logiki i w kompilacji na CI, ale nie na różnych modelach telefonów. Zgłaszaj błędy.
- Synchronizacji z własnym serwerem (obecnej w dawnej wersji przeglądarkowej) nie ma. Przenoszenie danych odbywa się przez kopie zapasowe.

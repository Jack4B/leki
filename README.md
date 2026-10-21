# Asystent leków

Wersja **0.2.0 (preview)**.

Prosty dziennik leków, ciśnienia i wyników badań w jednej aplikacji na Androida. Dane zostają **tylko na telefonie**. Aplikacja nie ma kont, serwera ani analityki i nie pobiera niczego z internetu (fonty i ikony są wbudowane).

Aplikacja startuje **pusta**: nie ma w niej żadnych leków, danych ani imienia. Wszystko dodajesz sam w Ustawieniach albo wczytujesz z kopii zapasowej.

## Funkcje

- **Leki:** plan dnia z porami (śniadanie, obiad…), odhaczanie dawek z godziną, pomijanie dawek z powodem, zmiany dawkowania w czasie z zachowaniem historii, stoper po dawce, licznik wody, notatka dnia.
- **Ciśnienie:** pomiary z tętnem i porą, wykres, średnie, oznaczenia progów ustawianych przez użytkownika.
- **Badania:** dziennik wyników z zakresami odniesienia, wykresy, widok według parametrów i dat, import wyników z pliku JSON.
- **Raport dla lekarza:** wybór okresu i sekcji, druk lub zapis do PDF z menu Androida.
- **Raport dla AI:** gotowy tekst (Markdown) z poleceniem i danymi z aplikacji, do wklejenia w dowolny model AI. Szczegóły niżej.
- **Przypomnienia o dawkach:** powiadomienia o porach z planu, także przy zamkniętej aplikacji i po restarcie telefonu.
- **Kopie zapasowe:** plik JSON albo plik zaszyfrowany hasłem (AES-256-GCM, PBKDF2), zapis przez systemowe „Udostępnij”.

Aplikacja tylko zapisuje i porządkuje dane wpisane przez użytkownika. Nie ocenia wyników i nie zastępuje lekarza. Dawkowanie zmieniaj zgodnie z jego zaleceniami.

## Raport dla AI

W zakładce **Raport** jest sekcja **Raport dla AI**. Generuje ona jeden tekst w Markdown, który wklejasz do wybranego modelu (ChatGPT, Claude, Gemini i inne) albo zapisujesz jako plik `.md`.

Tekst składa się z dwóch części:

1. **Polecenie dla modelu:** analiza interakcji lek–lek, interakcji z jedzeniem i napojami, doboru pór dnia i odstępów między lekami, duplikatów substancji i nietypowych dawek, powiązań z wynikami badań i ciśnieniem, systematyczności przyjmowania oraz lista pytań do lekarza. Model ma podawać pewność wniosków, oddzielać sprawy pilne i nie zalecać samodzielnych zmian dawek.
2. **Dane:** leki z dawkowaniem i wskazówkami, harmonogram dnia (co jest brane razem), zmiany dawkowania, zgodność z planem (pominięte, spóźnione i nieodhaczone dawki, średnia godzina przyjęcia względem pory), ciśnienie, wyniki badań z zakresami, kontrole i notatki.

Okres jest ten sam co w raporcie dla lekarza (7, 14, 30 dni, wszystko albo własny zakres). Możesz wybrać sekcje i wpisać kontekst medyczny (wiek, płeć, choroby, alergie, leki bez recepty, godziny posiłków), co mocno poprawia analizę. Imię i nazwisko jest domyślnie pomijane. Tekst powstaje na urządzeniu i opuszcza je dopiero, gdy go skopiujesz lub zapiszesz. Odpowiedź AI nie jest poradą medyczną.

## Pobranie APK z GitHuba

Najnowszy APK jest w zakładce **Releases** repozytorium, w wydaniu **Asystent leków 0.2.0-preview** (sekcja **Assets**: plik `asystent-lekow-0.2.0-preview.apk` i suma kontrolna `.sha256`). Wydanie jest oznaczone jako testowe (prerelease) i odświeża się automatycznie po każdym wypchnięciu zmian na gałąź.

Ten sam plik jest też w artefakcie przebiegu **Actions → Zbuduj APK** (na dole strony przebiegu, **asystent-lekow-apk**).

Instalacja: skopiuj APK na telefon i otwórz go (Android poprosi o zgodę na instalację z nieznanych źródeł dla przeglądarki lub menedżera plików).

## Przywrócenie własnych danych

1. W aplikacji: **Ustawienia → Dane → Wczytaj kopię z pliku** i wybierz plik JSON albo zaszyfrowany plik kopii (zapyta o hasło).
2. Włącz przypomnienia w **Ustawienia → Przypomnienia o lekach** (Android poprosi o zgodę na powiadomienia).

Format kopii (wersja 3) jest taki sam jak w poprzednich wersjach aplikacji.

## Budowanie lokalnie

Wymagane: Node 20+, Java 21, Android SDK (`ANDROID_HOME`).

```
./build-local.sh
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Aktualizacja aplikacji

Zainstaluj nowy APK **na wierzchu** (bez odinstalowywania), a dane zostaną. Android wymaga, żeby każdy APK był podpisany tym samym kluczem. Domyślnie GitHub Actions generuje nowy klucz debug w każdym przebiegu, więc przy aktualizacji z innego przebiegu Android może odmówić. Są dwa wyjścia:

- zrób kopię danych, odinstaluj aplikację, zainstaluj nową i wczytaj kopię, albo
- ustaw stały klucz: wygeneruj go raz i dodaj w repozytorium jako sekret `DEBUG_KEYSTORE_BASE64` (Settings → Secrets and variables → Actions). Workflow użyje go w każdym kolejnym przebiegu.

```
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # wynik wklej do sekretu; sam plik trzymaj poza repozytorium
```

## Struktura projektu

```
www/index.html        szkielet strony i polityka CSP
www/css/app.css       style
www/js/app.js         cała logika aplikacji (bez zależności), w tym generator raportu dla AI
www/vendor/           lokalne fonty Geist i ikony Phosphor (licencje w katalogach)
native/java/          PrintPlugin (druk/PDF) i MainActivity, kopiowane do projektu Androida
native/res/           ikony Androida (launcher, ekran startowy), kopiowane do projektu Androida
assets/               źródłowe rysunki ikony (SVG)
scripts/patch-android.js   ikony, uprawnienia, wyłączenie kopii zapasowej Androida, numer wersji
scripts/make-icons.js      generowanie PNG ikon z assets/*.svg (tylko po zmianie rysunku)
scripts/check.js           kontrola składni i zgodności numeru wersji (npm run check)
scripts/vendor-assets.js   odświeżanie www/vendor (potrzebne tylko przy zmianie ikon)
.github/workflows/apk.yml  budowanie APK na GitHubie
```

Projekt Androida (`android/`) jest generowany przez Capacitor i nie leży w repozytorium. Aplikację można też otworzyć w przeglądarce: wystarczy serwować katalog `www/` (np. `npx serve www`). Przypomnienia i druk przez menu Androida działają wtedy tylko w APK.

## Prywatność

- Dane trafiają wyłącznie do pamięci lokalnej aplikacji. Kopia zapasowa Androida do chmury Google jest wyłączona.
- Polityka CSP blokuje ładowanie czegokolwiek spoza aplikacji.
- Kopię do chmury (Dysk Google, Filen) rób w formie zaszyfrowanej. Hasła nie da się odzyskać.
- Repozytorium nie zawiera żadnych danych osobowych ani zdrowotnych.

## Zasady pracy nad projektem

Zasady dla osób i narzędzi rozwijających repozytorium są w `CLAUDE.md`. Najważniejsza: każda zmiana aktualizuje README i CHANGELOG, a numer wersji jest w `package.json` i w `APP_VERSION` w `www/js/app.js`.

## Plany

- Dodawanie wyników badań ze zdjęcia (aparat lub galeria) i z pliku PDF przez rozpoznawanie tekstu (OCR) na urządzeniu, bez edycji PDF.

## Znane ograniczenia wersji preview

- APK jest podpisany kluczem debug i nie jest przeznaczony do Google Play.
- Interfejs tylko po polsku.
- Synchronizacja z własnym serwerem (obecna w wersji przeglądarkowej) została usunięta z wersji na Androida. Przenoszenie danych odbywa się przez kopie zapasowe.

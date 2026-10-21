# Asystent leków

Wersja **0.1.0 (preview)**.

Prosty dziennik leków, ciśnienia i wyników badań w jednej aplikacji na Androida. Dane zostają **tylko na telefonie**. Aplikacja nie ma kont, serwera ani analityki i nie pobiera niczego z internetu (fonty i ikony są wbudowane).

Aplikacja startuje **pusta**: nie ma w niej żadnych leków, danych ani imienia. Wszystko dodajesz sam w Ustawieniach albo wczytujesz z kopii zapasowej.

## Funkcje

- **Leki:** plan dnia z porami (śniadanie, obiad…), odhaczanie dawek z godziną, pomijanie dawek z powodem, zmiany dawkowania w czasie z zachowaniem historii, stoper po dawce, licznik wody, notatka dnia.
- **Ciśnienie:** pomiary z tętnem i porą, wykres, średnie, oznaczenia progów ustawianych przez użytkownika.
- **Badania:** dziennik wyników z zakresami odniesienia, wykresy, widok według parametrów i dat, import wyników z pliku JSON.
- **Raport dla lekarza:** wybór okresu i sekcji, druk lub zapis do PDF z menu Androida.
- **Przypomnienia o dawkach:** powiadomienia o porach z planu, także przy zamkniętej aplikacji i po restarcie telefonu.
- **Kopie zapasowe:** plik JSON albo plik zaszyfrowany hasłem (AES-256-GCM, PBKDF2), zapis przez systemowe „Udostępnij”.

Aplikacja tylko zapisuje i porządkuje dane wpisane przez użytkownika. Nie ocenia wyników i nie zastępuje lekarza. Dawkowanie zmieniaj zgodnie z jego zaleceniami.

## Pobranie APK z GitHuba

1. Wejdź w **Actions → Zbuduj APK** i otwórz ostatni udany przebieg (albo uruchom go przyciskiem **Run workflow**).
2. Na dole strony przebiegu pobierz artefakt **asystent-lekow-apk** (plik `asystent-lekow-0.1.0-preview.apk` i suma kontrolna `.sha256`).
3. Skopiuj APK na telefon i zainstaluj (Android poprosi o zgodę na instalację z nieznanych źródeł dla przeglądarki lub menedżera plików).

Po wypchnięciu tagu `v*` (np. `v0.1.0-preview`) workflow sam tworzy też wydanie (prerelease) z dołączonym APK:

```
git tag v0.1.0-preview && git push origin v0.1.0-preview
```

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
www/js/app.js         cała logika aplikacji (bez zależności)
www/vendor/           lokalne fonty Geist i ikony Phosphor (licencje w katalogach)
native/java/          PrintPlugin (druk/PDF) i MainActivity, kopiowane do projektu Androida
scripts/patch-android.js   uprawnienia, wyłączenie kopii zapasowej Androida, numer wersji
scripts/vendor-assets.js   odświeżanie www/vendor (potrzebne tylko przy zmianie ikon)
.github/workflows/apk.yml  budowanie APK na GitHubie
```

Projekt Androida (`android/`) jest generowany przez Capacitor i nie leży w repozytorium. Aplikację można też otworzyć w przeglądarce: wystarczy serwować katalog `www/` (np. `npx serve www`). Przypomnienia i druk przez menu Androida działają wtedy tylko w APK.

## Prywatność

- Dane trafiają wyłącznie do pamięci lokalnej aplikacji. Kopia zapasowa Androida do chmury Google jest wyłączona.
- Polityka CSP blokuje ładowanie czegokolwiek spoza aplikacji.
- Kopię do chmury (Dysk Google, Filen) rób w formie zaszyfrowanej. Hasła nie da się odzyskać.
- Repozytorium nie zawiera żadnych danych osobowych ani zdrowotnych.

## Znane ograniczenia wersji preview

- Brak własnej ikony aplikacji (domyślna ikona Capacitor).
- APK jest podpisany kluczem debug i nie jest przeznaczony do Google Play.
- Interfejs tylko po polsku.
- Synchronizacja z własnym serwerem (obecna w wersji przeglądarkowej) została usunięta z wersji na Androida. Przenoszenie danych odbywa się przez kopie zapasowe.

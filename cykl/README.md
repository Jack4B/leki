# Cykl v2

Dziennik cyklu w jednym pliku HTML. Bez zależności, bez sieci, dane tylko w przeglądarce.

    npm test        # build + 66 testów (node:test, TypeScript uruchamiany natywnie)
    npm run build   # dist/cykl.html

Wymaga Node >= 22.18 (natywne wykonywanie TypeScript).

## Układ
- src/dates.ts, model.ts, history.ts : daty, import/merge, edycja dnia, segmentacja cykli
- src/predict.ts : rozkład owulacji (splot rozkładów), szanse wg Wilcox 1995, LH, spóźniony okres
- src/health.ts  : FIGO System 1, STRAW+10, trend Theil–Sen
- src/report.ts, analysis.ts : raporty, oś cykli, złożenie analizy
- src/views.ts, charts.ts : czyste renderery (testowalne bez DOM), bezpieczne szablony html``
- src/app.ts : obsługa zdarzeń, motyw, cofnij, swipe
- build.mjs : mały bundler (skleja moduły, zdejmuje typy, wkleja do szablonu)

Klucz localStorage `luna_db_v6` jest taki sam jak w poprzedniej wersji, więc dane przechodzą.

## Wersja w aplikacji Asystent leków
Aplikacja otwiera ten plik w WebView bez dostępu do sieci i dokłada dwa pliki z `app/src/main/assets/cykl/`: `host.js` (zamiast `localStorage` używa pamięci aplikacji, zapisuje pliki i kopiuje do schowka przez system; ustawia `data-host="leki"`) oraz `host.css` (barwy i krój aplikacji). W trybie `data-host` moduł nie pokazuje własnego wyboru motywu ani ostrzeżeń o przeglądarce. Po zmianie źródeł: `npm test`, potem skopiuj `dist/cykl.html` do `app/src/main/assets/cykl/cykl.html` (CI pilnuje, żeby oba pliki były takie same).

Dane w testach są wymyślone. Nie dodawaj tu prawdziwych dzienników ani ich fragmentów (daty, długości cykli): to dane zdrowotne, a repozytorium ich nie przyjmuje.

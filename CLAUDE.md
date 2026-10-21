# Zasady pracy nad projektem

- **Przy każdej zmianie aktualizuj dokumentację:** `README.md` (funkcje, struktura, ograniczenia, instrukcje) oraz wpis w `CHANGELOG.md`. Zmiana bez zaktualizowanego README jest niedokończona.
- Numer wersji jest w `package.json` i w stałej `APP_VERSION` w `www/js/app.js`. `npm run check` pilnuje, żeby się zgadzały i żeby CHANGELOG miał wpis dla tej wersji.
- Repozytorium nie może zawierać danych osobowych ani zdrowotnych (imiona, leki, wyniki, daty z prawdziwego życia). Przykłady w kodzie i dokumentacji mają być neutralne.
- Aplikacja nie łączy się z internetem. Nie dodawaj zasobów ładowanych z zewnątrz (CDN, fonty, skrypty); polityka CSP w `www/index.html` ich nie przepuści.
- Format kopii zapasowej (wersja 3) musi pozostać wczytywalny. Nowe pola dodawaj z wartościami domyślnymi w `normalize()`.
- Teksty w interfejsie po polsku, neutralne płciowo.

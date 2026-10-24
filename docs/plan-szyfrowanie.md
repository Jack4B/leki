# Plan: szyfrowanie danych aplikacji na telefonie

Status: **wersja 3 planu, po drugim review Nexus (uwagi P1 i P2 do wersji 2 uwzględnione). Czeka na `[Nexus] APPROVE` planu i decyzję Jack4B. Nie ma żadnego kodu.** Implementacja zaczyna się dopiero po `[Nexus] APPROVE` planu i decyzji Jack4B (patrz „Etapy”). To punkt z listy „Otwarte uwagi z review” w `CLAUDE.md`.

Zmiany względem wersji 1: koperta kluczy (DEK zaszyfrowany kluczem z Keystore) od razu; automat stanów z atomowym znacznikiem trybu zamiast `lastModified`; licznik generacji w nagłówku; AAD z logicznym identyfikatorem zamiast nazwy pliku; błąd klucza nigdy nie kasuje pliku; odczyty w tle bez trybu odzyskiwania i bez zapisów; osobne komunikaty dla przeniesienia plików narzędziem producenta; wymuszona kopia poza `files/` sprawdzona przed migracją; procedura rotacji; `state.przed-osobami.json` usuwany po udanej migracji.

Zmiany w wersji 3 (drugie review): nieważny albo brakujący znacznik przy istniejących `.enc` lub `keys.bin` = tryb `enc` tylko do odczytu; przerwana migracja jest po restarcie cofana do trybu wiążącego, z licznikiem prób w znaczniku; `keys.bin` ma drugą kopię i osobną klasę błędu; alias KEK zapisany w `keys.bin`; rotacja DEK poza etapem 3; generacja nie jest już przedstawiana jako ochrona przed starszą kopią; doprecyzowane rodzaje AAD i bit flagi „surowe”; alarmy po restarcie bez odczytu danych.

## 0. Po co i czego to nie załatwia

Dziś dane zdrowotne leżą w pamięci aplikacji jako jawny JSON: `state.json` (pierwsza osoba), `state-p2.json`… (kolejne), `profiles.json` (lista osób), do tego pliki pomocnicze `state.przed-osobami.json` i `*.uszkodzony.json`. Blokada aplikacji (biometria/PIN) zasłania tylko interfejs, nie pliki.

Co już chroni dane bez naszej pracy: piaskownica Androida (inne aplikacje nie czytają naszego katalogu), szyfrowanie całego urządzenia (FBE) przy zablokowanym telefonie, `allowBackup="false"` i reguły `data_extraction_rules` (wykluczają `file`, także dla przenoszenia telefon-telefon).

Co szyfrowanie plików z kluczem w Android Keystore dodaje:

- plik wyniesiony z odblokowanego, zrootowanego telefonu, z obrazu pamięci albo z narzędzi diagnostycznych serwisu jest nieczytelny bez klucza, który nie opuszcza układu zabezpieczeń;
- pozostałości po usuniętych plikach i kopie robocze (np. `.bak`) nie zawierają jawnych danych zdrowotnych.

Czego **nie** załatwia: złośliwej aplikacji z uprawnieniami roota, odblokowanego telefonu w cudzych rękach (aplikacja czyta dane sama), danych w kopiach JSON zapisanych bez hasła, zrzutów ekranu, schowka ani udostępnionych raportów. **Telefon bez blokady ekranu:** klucze w Keystore nadal nie opuszczają układu, ale każdy, kto uruchomi telefon, uruchomi też aplikację, więc ochrona jest słabsza. Ustawienia pokażą to ostrzeżenie przy włączaniu (jeśli telefon nie ma blokady ekranu, `KeyguardManager.isDeviceSecure() == false`) i zachęcą do ustawienia blokady.

Cena: **ryzyko utraty danych, gdy klucz zniknie, a pliki zostaną** (rozdział 4). To ryzyko jest główną przyczyną ostrożnego planu. Decyzja o wdrożeniu (z odpowiedzi Nexus): funkcja **opcjonalna**, domyślnie włączana dopiero dla nowych instalacji po okresie testów.

## 1. Mechanizm: koperta kluczy (DEK + KEK)

Dwa klucze:

- **KEK** (klucz osłaniający): AES-256-GCM w Android Keystore (`AndroidKeyStore`), alias `leki_kek_v1`, generowany na telefonie, **bez eksportu** i bez wysyłania gdziekolwiek (brak uprawnienia INTERNET). Służy wyłącznie do zaszyfrowania i odszyfrowania 32-bajtowego klucza danych.
- **DEK** (klucz danych): losowy klucz AES-256 (`SecureRandom`), po jednym na instalację (identyfikator `dekId`, początkowo 1). Zapisany w pliku `keys.bin` jako `AES-GCM(KEK, DEK)` z AAD `leki|dek|<dekId>|<aliasKEK>`; **`keys.bin` zawiera też nazwę bieżącego aliasu KEK** (jawnie, jako pole nagłówka objęte AAD), więc aplikacja nie zgaduje aliasu z numeru wersji. Plik jest zapisywany przez `AtomicFile`, **po zapisie od razu weryfikowany** (odczyt z dysku i `unwrap`), a obok powstaje **druga kopia `keys.bin.2`** (zapisywana i weryfikowana tak samo; patrz „Ochrona `keys.bin`” niżej). Odszyfrowany **raz na proces** (przy pierwszym użyciu) i trzymany w pamięci procesu; dane są szyfrowane programowo (`javax.crypto`) tym kluczem.

Dlaczego koperta, a nie szyfrowanie plików bezpośrednio kluczem z Keystore: StrongBox (osobny układ) jest bardzo wolny przy megabajtach danych (zapisy EKG), a operacja na 32 bajtach raz na proces jest szybka. Koperta daje też tanią **rotację KEK** (przepisanie jednego małego pliku) i miejsce na późniejsze drugie owinięcie DEK hasłem z kopii zapasowej (zarezerwowane pole, nie w tej wersji). Kosztem jest obecność DEK w pamięci procesu aplikacji (jak dziś odszyfrowane dane); nie trafia on do logów, plików ani zrzutów.

Parametry KEK (`KeyGenParameterSpec`):

- `KEY_ALGORITHM_AES`, 256 bit, GCM, bez dopełnienia, cele `ENCRYPT | DECRYPT`;
- **bez** `setUserAuthenticationRequired` i **bez** `setUnlockedDeviceRequired`: dane muszą być czytelne w tle przy zablokowanym ekranie, bo czyta je kod bez interfejsu (alarmy przypomnień o dawkach, `BpWidget`, odbiorniki, przez `Store.readAll` w `Reminders.kt` i `BpWidget.kt`);
- `setIsStrongBoxBacked(true)` próbujemy (przy kopercie koszt jest znikomy), a przy `StrongBoxUnavailableException` wracamy do zwykłego TEE; który wariant działa, pokazują tylko Ustawienia (diagnostyka);
- **IV tylko z `cipher.iv`** (generuje go dostawca; nigdy własny licznik ani własny losowy IV) zarówno przy owijaniu DEK, jak i przy szyfrowaniu plików;
- `minSdk 26`: AES-GCM w Keystore jest dostępny od API 23.

**Powiązanie z biometrią: nie.** Klucz wymagający uwierzytelnienia (lub unieważniany po zmianie biometrii) nie działa w tle i zamienia zmianę ustawień telefonu w utratę danych. Zostaje obecna blokada interfejsu. Dziennik cyklu pod biometrią: nie teraz (decyzja Nexus).

**Alternatywa odrzucona:** hasło użytkownika (PBKDF2 jak w kopiach zapasowych, `Crypto.kt`) zamiast Keystore: hasło przy każdym uruchomieniu i w tle, zapomniane hasło to utrata danych. Hasło zostaje dla kopii zapasowych; koperta z hasłem do DEK jest odłożona na później.

**Ochrona `keys.bin` (pojedynczy punkt awarii):**

- Zapis tylko przez `AtomicFile`; po zapisie weryfikacja przez odczyt z dysku i `unwrap` kluczem z Keystore. Dopiero po poprawnej weryfikacji powstaje druga kopia `keys.bin.2` (zapis i weryfikacja jak wyżej).
- **Obie kopie są przepisywane wyłącznie przy rotacji KEK** (i przy pierwszym utworzeniu); zwykłe zapisy danych ich nie dotykają, więc zwykła praca nie może ich uszkodzić.
- Odczyt: najpierw `keys.bin`, przy błędzie strukturalnym lub uwierzytelnienia `keys.bin.2`. Użycie drugiej kopii oznacza „uszkodzona kopia kluczy” w diagnostyce Ustawień i ostrzeżenie, ale **bez automatycznej naprawy** (naprawa dopiero przy następnej rotacji albo ręcznie z Ustawień na pierwszym planie).
- **Gdy obie kopie są nieczytelne** (a alias KEK istnieje i działa): osobna klasa błędu **F „keys.bin uszkodzony”** (rozdział 4) z własnym komunikatem. Pliki `keys.bin`, `keys.bin.2` i `state*.enc` zostają nietknięte, **nigdy ich nie kasujemy**, a nowy `keys.bin` nie powstaje bez jawnego „Zaczynam od nowa”.

**Rotacja (procedura):**

- *KEK:* wybierz nowy alias jako `leki_kek_<n+1>`, gdzie n to największy numer spośród aliasów `leki_kek_*` w Keystore i numeru zapisanego w `keys.bin` (dzięki temu „Zaczynam od nowa” i rotacja nigdy nie dostają tego samego aliasu); odszyfruj DEK starym KEK, zaszyfruj nowym, zapisz `keys.bin` z nowym aliasem (`AtomicFile`), zweryfikuj `unwrap`, zapisz i zweryfikuj `keys.bin.2`. **Stary alias usuwamy dopiero na końcu i tylko wtedy, gdy żadna z kopii `keys.bin` na niego nie wskazuje**; osierocony alias (np. po przerwanej rotacji) sprzątany jest tą samą regułą przy następnym starcie na pierwszym planie, a alias, na który wskazuje którakolwiek kopia `keys.bin`, nie jest nigdy usuwany. Pliki danych się nie zmieniają. **Rotacja automatyczna tylko na pierwszym planie** (nigdy z widżetu, alarmów ani odbiorników); wyzwalacze: zmiana wersji formatu `keys.bin`, wykrycie słabszego wariantu (np. TEE zamiast StrongBox po aktualizacji systemu) albo ręcznie z Ustawień.
- *DEK:* **poza zakresem tego planu i etapu 3.** Nagłówek pliku ma pole `id DEK`, więc format na to pozwala, ale rotacja DEK (ponowne zaszyfrowanie wszystkich plików) wymaga osobnego planu i osobnej decyzji. Przy tej aplikacji limit bezpiecznego użycia losowych 96-bitowych IV (ok. 2^32 zapisów na klucz) jest praktycznie nieosiągalny.

## 2. Format pliku

Każdy zaszyfrowany plik to jeden blok binarny:

| Pole | Rozmiar | Opis |
|---|---|---|
| magic | 4 B | `LEKI` (0x4C 0x45 0x4B 0x49) |
| wersja formatu | 1 B | `1` |
| flagi | 1 B | bit 0 (`0x01`) = **„surowe”**: zawartość to surowe bajty uszkodzonego pliku (nie JSON kopii v3); pozostałe bity zarezerwowane (0), wartość inna niż 0 lub 1 = plik z nowszej wersji |
| id DEK | 1 B | `dekId` |
| długość IV | 1 B | 12 |
| generacja | 4 B | licznik zapisu danego pliku (uint32, big endian), rośnie o 1 przy każdym zapisie; **tylko diagnostyka** i wybór właściwego pliku wobec jego własnej kopii `.bak` (nie chroni przed podłożeniem starszej kopii; to jest poza zakresem ochrony) |
| IV | 12 B | z `cipher.iv` |
| szyfrogram + znacznik GCM | n + 16 B | zawartość = dotychczasowy JSON kopii wersji 3 (`Backup.serialize`), UTF-8 |

**AAD:** nagłówek (pierwsze 12 bajtów, razem z generacją) + **logiczny identyfikator** `leki|v1|<rodzaj>|<osoba>`, gdzie rodzaj ∈ {`state`, `uszkodzony`}, a osoba to `p1`, `p2`… (`state` = plik danych osoby, także po zmianie nazwy na `*.nieczytelny.enc`; `uszkodzony` = `*.uszkodzony.enc`, czyli surowe bajty uszkodzonego pliku z flagą „surowe”). `state.przed-osobami.json` nie jest szyfrowany (jest usuwany po migracji), więc nie ma dla niego rodzaju Nazwa pliku **nie** wchodzi do AAD. Dzięki temu zmiana nazwy (`*.nieczytelny.enc`, `*.uszkodzony.enc`, `.bak`) nie psuje odszyfrowania, a podmiana pliku jednej osoby plikiem innej (inny identyfikator logiczny) albo obniżenie wersji nagłówka kończy się błędem uwierzytelnienia. Zmiana nazwy nie zmienia rodzaju ani osoby; plik `*.uszkodzony.enc` ma zawsze rodzaj `uszkodzony` i osobę oryginału.

**Rozpoznawanie:** plik zaczyna się od `LEKI` = zaszyfrowany; od `{` (po białych znakach, BOM) = jawny JSON kopii v3; cokolwiek innego = strukturalnie uszkodzony. JSON nigdy nie zaczyna się od `L`. Wersja formatu wyższa niż znana = „plik z nowszej wersji aplikacji”, osobny komunikat, plik bez zmian.

Pliki: `state.enc`, `state-p2.enc`… (nie nadpisujemy `state*.json`, patrz migracja), `keys.bin` i `keys.bin.2` (owinięty DEK i alias KEK, własny nagłówek `LEKK`), `storage.json` (znacznik trybu, rozdział 3). `profiles.json` (identyfikatory `p1…` i aktywna osoba) nie zawiera danych zdrowotnych ani imion i zostaje jawny. Format kopii zapasowych JSON (v3) **nie zmienia się**.

Zapis fizyczny zostaje przez `android.util.AtomicFile`. Szyfrowanie jest warstwą przed nim.

## 3. Migracja i automat stanów

Zamiast porównywania `lastModified` (niewiarygodny przy zmianie zegara) o tym, które pliki są wiążące, decyduje **atomowy znacznik trybu** `storage.json`, zapisywany przez `AtomicFile`:

```
{"v":1,"mode":"plain" | "to-enc" | "enc" | "to-plain","dekId":1,"attempts":0}
```

**Walidacja znacznika:** `v == 1`; `mode` ∈ {`plain`, `to-enc`, `enc`, `to-plain`}; `dekId` liczbą całkowitą `>= 1` **zgodną z `dekId` zapisanym w `keys.bin`** (gdy `keys.bin` istnieje); `attempts` liczbą całkowitą z zakresu 0 do 3. Zapis znacznika zawsze przez `AtomicFile`, po zapisie odczyt i ponowna walidacja. `attempts` to licznik prób migracji; zerowany po jej sukcesie.

**Znacznik brakujący albo nieważny** (nie parsuje się, nie przechodzi walidacji, niezgodny z `keys.bin`):

- nie istnieje żaden `state*.enc` ani `keys.bin`: instalacja bez szyfrowania lub sprzed tej funkcji, tryb `plain`;
- **istnieje którykolwiek `state*.enc` lub `keys.bin`: nie zakładamy `plain`.** Aplikacja przechodzi w **tryb `enc` tylko do odczytu** (zapisy danych wyłączone, odczyt z `.enc`, jeśli się odszyfrowują; jawne pliki nietknięte) z wyraźnym ostrzeżeniem: „Znacznik trybu przechowywania jest uszkodzony. Aplikacja działa tylko do odczytu. Zrób kopię zapasową z hasłem i odtwórz znacznik.” Odtworzenie znacznika jest czynnością jawną z Ustawień (pierwszy plan): aplikacja sprawdza, czy `.enc` odszyfrowują się i parsują, i dopiero wtedy zapisuje `mode:"enc"`; jeśli nie, tryb odzyskiwania (rozdział 4). Procesy w tle w tej sytuacji tylko czytają.


| `mode` | Wiążące są | Co znaczą pliki obok |
|---|---|---|
| `plain` (albo brak pliku) | `state*.json` | `.enc` bez zatwierdzenia to pozostałość po przerwanej próbie |
| `to-enc` | `state*.json` | `.enc` jest kandydatem, jeszcze niezatwierdzonym |
| `enc` | `state*.enc` | `state*.json` to pozostałość do usunięcia (po sprawdzeniu, że `.enc` odczytuje się poprawnie) |
| `to-plain` | `state*.enc` | `state*.json` to kandydat przy wyłączaniu szyfrowania |

**Zasada nadrzędna: wygrywa format wskazany w znaczniku, nigdy „nowszy plik”. Zapisy danych są zablokowane (krótka blokada w `Store`) od zmiany znacznika na `to-*` do jego ustawienia na wartość końcową, w obie strony. Blokada jest wyłącznie w pamięci procesu, który prowadzi migrację: nie jest zapisywana i nie przeżywa restartu.**

**Przerwana migracja (zabicie procesu, restart telefonu, błąd):** po następnym starcie znacznik `to-enc` albo `to-plain` oznacza, że migracja się nie skończyła. Aplikacja **w pierwszej kolejności, atomowo** zapisuje znacznik w **trybie wiążącym sprzed migracji** (`to-enc` → `plain`, `to-plain` → `enc`), zwiększa `attempts` o 1 i dopiero wtedy odblokowuje zapisy. Nigdy nie zostaje w `to-*` ze wstrzymanymi zapisami. Ponowna próba migracji jest osobnym, nowym uruchomieniem (od kroku 1), wykonywana automatycznie na pierwszym planie tylko gdy `attempts < 3`; po trzeciej nieudanej próbie funkcja zostaje wyłączona w trybie wiążącym, a Ustawienia pokazują komunikat i przycisk ręcznej próby.

**Włączenie (`plain` → `enc`):**

0. **Warunki wstępne** (przed jakąkolwiek zmianą na dysku): (a) KEK i DEK utworzone, a próba na bajtach testowych (zaszyfruj, odszyfruj, porównaj) zakończona powodzeniem; (b) wolne miejsce co najmniej 2× największy plik; (c) **kopia zapasowa z hasłem zapisana poza katalogiem `files/` aplikacji** (wybór folderu przez systemowy selektor, SAF), **sprawdzona**: aplikacja odczytuje ją z powrotem, odszyfrowuje hasłem, parsuje i porównuje ze stanem w pamięci. Bez zaliczonej kopii przycisk migracji jest nieaktywny (nie ma „pomiń”).
1. Zapis `storage.json` z `mode:"to-enc"` (atomowo). Od teraz zapisy danych są wstrzymane.
2. Dla każdej osoby: odczyt jawnego `state*.json`, `Backup.parseState`; zapis `state*.enc` (generacja 1) przez `AtomicFile`.
3. Dla każdej osoby: odczyt `state*.enc` z dysku od nowa, odszyfrowanie, parsowanie i **porównanie ze stanem z kroku 2** (równość stanów lub skrótów SHA-256 serializacji). Niezgodność: usuń **tylko ten wadliwy kandydat** `.enc` (jawny plik jest wiążący), przerwij i **od razu cofnij znacznik do `plain`** (`attempts + 1`, zapisy odblokowane); ponowna próba najwyżej 3 razy, potem komunikat.
4. **Punkt zatwierdzenia:** zapis `storage.json` z `mode:"enc"`. Dopiero teraz `.enc` są wiążące.
5. Sprzątanie (każdy krok powtarzalny): usuń jawne `state*.json` i ich `.bak` (z nadpisaniem zerami przed usunięciem, jako najlepsza próba: pamięć flash i systemy plików z kopiowaniem przy zapisie nie gwarantują fizycznego wymazania); usuń `state.przed-osobami.json` (decyzja Nexus: po udanej migracji go nie trzeba); zaszyfruj istniejące `*.uszkodzony.json` do `*.uszkodzony.enc` (z logicznym identyfikatorem oryginału) i usuń jawne.
6. Odblokuj zapisy.

**Wyłączenie (`enc` → `plain`):** lustrzanie: `to-plain` → zapis `state*.json` → odczyt i porównanie → `plain` → usunięcie `.enc`, `keys.bin`, `keys.bin.2` i zerowanie. Klucze Keystore usuwane na samym końcu.

**Reguła odczytu przy starcie** (dotyczy też procesów w tle, patrz rozdział 4), niezależnie od miejsca przerwania:

| Znacznik | Pliki | Co robi aplikacja |
|---|---|---|
| `plain` / brak | tylko `state.json` | czyta jawny |
| `plain` / brak | `state.json` + `state.enc` | jawny wiążący; `.enc` **strukturalnie uszkodzony** (zły nagłówek, ucięty) i niezatwierdzony: usuń; `.enc` strukturalnie poprawny: zostaw i potraktuj jako kandydata |
| `to-enc` (po restarcie) | `state.json` (+ `state.enc`) | migracja była przerwana: znacznik wraca atomowo do `plain` (`attempts + 1`), zapisy odblokowane, jawny wiążący; kandydat `.enc` zostaje; ponowna próba osobno (od kroku 1). **Błąd klucza lub uwierzytelnienia (AEAD) kandydata nie jest powodem do jego usunięcia**: zostaw oba pliki, pokaż komunikat „szyfrowanie niedostępne” |
| `enc` | `state.enc` (+ resztki `state.json`) | czyta zaszyfrowany; resztki jawnych usuwa dopiero po poprawnym odczycie `.enc` |
| `enc` | brak `state.enc`, jest `state.json` | stan wyjątkowy (ręczna ingerencja albo awaria dysku): czytaj jawny, pokaż ostrzeżenie, nic nie kasuj |
| `to-plain` (po restarcie) | `state.enc` (+ `state.json`) | wyłączanie było przerwane: znacznik wraca atomowo do `enc` (`attempts + 1`), zapisy odblokowane, zaszyfrowany wiążący; ponowna próba osobno |
| brak lub nieważny znacznik, są `.enc` lub `keys.bin` | dowolne | tryb `enc` tylko do odczytu z ostrzeżeniem (patrz „Znacznik brakujący albo nieważny”) |
| dowolny | `.bak` obok pliku | `AtomicFile.readFully` wybiera właściwą kopię; jawne `.bak` po migracji są usuwane |

**Zasada bezpieczeństwa danych:** błąd **klucza** (brak aliasu, `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `KeyStoreException`, `AEADBadTagException`) **nigdy nie prowadzi do skasowania ani nadpisania pliku**; kasowany bywa wyłącznie plik strukturalnie uszkodzony i niezatwierdzony, jak w tabeli.

Wynik w każdym punkcie przerwania kroków 1 do 6: dane z jawnego pliku (do powtórzenia) albo z zaszyfrowanego (reszta sprzątania do dokończenia). Nigdy stan bez poprawnej kopii. Znacznik jest jedynym miejscem rozstrzygającym. Generacja w nagłówku nie ma zapisanej wartości oczekiwanej, więc **nie** chroni przed podłożeniem starszej kopii pliku; służy tylko diagnostyce i wyborowi między plikiem a jego własną kopią `.bak`.

Wczytanie kopii „Zastąp wszystkie dane” (`replaceAll`) po wprowadzeniu szyfrowania: nowe pliki → lista osób → dopiero kasowanie starych (kolejność z 0.14.4, `Profiles.commitReplace`), w trybie `enc` zapis nowych plików przez ścieżkę szyfrującą.

## 4. Zniknięcie klucza i odczyty w tle

**Kiedy klucz może zniknąć, a pliki zostać:**

- reset do ustawień fabrycznych albo odinstalowanie aplikacji: znikają **klucz i pliki**, nie ma niespójności;
- przywrócenie z kopii Androida / przeniesienie telefon-telefon (D2D): `allowBackup="false"` i `data_extraction_rules` wykluczają `file` (sprawdzone); klucze Keystore i tak nie są przenoszone;
- **narzędzia producentów** (Smart Switch, klonowanie telefonu Xiaomi/Oppo i podobne) potrafią przenieść katalog aplikacji pomijając reguły Androida: `state*.enc` i `keys.bin` lądują na nowym telefonie **bez klucza KEK**. Osobny komunikat (niżej, przypadek B);
- zmiana albo usunięcie blokady ekranu: nasz KEK (bez wymagania uwierzytelnienia) nie jest przez to unieważniany;
- uszkodzenie magazynu kluczy przez producenta, błędy po aktualizacji systemu (rzadkie, ale udokumentowane): alias jest, operacja kończy się wyjątkiem.

**Rozróżnienie przypadków (osobna logika klasyfikacji w `core`):**

- **A. Klucz niedostępny przy istniejących plikach:** alias KEK jest, ale `unwrap` DEK kończy się wyjątkiem Keystore albo `AEADBadTagException` na `keys.bin`.
- **B. Brak aliasu przy istniejących plikach `.enc` / `keys.bin`:** `containsAlias == false`. Wygląda na przeniesienie plików z innego telefonu.
- **C. Plik uszkodzony strukturalnie:** zły nagłówek, ucięty plik (jak dziś `*.uszkodzony`).
- **D. Plik z nowszej wersji aplikacji.**
- **F. `keys.bin` uszkodzony:** obie kopie (`keys.bin`, `keys.bin.2`) nieczytelne strukturalnie albo nie dają się rozpakować działającym KEK. Osobna klasa, bo dane `.enc` mogą być w porządku, a brakuje tylko koperty. Komunikat F poniżej; **pliku `keys.bin` ani `keys.bin.2` nigdy nie kasujemy**.
- **E. Błąd uwierzytelnienia samej treści pliku przy działającym DEK** (modyfikacja lub podmiana pliku): traktowane jak C, ale z innym komunikatem.

**Zachowanie aplikacji na pierwszym planie** (zasada: nic nie kasujemy, nic nie nadpisujemy):

1. **Nigdy nie generujemy nowego KEK pod tym samym aliasem.** Gdy brakuje klucza, a istnieją `keys.bin` lub `.enc` (przypadki A i B), aplikacja **nie** tworzy nowego klucza automatycznie. Nowy klucz (nowy alias o najwyższym numerze o 1 większym niż dotychczasowe, nowy DEK, nowy `keys.bin`) powstaje wyłącznie po jawnym „Zaczynam od nowa” z potwierdzeniem.
2. Dotknięte osoby trafiają do istniejącego trybu odzyskiwania (`Store.recovered`): pusty stan w pamięci, **pliki `.enc` i `keys.bin` nietknięte**, zmiany automatyczne ich nie zapisują, automatyczna kopia zapasowa wstrzymana (ochrona z 0.14.4).
3. Komunikaty (do dopracowania językowo, neutralne płciowo):
   - **A:** „Nie można odczytać zaszyfrowanych danych. Klucz zabezpieczający w telefonie jest niedostępny, ale pliki z danymi zostały zachowane. Wczytaj ostatnią kopię zapasową w Ustawieniach (kopia z hasłem nie zależy od klucza w telefonie) albo zacznij od nowa. Po rozpoczęciu od nowa zachowane pliki można usunąć dopiero przez »Usuń wszystkie dane«.”
   - **B:** „Te dane wyglądają na przeniesione z innego telefonu: w tym telefonie nie ma klucza, którym je zaszyfrowano, i nie da się ich odczytać. Wczytaj kopię zapasową z hasłem zrobioną na poprzednim telefonie.”
   - **F:** „Plik z kluczami szyfrowania jest uszkodzony, a jego zapasowa kopia też. Pliki z danymi zostały zachowane, ale bez tego pliku nie da się ich odczytać. Wczytaj ostatnią kopię zapasową z hasłem w Ustawieniach albo zacznij od nowa.”
   - **C/E:** jak dziś („Plik danych był uszkodzony”), z nazwą kopii `*.uszkodzony.enc`.
   - **D:** „Dane zapisano w nowszej wersji aplikacji. Zaktualizuj aplikację.”
4. Przycisk „Zaczynam od nowa” wymaga potwierdzenia za każdym razem; stary plik zostaje jako `*.nieczytelny.enc` (identyfikator logiczny oryginału w AAD, więc nadal da się go odszyfrować, jeśli klucz kiedyś wróci) do czasu „Usuń wszystkie dane”.
5. Przy włączonym szyfrowaniu w Ustawieniach → Dane stałe przypomnienie o kopii z hasłem (jedyna kopia niezależna od klucza w telefonie).
6. Z kopii zapasowej odtwarzamy dane już zaszyfrowane **nowym** DEK (`replaceAll` przez normalną ścieżkę).

**Odczyty w tle** (widżet `BpWidget`, odbiorniki alarmów i powiadomień, `Reminders.people`, czyli kod wołający `Store.readAll(dir)` bez uruchomionego `Store`):

- działają w trybie **tylko do odczytu**: **nie wchodzą w tryb odzyskiwania, nie zapisują niczego, nie tworzą ani nie zmieniają kluczy, nie przenoszą ani nie usuwają plików** (także `.enc` strukturalnie uszkodzonych: sprzątanie robi wyłącznie proces z `Store` na pierwszym planie);
- przy błędzie klucza zwracają „niedostępne” (wynik klasy zamiast wyjątku). Alarmy, które już są zaplanowane w systemie, pozostają; **po restarcie telefonu system kasuje zaplanowane alarmy, a my planujemy je na nowo wyłącznie z odczytanych danych: jeśli danych nie da się odczytać, alarmy NIE są planowane na nowo i przypomnienia o dawkach nie działają do czasu naprawy w aplikacji.** Jedynym sygnałem jest wtedy **jedno** ogólne powiadomienie „Otwórz aplikację: nie można odczytać danych” (bez nazw leków, imion i godzin) zamiast przypomnień; widżet pokazuje „—”;
- ponowna próba przy następnym zdarzeniu; stan „niedostępne” nie jest zapisywany na dysku.

## 5. Wpływ na kopie zapasowe i pliki pomocnicze

- **Eksport i import JSON, kopie z hasłem (`Crypto.kt`, PBKDF2 600 000 iteracji + AES-GCM):** bez zmian. Format kopii v3 zostaje, pliki ze starszych wersji (`core/src/test/resources/fixtures`) nadal się wczytują. Kopie nie zależą od klucza z Keystore, więc są właściwą drogą ratunku.
- **`AutoBackup`:** korzysta z danych w pamięci (`Store.exportStates()`), więc zapis szyfrowany go nie dotyka. Zmiany: (a) wstrzymany w trybie odzyskiwania; (b) przy włączonym szyfrowaniu danych aplikacja **domyślnie proponuje kopię z hasłem**; kopia JSON bez hasła w wybranym folderze wymaga **dodatkowego potwierdzenia za każdym razem** (decyzja Nexus) z ostrzeżeniem, że folder nie jest szyfrowany kluczem aplikacji.
- **Udostępnianie i eksport do pliku:** bez zmian (świadoma czynność użytkownika). Pliki tymczasowe w `cache/share` i `cache/camera` są dalej sprzątane przy starcie.
- **`*.uszkodzony.json`:** jawna kopia uszkodzonego pliku. W kroku 5 migracji jest szyfrowana do `*.uszkodzony.enc` (rodzaj AAD `uszkodzony`, osoba oryginału) i usuwana w formie jawnej. Nowe kopie powstają od razu jako `.enc` (surowe bajty uszkodzonego pliku w kopercie z ustawionym bitem „surowe”, rodzaj AAD `uszkodzony`).
- **`state.przed-osobami.json`:** jawny zapas pierwszej osoby sprzed wielu osób. Po udanej migracji (znacznik `enc`) jest **usuwany** (decyzja Nexus); nie jest szyfrowany (nie ma dla niego rodzaju AAD). Do tego czasu nadal znika razem z pierwszą osobą i przy „Usuń wszystkie dane”.
- **Pliki `.bak` z `AtomicFile`:** po przerwanym zapisie mogą zawierać jawne dane; sprzątanie po migracji usuwa je dla jawnych plików.
- **Dzienniki:** w logach (`Log.*`) nie trafia żadna treść danych, imiona, nazwy leków, rozmiary plików per osoba ani wartości kluczy; tylko klasa błędu i rodzaj operacji. Reguła dopisywana do `CLAUDE.md` przy implementacji, sprawdzana w code review.
- **Pliki ML Kit / OCR i zdjęcia aparatu:** poza zakresem.

## 6. Testy

**`core` (JVM, bez Androida; wymóg z `CLAUDE.md`):**

- `DataFile` (koperta, nagłówek, generacja, AAD z identyfikatorem logicznym) na interfejsie `DataCipher` (`encrypt(plain, aad)`, `decrypt(blob, aad)`); w testach zwykła implementacja AES-GCM z `javax.crypto`. Interfejs `KeyWrap` (owijanie DEK) z fałszywym KEK w testach.
- Format: runda zapis-odczyt; wykrywanie jawny / zaszyfrowany / uszkodzony / nowszej wersji; zmiana pojedynczego bajtu nagłówka, generacji, IV, szyfrogramu i znacznika (każda kończy się błędem uwierzytelnienia); **zmiana nazwy pliku nie psuje odszyfrowania, zamiana identyfikatora logicznego (inna osoba) tak**; IV nigdy się nie powtarza w serii zapisów; pliki puste i obcięte; zgodność z fixtures kopii v3.
- **Automat stanów migracji** (`Migration` w `core`) nad interfejsem `FileOps` z **wstrzykiwaniem przerwania po każdym kroku**: dla k od 1 do ostatniego (jedna i osiem osób, z plikami pomocniczymi), w obie strony (włączenie i wyłączenie), po każdym przerwaniu „uruchamia aplikację ponownie” (reguła odczytu z rozdziału 3) i sprawdza, że dane są równe oryginałowi, a dokończenie migracji kończy w stanie docelowym. Przypadki dodatkowe: brak miejsca, niezgodna weryfikacja kandydata, zmiana zegara (przesunięcie znaczników czasu w `FileOps` w obie strony nie zmienia wyniku), zapis w trakcie migracji zablokowany, trzy nieudane próby, znacznik uszkodzony lub nieobecny.
- **Błąd klucza nie kasuje:** dla każdego wyjątku z listy w rozdziale 3 (brak aliasu, unieważnienie, `AEAD`) sprawdzamy, że żaden plik nie został usunięty, przeniesiony ani nadpisany, a wynik to właściwy przypadek A lub B (klasyfikator).
- **Tryb odzyskiwania = zero zapisów z tła:** test na `FileOps` z licznikiem zapisów: odczyt w trybie tylko do odczytu (ścieżka widżetu i alarmów) przy błędzie klucza nie wykonuje żadnego zapisu, usunięcia ani utworzenia klucza; `Store` w trybie odzyskiwania nie zapisuje osób w stanie odzyskiwania przy `updateAll`, `markBackup` ani żadnej zmianie automatycznej (rozszerzenie `ProfilesTest`).
- Znacznik: brak, uszkodzony, nieważny (zły `v`, nieznany `mode`, `dekId` niezgodny z `keys.bin`, `attempts` poza zakresem) przy istniejących `.enc` lub `keys.bin` daje tryb `enc` tylko do odczytu, bez ani jednego zapisu; bez `.enc` i `keys.bin` daje `plain`.
- Przerwana migracja po restarcie: `to-enc` wraca do `plain`, `to-plain` do `enc`, `attempts` rośnie, zapisy są odblokowane (po restarcie nigdy nie zostajemy z wstrzymanymi zapisami); po trzech próbach brak automatycznej ponowki.
- `keys.bin`: weryfikacja `unwrap` po zapisie, odczyt z `keys.bin.2` przy uszkodzeniu pierwszej kopii, klasa F przy dwóch uszkodzonych kopiach, brak kasowania i brak nowego klucza w obu przypadkach; zwykły zapis danych nie dotyka żadnej kopii.
- Rotacja KEK (nowy alias zapisany w `keys.bin`, przerwanie po każdym kroku, osierocony alias sprzątany tylko gdy żadna kopia `keys.bin` na niego nie wskazuje; „Zaczynam od nowa” po rotacji nie dostaje tego samego aliasu). Rotacji DEK nie ma w zakresie.
- Alarmy po restarcie bez odczytu danych: nie są planowane, wysyłane jest jedno ogólne powiadomienie.
- Kopia przed migracją: weryfikacja odczytu kopii z hasłem i porównania ze stanem.
- Dane testowe wyłącznie zmyślone.

**`app` (CI: kompilacja i zrzuty; brak emulatora, więc bez prawdziwego Keystore):**

- Cienka warstwa: `KeystoreKeyWrap : KeyWrap` (KEK, StrongBox z awaryjnym TEE, mapowanie wyjątków na klasy z rozdziału 4), wywołania w `Store`, ekran Ustawienia → Dane (włącz/wyłącz, stan, ostrzeżenie o braku blokady ekranu, wybór folderu kopii), komunikaty trybu odzyskiwania.
- Zrzuty ekranów (Paparazzi): komunikaty A, B, C, D, ekran włączania szyfrowania, ostrzeżenie o kopii.
- Robolectric nie obsługuje `AndroidKeyStore`, więc nie dodajemy tej zależności; testy Keystore są ręczne.

**Ręcznie na telefonie (lista trafia też do README, sekcja „Do sprawdzenia na telefonie”):**

- aktualizacja z wersji z jawnymi plikami (kilka osób, duże EKG): wszystko na miejscu;
- zabicie aplikacji w trakcie migracji (`adb shell am force-stop` w różnych momentach na dużych danych), także przy wyłączaniu;
- alarm o dawce i widżet przy zablokowanym ekranie oraz po restarcie telefonu; zachowanie przy symulowanym braku klucza (telefon testowy z podmienionym `keys.bin`);
- zmiana blokady ekranu, dodanie odcisku palca, usunięcie blokady ekranu: dane muszą się czytać;
- przeniesienie plików narzędziem producenta (Smart Switch lub klonowanie) na drugi telefon: komunikat B;
- odinstalowanie i instalacja z kopii z hasłem; wczytanie kopii na drugim telefonie;
- telefon bez StrongBox i z nim; nakładki producentów (Realme, Oppo, Xiaomi, Samsung);
- mało wolnego miejsca w trakcie migracji; zmiana zegara systemowego w tył i w przód w trakcie migracji;
- brak INTERNET w APK nadal sprawdza CI.

## 7. Ryzyka i wycofanie

| Ryzyko | Skutek | Ograniczenie |
|---|---|---|
| uszkodzony `keys.bin` albo znacznik | dane nieczytelne lub tylko do odczytu | druga kopia `keys.bin.2`, weryfikacja po zapisie, klasa F, tryb `enc` tylko do odczytu przy nieważnym znaczniku, kopia z hasłem |
| utrata klucza przy zachowanych plikach | dane nieczytelne | tryb odzyskiwania bez kasowania, kopia z hasłem poza `files/` sprawdzona przed migracją, przypomnienia o kopii, osobne komunikaty A/B, koperta z hasłem później |
| przeniesienie plików narzędziem producenta | nieczytelne dane na nowym telefonie | komunikat B, kopie z hasłem |
| błąd w migracji | utrata lub rozjazd danych | znacznik trybu, jawny plik do zatwierdzenia, test przerwań po każdym kroku w obie strony, wstrzymanie zapisu |
| zegar systemowy | zły wybór pliku | brak zależności od `lastModified`; wiążący jest znacznik |
| odczyt w tle zawodzi | brak przypomnień o dawkach | tryb tylko do odczytu, jedno ogólne powiadomienie, zaplanowane alarmy zostają |
| błąd Keystore na konkretnym telefonie | niemożność włączenia | próba na bajtach testowych przed migracją, funkcja opcjonalna, wyłączenie bez utraty danych |
| DEK w pamięci procesu | jak dziś odszyfrowane dane | brak w logach, plikach i zrzutach |
| koszt czasu zapisu (EKG kilka MB) | opóźnienie | szyfrowanie programowe DEK (nie StrongBox), w wątku zapisu, pomiar na dużych danych |
| telefon bez blokady ekranu | słabsza ochrona | ostrzeżenie w Ustawieniach |
| fałszywe poczucie bezpieczeństwa | użytkownik zakłada ochronę kopii JSON bez hasła | opis w README i Ustawieniach, potwierdzenie za każdym razem, domyślna kopia z hasłem |
| wymazanie fizyczne na flash nie jest gwarantowane | resztki jawnych danych | ograniczenie (najlepsza próba), zabezpieczeniem jest FBE |

**Wycofanie:**

1. Etapy 1 i 2 nie zmieniają zapisu: wycofanie to cofnięcie wydania.
2. Po włączeniu szyfrowania (etap 3): Ustawienia → Dane → „Wyłącz szyfrowanie” (automat `to-plain`, rozdział 3). Starsze wersje aplikacji bez czytnika `.enc` nie odczytają zaszyfrowanych plików, dlatego etap 3 wymaga wcześniejszego etapu 2.
3. Kopia z hasłem wczytuje się w każdej wersji aplikacji (format v3 bez zmian).

## Etapy wdrożenia (po zatwierdzeniu planu)

Każdy etap to osobny, mały PR (gałąź `feat/…`), z testami i wpisem w CHANGELOG, bez samodzielnego scalania.

1. **Format, koperta i automat migracji w `core`:** `DataFile`, `DataCipher`, `KeyWrap`, `FileOps`, `Migration`, klasyfikator błędów klucza, reguła odczytu w `Profiles` (z trybem tylko do odczytu), testy z przerwaniami. Bez zmian w `app` i bez zmiany zachowania.
2. **Czytnik obu formatów w `app`:** `Store` umie czytać `.enc` i `.json` (`KeystoreKeyWrap` istnieje), ale **nadal zapisuje jawnie**. Odczyty w tle w trybie tylko do odczytu. Wydanie testowe sprawdza odczyt `.enc` podłożonego ręcznie na telefonie testowym.
3. **Włączenie (opcjonalne, domyślnie wyłączone):** ekran w Ustawieniach → Dane, krok 0 z kopią poza `files/`, migracja, `keys.bin` z drugą kopią, rotacja KEK (bez rotacji DEK), komunikaty A/B/C/D/F i odtwarzanie znacznika, zrzuty ekranów, lista testów ręcznych, reguła „brak danych w logach” w `CLAUDE.md`.
4. **Po okresie testów:** domyślne włączenie wyłącznie dla nowych instalacji (decyzja Nexus); koperta z hasłem do DEK jako osobny plan.

## Decyzje (stan po review Nexus; do potwierdzenia przez Jack4B)

1. Opcjonalne, domyślnie włączane dopiero dla nowych instalacji po okresie testów: **przyjęte w planie**.
2. DEK + KEK od razu, koperta z hasłem później: **przyjęte**.
3. `profiles.json` jawny (bez imion): **przyjęte**.
4. `state.przed-osobami.json` usuwany po udanej migracji: **przyjęte**.
5. Potwierdzenie przy kopii JSON bez hasła za każdym razem: **przyjęte**.
6. Dziennik cyklu pod biometrią: **nie teraz**.

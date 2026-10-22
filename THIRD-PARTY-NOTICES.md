# Składniki zewnętrzne i licencje

Ten plik jest zgodny z listą w aplikacji (O aplikacji → Licencje i źródła; kod: `core/.../Licenses.kt`). Przy każdej zmianie zależności lub dodaniu cudzego kodu, modelu lub danych należy go zaktualizować razem z README i CHANGELOG.

## Dołączone do aplikacji

| Składnik | Licencja | Do czego służy | Prawa autorskie |
|---|---|---|---|
| [Phosphor Icons](https://github.com/phosphor-icons/core) | MIT | ikony interfejsu (wektory w `PhIcons.kt`) | Copyright (c) 2023 Phosphor Icons |
| Jetpack Compose, Material 3, AndroidX | Apache-2.0 | interfejs, cykl życia, odczyt orientacji zdjęć, okno odblokowania biometrycznego (AndroidX Biometric) | Copyright The Android Open Source Project |
| Kotlin, kotlinx.serialization, kotlinx.coroutines | Apache-2.0 | język i biblioteki programu | Copyright JetBrains s.r.o. and contributors |
| ML Kit Text Recognition (model łaciński dołączony) | [Warunki Google ML Kit](https://developers.google.com/ml-kit/terms), nie jest to licencja open source | rozpoznawanie tekstu ze zdjęć i PDF na urządzeniu | Google LLC |

Pełny tekst licencji Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>.

### Phosphor Icons, licencja MIT

```
MIT License

Copyright (c) 2023 Phosphor Icons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Algorytmy własne, oparte na publikacjach (kod napisany od zera)

- Pan J., Tompkins W.J.: A Real-Time QRS Detection Algorithm. IEEE Trans. Biomed. Eng. 1985 (wykrywanie uderzeń).
- Richman J.S., Moorman J.R.: Physiological time-series analysis using approximate entropy and sample entropy. Am. J. Physiol. 2000 (entropia próbkowa).
- Dash S. i in.: Automatic real time detection of atrial fibrillation. Ann. Biomed. Eng. 2009 (odsetek punktów zwrotnych i RMSSD odstępów RR).

Nie skopiowano kodu z żadnego z tych źródeł. Implementacje w Pythonie na licencji GPL (np. py-ecg-detectors, GPL-3.0) świadomie nie były używane.

## Ocenione, niedołączone: HeartKit (AmbiqAI)

Stan na 22.10.2026: **niedołączony do aplikacji**. Poniżej dane, które trzeba zachować, gdyby jego kod lub modele zostały dołączone.

- Repozytorium: <https://github.com/AmbiqAI/heartkit>, licencja **BSD-3-Clause**, Copyright (c) 2022, Ambiq. Użycie komercyjne i w zamkniętej aplikacji jest dozwolone.
- Obowiązki: zachować notę, warunki i zastrzeżenie w dystrybucji źródeł; w dystrybucji binarnej (APK) umieścić je w dokumentacji lub w aplikacji (ten plik i ekran „Licencje i źródła”); nie używać nazwy Ambiq ani autorów do promocji bez zgody. Można pisać neutralnie: „wykorzystuje projekt heartKIT (AmbiqAI)”.
- Do sprawdzenia przed dołączeniem: licencje modeli (Model Zoo) i zbiorów, na których je wytrenowano (Icentia11k, LUDB, QTDB, PTB-XL i inne), oraz licencje zależności; BSD-3-Clause nie zawiera jawnej licencji patentowej. To nie jest porada prawna.

```
BSD 3-Clause License

Copyright (c) 2022, Ambiq
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

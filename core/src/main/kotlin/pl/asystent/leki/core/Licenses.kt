package pl.asystent.leki.core

/**
 * Informacje o licencjach składników zewnętrznych: pokazywane w aplikacji (O aplikacji) i powtórzone w THIRD-PARTY-NOTICES.md.
 * Lista [included] obejmuje wyłącznie to, co faktycznie trafia do APK; [evaluated] to projekty ocenione, ale niedołączone.
 */
object Licenses {
    data class Notice(val name: String, val license: String, val role: String, val copyright: String, val url: String)

    val included = listOf(
        Notice("Phosphor Icons", "MIT", "ikony interfejsu (wektory w pliku PhIcons.kt)", "Copyright (c) 2023 Phosphor Icons", "https://github.com/phosphor-icons/core"),
        Notice("Jetpack Compose, Material 3 i pozostałe biblioteki AndroidX", "Apache-2.0", "interfejs, cykl życia, odczyt orientacji zdjęć, okno odblokowania biometrycznego (AndroidX Biometric)", "Copyright The Android Open Source Project", "https://developer.android.com/jetpack"),
        Notice("Kotlin, kotlinx.serialization i kotlinx.coroutines", "Apache-2.0", "język oraz biblioteki programu (format kopii zapasowej, współbieżność)", "Copyright JetBrains s.r.o. and contributors", "https://kotlinlang.org"),
        Notice("ML Kit Text Recognition (model łaciński dołączony do aplikacji)", "Warunki Google ML Kit (to nie jest licencja open source)", "rozpoznawanie tekstu ze zdjęć i PDF na urządzeniu", "Google LLC", "https://developers.google.com/ml-kit/terms"),
    )

    /** Ocenione, ale niedołączone do aplikacji (decyzja o użyciu wymaga jeszcze sprawdzenia modeli i danych treningowych). */
    val evaluated = listOf(
        Notice("HeartKit (AmbiqAI)", "BSD-3-Clause", "modele rytmu i klasyfikacji uderzeń; niedołączony", "Copyright (c) 2022, Ambiq", "https://github.com/AmbiqAI/heartkit"),
    )

    /** Publikacje, na których oparto algorytmy własne (zaimplementowane od zera, bez kopiowania kodu). */
    val references = listOf(
        "Pan J., Tompkins W.J.: A Real-Time QRS Detection Algorithm. IEEE Trans. Biomed. Eng. 1985 (wykrywanie uderzeń).",
        "Richman J.S., Moorman J.R.: Physiological time-series analysis using approximate entropy and sample entropy. Am. J. Physiol. 2000 (entropia próbkowa).",
        "Dash S. i in.: Automatic real time detection of atrial fibrillation. Ann. Biomed. Eng. 2009 (odsetek punktów zwrotnych i RMSSD odstępów RR).",
    )
}

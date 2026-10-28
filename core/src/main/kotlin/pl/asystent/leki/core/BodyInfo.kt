package pl.asystent.leki.core

import java.time.LocalDate

/**
 * Wiek, masa ciała i wzrost osoby (Ustawienia) oraz wskaźniki z nich liczone. Dane są opcjonalne (0 = nie podano), trafiają do tekstu dla AI
 * (pigułka „Wiek, masa, wzrost”) i do kopii zapasowej; nie służą do żadnej oceny w aplikacji. BMI jest tylko liczbą, bez opisu (u dzieci i osób starszych
 * interpretacja jest inna).
 */
object BodyInfo {
    const val MIN_YEAR = 1900
    val WEIGHT_KG = 2.0..400.0
    val HEIGHT_CM = 40..250

    /** Wiek w latach na dzień [today] (z roku urodzenia, więc z dokładnością do roku); null, gdy rok jest pusty lub nieprawdopodobny. */
    fun age(birthYear: Int, today: LocalDate): Int? = (today.year - birthYear).takeIf { birthYear in MIN_YEAR..today.year }

    /** BMI z jednym miejscem po przecinku; null, gdy brakuje masy lub wzrostu. */
    fun bmi(weightKg: Double, heightCm: Int): Double? {
        if (weightKg !in WEIGHT_KG || heightCm !in HEIGHT_CM) return null
        val m = heightCm / 100.0
        return Math.round(weightKg / (m * m) * 10) / 10.0
    }

    /** Rok urodzenia z tekstu: pusty = 0 (brak), poprawny rok = rok, inaczej null (nie zapisujemy). */
    fun parseYear(text: String, today: LocalDate): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return t.toIntOrNull()?.takeIf { it in MIN_YEAR..today.year }
    }

    /** Masa ciała z tekstu (przecinek lub kropka, zaokrąglona do 0,1 kg): pusty = 0.0 (brak), poza zakresem null. */
    fun parseWeight(text: String): Double? {
        if (text.isBlank()) return 0.0
        return parseNum(text)?.let { Math.round(it * 10) / 10.0 }?.takeIf { it in WEIGHT_KG }
    }

    /** Wzrost z tekstu: pusty = 0 (brak), poza zakresem null. */
    fun parseHeight(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return t.toIntOrNull()?.takeIf { it in HEIGHT_CM }
    }

    /** Podane dane jednym zdaniem (do tekstu dla AI i podglądu w ustawieniach); null, gdy nic nie podano. */
    fun describe(c: Settings, today: LocalDate): String? {
        val parts = ArrayList<String>()
        age(c.birthYear, today)?.let { parts.add("wiek ok. $it ${plN(it, "rok", "lata", "lat")} (rok urodzenia ${c.birthYear})") }
        if (c.weightKg in WEIGHT_KG) parts.add("masa ciała ${fmtNum(c.weightKg, 1)} kg")
        if (c.heightCm in HEIGHT_CM) parts.add("wzrost ${c.heightCm} cm")
        bmi(c.weightKg, c.heightCm)?.let { parts.add("BMI ${fmtNum(it, 1)}") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

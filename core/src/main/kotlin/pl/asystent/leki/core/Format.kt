package pl.asystent.leki.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object Pl {
    val dowShort = listOf("Nd", "Pn", "Wt", "Śr", "Cz", "Pt", "So")
    val dowFull = listOf("niedziela", "poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota")
    val monthsNom = listOf("styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec", "sierpień", "wrzesień", "październik", "listopad", "grudzień")
    val monthsShort = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
    val months = listOf("stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca", "lipca", "sierpnia", "września", "października", "listopada", "grudnia")
}

/** Dzień tygodnia w numeracji pliku kopii: 0 = niedziela. */
val LocalDate.jsDow: Int get() = dayOfWeek.value % 7

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
fun LocalTime.hhmm(): String = format(timeFmt)
val LocalTime.minutes: Int get() = hour * 60 + minute
fun minutesToTime(m: Int): LocalTime = LocalTime.of(((m % 1440) + 1440) % 1440 / 60, ((m % 1440) + 1440) % 60)

fun plDate(d: LocalDate): String = "%02d.%02d.%04d".format(d.dayOfMonth, d.monthValue, d.year)
fun shortDate(d: LocalDate): String = "%02d.%02d".format(d.dayOfMonth, d.monthValue)
fun longDay(d: LocalDate): String =
    Pl.dowFull[d.jsDow].replaceFirstChar { it.uppercase() } + " " + d.dayOfMonth + " " + Pl.months[d.monthValue - 1]

/** Liczba z przecinkiem dziesiętnym, bez zbędnych zer: 1 → "1", 0.5 → "0,5". */
fun fmtNum(n: Double, decimals: Int = 2): String {
    if (n.isNaN() || n.isInfinite()) return ""
    // valueOf (najkrótszy zapis dziesiętny), a nie BigDecimal(n): 1,005 ma się zaokrąglić do 1,01, a nie do 1 przez dokładny rozwój binarny (1,00499…)
    val s = BigDecimal.valueOf(n).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return s.replace('.', ',')
}

fun num(n: Double) = fmtNum(n, 2)
fun lnum(n: Double) = fmtNum(n, 3)

/** Polska odmiana liczebnika: 1 pomiar, 2 pomiary, 5 pomiarów. */
fun plN(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        n == 1 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Liczba z tekstu (przecinek lub kropka), null gdy to nie liczba. */
fun parseNum(v: String?): Double? {
    val t = v?.trim().orEmpty()
    if (t.isEmpty()) return null
    // tylko zwykłe liczby: toDoubleOrNull przyjmuje też "5f", "5d" czy "0x1p3"
    if (!Regex("""^[+-]?(\d+[.,]?\d*|[.,]\d+)$""").matches(t)) return null
    val n = t.replace(',', '.').toDoubleOrNull() ?: return null
    return if (n.isFinite()) n else null
}

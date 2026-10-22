package pl.asystent.leki.core

import java.time.LocalDate

/** Sposób grupowania długich list (pomiary ciśnienia, zapisy EKG): po dniach, tygodniach (od poniedziałku) albo miesiącach. */
enum class HistoryView { Days, Weeks, Months }

/** Okres z pozycjami, od najnowszego. [key] to początek okresu (nadaje się na klucz listy). */
data class Group<T>(val key: String, val start: LocalDate, val end: LocalDate, val items: List<T>)

fun <T> groupByPeriod(items: List<T>, view: HistoryView, dateOf: (T) -> LocalDate): List<Group<T>> {
    fun start(d: LocalDate): LocalDate = when (view) {
        HistoryView.Days -> d
        HistoryView.Weeks -> d.minusDays((d.dayOfWeek.value - 1).toLong())
        HistoryView.Months -> d.withDayOfMonth(1)
    }
    fun end(s: LocalDate): LocalDate = when (view) {
        HistoryView.Days -> s
        HistoryView.Weeks -> s.plusDays(6)
        HistoryView.Months -> s.plusMonths(1).minusDays(1)
    }
    return items.groupBy { start(dateOf(it)) }.toSortedMap(reverseOrder()).map { (s, l) -> Group(s.toString(), s, end(s), l) }
}

fun periodLabel(start: LocalDate, end: LocalDate, view: HistoryView): String = when (view) {
    HistoryView.Days -> longDay(start)
    HistoryView.Months -> Pl.monthsNom[start.monthValue - 1].replaceFirstChar { it.uppercase() } + " " + start.year
    HistoryView.Weeks ->
        if (start.month == end.month && start.year == end.year) "${start.dayOfMonth}–${end.dayOfMonth} ${Pl.months[end.monthValue - 1]} ${end.year}"
        else "${start.dayOfMonth} ${Pl.monthsShort[start.monthValue - 1]}${if (start.year != end.year) " ${start.year}" else ""} – ${end.dayOfMonth} ${Pl.monthsShort[end.monthValue - 1]} ${end.year}"
}

/** Jednowierszowe podsumowanie okresu pomiarów ciśnienia i informacja, czy są pomiary na progu podwyższonego ciśnienia lub powyżej. */
fun bpGroupSummary(items: List<Bp>, c: Settings): Pair<String, Boolean> {
    if (items.isEmpty()) return "brak pomiarów" to false
    val pulses = items.mapNotNull { it.pulse }
    val high = items.count { c.bpFlag(it) == "podwyższone" }
    val low = items.count { c.bpFlag(it) == "niskie" }
    val parts = ArrayList<String>()
    parts.add("${items.size} ${plN(items.size, "pomiar", "pomiary", "pomiarów")}")
    parts.add("średnio ${Math.round(items.map { it.sys }.average())}/${Math.round(items.map { it.dia }.average())}")
    if (pulses.isNotEmpty()) parts.add("tętno ${Math.round(pulses.average())}")
    if (high > 0) parts.add("powyżej progu: $high")
    if (low > 0) parts.add("niskie: $low")
    return parts.joinToString(" · ") to (high > 0)
}

/** To samo dla zapisów EKG: liczba, zakres tętna (z przebiegu, a gdy go nie ma, z zegarka) i liczba zapisów z oznaczeniami do omówienia z lekarzem. */
fun ecgGroupSummary(items: List<Ecg>): Pair<String, Boolean> {
    if (items.isEmpty()) return "brak zapisów" to false
    val pts = EcgTrend.points(items)
    val hrs = pts.mapNotNull { it.hr }
    val flagged = pts.count { it.attention > 0 }
    val parts = ArrayList<String>()
    parts.add("${items.size} ${plN(items.size, "zapis", "zapisy", "zapisów")}")
    if (hrs.isNotEmpty()) parts.add(if (hrs.size == 1 || Math.round(hrs.min()) == Math.round(hrs.max())) "tętno ${Math.round(hrs.max())}/min" else "tętno ${Math.round(hrs.min())}–${Math.round(hrs.max())}/min")
    if (flagged > 0) parts.add("do omówienia: $flagged")
    return parts.joinToString(" · ") to (flagged > 0)
}

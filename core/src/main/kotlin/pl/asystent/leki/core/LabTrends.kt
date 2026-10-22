package pl.asystent.leki.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Szerokość „typowej” skali parametru: rozstęp zakresu odniesienia, a gdy go nie ma, 30% średniej wartości. Służy do oceny, czy zmiana jest istotna. */
internal fun labScale(m: Marker, list: List<Lab>): Double {
    if (m.lo != null && m.hi != null && m.hi > m.lo) return m.hi - m.lo
    val mean = list.map { abs(it.value) }.average().takeIf { it.isFinite() && it > 0 } ?: return 1.0
    return mean * 0.3
}

enum class LabDir { Up, Down, Flat, Mixed }

/** Tendencja kilku ostatnich wyników: kierunek, ile kolejnych badań trwa i opis słowny. */
data class LabTrend(val dir: LabDir, val results: Int, val text: String)

/**
 * Tendencja z co najmniej trzech wyników (od najstarszego). „Rośnie” albo „maleje”, gdy ostatnie kroki (co najmniej dwa) idą w tę samą stronę,
 * a każdy jest większy niż 4% skali parametru; „stabilnie”, gdy ostatnie trzy wyniki różnią się o mniej niż 10% skali; w pozostałych przypadkach „bez wyraźnej tendencji”.
 * Opis dotyczy liczb, nie oceny zdrowotnej; ocenia lekarz.
 */
fun labTrend(m: Marker, list: List<Lab>): LabTrend? {
    if (list.size < 3) return null
    val v = list.takeLast(8).map { it.value }
    val eps = 0.04 * labScale(m, list)
    var k = 0
    var up: Boolean? = null
    for (i in v.size - 1 downTo 1) {
        val d = v[i] - v[i - 1]
        if (abs(d) < eps) break
        val u = d > 0
        if (up == null) up = u else if (up != u) break
        k++
    }
    if (k >= 2 && up != null) return if (up) LabTrend(LabDir.Up, k + 1, "rośnie od ${k + 1} badań") else LabTrend(LabDir.Down, k + 1, "maleje od ${k + 1} badań")
    val last3 = v.takeLast(3)
    return if (last3.max() - last3.min() < 0.1 * labScale(m, list)) LabTrend(LabDir.Flat, 3, "stabilnie") else LabTrend(LabDir.Mixed, 3, "bez wyraźnej tendencji")
}

/** Zmiana ostatniego wyniku względem poprzedniego: różnica, procent (gdy poprzedni nie jest zerem) i czy jest istotna (≥10% rozstępu zakresu albo ≥10% poprzedniej wartości). */
data class LabChange(val prev: Lab, val delta: Double, val pct: Double?, val significant: Boolean)

internal fun labSignificant(m: Marker, delta: Double, base: Double): Boolean =
    if (m.lo != null && m.hi != null && m.hi > m.lo) abs(delta) >= 0.1 * (m.hi - m.lo) else base != 0.0 && abs(delta / base) >= 0.1

fun labChange(m: Marker, list: List<Lab>): LabChange? {
    if (list.size < 2) return null
    val last = list[list.size - 1]; val prev = list[list.size - 2]
    val d = last.value - prev.value
    val pct = if (prev.value != 0.0) d / abs(prev.value) else null
    return LabChange(prev, d, pct, labSignificant(m, d, prev.value))
}

/** „+0,3 (+8%)”, „−2 (−5%)”; bez procentu, gdy zmiana jest mniejsza niż 0,5% albo poprzedni wynik wynosił 0. */
fun labDeltaText(delta: Double, pct: Double?): String {
    val sign = if (delta > 0) "+" else if (delta < 0) "−" else ""
    val p = pct?.let { Math.round(abs(it) * 100) }?.takeIf { it > 0 }
    return sign + lnum(abs(delta)) + if (p != null) " ($sign$p%)" else ""
}

/** Wynik najbliższy dacie sprzed roku względem ostatniego wyniku (w oknie ±75 dni), bez samego ostatniego. */
fun labYearAgo(list: List<Lab>): Lab? {
    val last = list.lastOrNull() ?: return null
    val target = last.date.minusYears(1)
    return list.dropLast(1).filter { abs(ChronoUnit.DAYS.between(target, it.date)) <= 75 }.minByOrNull { abs(ChronoUnit.DAYS.between(target, it.date)) }
}

/** Podsumowanie roku kalendarzowego jednego parametru. */
data class LabYear(val year: Int, val n: Int, val min: Double, val avg: Double, val max: Double, val out: Int)

fun labYears(m: Marker, list: List<Lab>): List<LabYear> =
    list.groupBy { it.date.year }.toSortedMap(reverseOrder()).map { (y, l) ->
        LabYear(y, l.size, l.minOf { it.value }, l.map { it.value }.average(), l.maxOf { it.value }, l.count { labFlag(it, m).isNotEmpty() })
    }

/** Okno czasu wykresu parametru. */
enum class LabWindow(val label: String, val years: Int) { Year("Rok", 1), ThreeYears("3 lata", 3), All("Wszystko", 0) }

fun labWindow(list: List<Lab>, w: LabWindow, today: LocalDate): List<Lab> =
    if (w == LabWindow.All) list else list.filter { it.date >= today.minusYears(w.years.toLong()) }

/** Wyniki sprzed roku przesunięte o rok do przodu, żeby narysować je pod tegorocznymi („nałóż rok temu”); tylko te, które po przesunięciu mieszczą się w oknie [from]–[to]. */
fun labGhost(list: List<Lab>, from: LocalDate, to: LocalDate): List<Lab> =
    list.map { it.copy(date = it.date.plusYears(1)) }.filter { it.date in from..to }

enum class LabPreset(val label: String) { LastTwo("Ostatnie i poprzednie"), YearAgo("Ostatnie i sprzed roku"), LastFour("Ostatnie 4") }

/** Daty do porównania (od najstarszej) dla gotowego skrótu; [daysDesc] to dni z wynikami od najnowszego. Puste, gdy skrót nie ma zastosowania (np. brak badania sprzed roku). */
fun labPresetDates(daysDesc: List<LocalDate>, p: LabPreset): List<LocalDate> {
    val newest = daysDesc.firstOrNull() ?: return emptyList()
    return when (p) {
        LabPreset.LastTwo -> daysDesc.take(2).sorted().takeIf { it.size == 2 } ?: emptyList()
        LabPreset.LastFour -> daysDesc.take(4).sorted().takeIf { it.size >= 2 } ?: emptyList()
        LabPreset.YearAgo -> {
            val target = newest.minusYears(1)
            val d = daysDesc.drop(1).filter { abs(ChronoUnit.DAYS.between(target, it)) <= 75 }.minByOrNull { abs(ChronoUnit.DAYS.between(target, it)) }
            if (d == null) emptyList() else listOf(d, newest)
        }
    }
}

/** Filtry listy parametrów w Badania → Przegląd (łączone logicznym „i”). */
data class LabFilter(val out: Boolean = false, val changed: Boolean = false, val pinned: Boolean = false) {
    val active: Boolean get() = out || changed || pinned
    fun matches(r: LabRow): Boolean {
        val last = r.last ?: return false
        if (out && labFlag(last, r.marker).isEmpty()) return false
        if (changed && labChange(r.marker, r.list)?.significant != true) return false
        if (pinned && !r.marker.pin) return false
        return true
    }
}

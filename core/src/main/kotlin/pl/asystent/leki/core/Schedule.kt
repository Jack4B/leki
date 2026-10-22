package pl.asystent.leki.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Jedna zaplanowana dawka: lek w danej porze dnia. [key] identyfikuje ją w zapisach dni. */
data class Item(val med: Med, val slot: Slot, val dose: Double) {
    val key: String get() = key(med.id, slot.id)

    companion object {
        fun key(medId: String, slotId: String) = "$medId|$slotId"
    }
}

fun daysBetween(a: LocalDate, b: LocalDate): Int = ChronoUnit.DAYS.between(a, b).toInt()

fun Med.phaseAt(d: LocalDate): Phase? = phases.lastOrNull { it.from <= d }
fun Med.isActive(d: LocalDate): Boolean = phases.isNotEmpty() && d >= phases.first().from && (end == null || d <= end)

/** Dawki zaplanowane na dzień, pora po porze, w kolejności pór. */
fun AppState.scheduleFor(d: LocalDate): List<Item> {
    val wd = d.jsDow
    val out = ArrayList<Item>()
    for (slot in sortedSlots()) {
        for (m in meds) {
            if (!m.isActive(d)) continue
            val p = m.phaseAt(d) ?: continue
            if (wd !in p.dow) continue
            val dose = p.doses[slot.id] ?: continue
            if (dose > 0) out.add(Item(m, slot, dose))
        }
    }
    return out
}

data class DayStats(val items: List<Item>, val n: Int, val skipped: Int, val mmolTaken: Double, val mmolPlan: Double, val hasMmol: Boolean) {
    val tot get() = items.size
    val complete get() = tot > 0 && n == tot
}

fun AppState.dayStats(d: LocalDate): DayStats {
    val items = scheduleFor(d)
    val r = day(d)
    var n = 0; var sk = 0; var mt = 0.0; var mp = 0.0; var hasM = false
    for (x in items) {
        val on = x.key in r.taken
        if (on) n++ else if (x.key in r.skipped) sk++
        if (x.med.mmol > 0) {
            hasM = true; mp += x.dose * x.med.mmol
            if (on) mt += x.dose * x.med.mmol
        }
    }
    return DayStats(items, n, sk, mt, mp, hasM)
}

/** Najwcześniejsza data, od której są jakiekolwiek dane. */
fun AppState.firstDate(today: LocalDate): LocalDate {
    var d = today
    meds.forEach { m -> m.phases.firstOrNull()?.let { if (it.from < d) d = it.from } }
    if (created < d) d = created
    days.keys.forEach { if (it < d) d = it }
    bp.forEach { if (it.date < d) d = it.date }
    return d
}

fun AppState.phaseText(m: Med, p: Phase): String {
    val parts = sortedSlots().mapNotNull { sl -> p.doses[sl.id]?.takeIf { it > 0 }?.let { sl.name + " " + num(it) + " " + m.unit } }
    var s = parts.joinToString(", ").ifEmpty { "bez dawek" }
    if (p.dow.size < 7) s += " (" + Defaults.dowOrder.filter { it in p.dow }.joinToString(", ") { Pl.dowShort[it] } + ")"
    return s
}

/** Dodaje lub zastępuje zmianę dawkowania od dnia [from]; nic nie robi, gdy obowiązuje już to samo. */
fun Med.upsertPhase(from: LocalDate, doses: Map<String, Double>, dow: List<Int>): Med {
    val eff = phaseAt(from)
    if (eff != null && eff.doses == doses && eff.dow == dow) return this
    val next = phases.filter { it.from != from } + Phase(from, doses, dow)
    return copy(phases = next.sortedBy { it.from })
}

/**
 * Dni od [from] do [to] włącznie. Zabezpieczenie przed absurdalnie długą listą (np. data z uszkodzonego pliku) obcina POCZĄTEK okresu,
 * a nie koniec: najnowsze dni są zawsze uwzględnione.
 */
fun dayList(from: LocalDate, to: LocalDate, max: Int = 4000): List<LocalDate> {
    val start = if (daysBetween(from, to) + 1 > max) to.minusDays((max - 1).toLong()) else from
    val out = ArrayList<LocalDate>()
    var d = start
    while (d <= to) { out.add(d); d = d.plusDays(1) }
    return out
}

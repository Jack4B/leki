package pl.asystent.leki.core

import java.time.LocalDate

// ---------- Ciśnienie ----------

fun Settings.bpFlag(r: Bp): String = when {
    r.sys >= hiSys || r.dia >= hiDia -> "podwyższone"
    r.sys < loSys || r.dia < loDia -> "niskie"
    else -> ""
}

fun Settings.thresholdText() = "podwyższone od $hiSys/$hiDia, niskie poniżej $loSys/$loDia"

data class CtxStats(val n: Int, val s: Int?, val d: Int?)

data class BpStats(
    val n: Int, val s: Int?, val d: Int?, val p: Int?,
    val maxS: Int?, val minS: Int?, val maxD: Int?, val minD: Int?,
    val rano: CtxStats, val wieczor: CtxStats,
)

private fun avg(a: List<Int>): Int? = if (a.isEmpty()) null else Math.round(a.sum().toDouble() / a.size).toInt()

fun bpStats(list: List<Bp>): BpStats {
    fun sub(c: String): CtxStats {
        val l = list.filter { it.ctx == c }
        return CtxStats(l.size, avg(l.map { it.sys }), avg(l.map { it.dia }))
    }
    val sy = list.map { it.sys }
    val di = list.map { it.dia }
    return BpStats(
        n = list.size, s = avg(sy), d = avg(di), p = avg(list.mapNotNull { it.pulse }),
        maxS = sy.maxOrNull(), minS = sy.minOrNull(), maxD = di.maxOrNull(), minD = di.minOrNull(),
        rano = sub("rano"), wieczor = sub("wieczór"),
    )
}

val bpOrder: Comparator<Bp> = compareBy<Bp>({ it.date }, { it.time })

// ---------- Badania ----------

fun labFlag(v: Double, m: Marker): String = when {
    m.lo != null && v < m.lo -> "poniżej zakresu"
    m.hi != null && v > m.hi -> "powyżej zakresu"
    else -> ""
}

fun rangeText(m: Marker): String = when {
    m.lo != null && m.hi != null -> lnum(m.lo) + "-" + lnum(m.hi)
    m.lo != null -> "od " + lnum(m.lo)
    m.hi != null -> "do " + lnum(m.hi)
    else -> ""
}

fun AppState.labsOf(markerId: String, upTo: LocalDate? = null): List<Lab> =
    labs.filter { it.marker == markerId && (upTo == null || it.date <= upTo) }.sortedWith(compareBy({ it.date }, { it.id }))

data class LabRow(val marker: Marker, val list: List<Lab>) {
    val last: Lab? get() = list.lastOrNull()
}

data class LabGroup(val name: String, val items: List<LabRow>)

data class LabsModel(val out: List<LabRow>, val groups: List<LabGroup>, val none: List<Marker>, val order: List<String>, val withResults: List<LabRow>)

fun AppState.labsModel(): LabsModel {
    val rows = markers.map { LabRow(it, labsOf(it.id)) }
    val with = rows.filter { it.last != null }
    val none = rows.filter { it.last == null }.map { it.marker }
    val out = with.filter { labFlag(it.last!!.value, it.marker).isNotEmpty() }.sortedByDescending { it.last!!.date }
    val rest = with.filter { labFlag(it.last!!.value, it.marker).isEmpty() }
    val map = LinkedHashMap<String, MutableList<LabRow>>()
    rest.forEach { map.getOrPut(it.marker.grp.ifEmpty { "Inne" }) { ArrayList() }.add(it) }
    val names = map.keys.sortedWith { a, b ->
        fun idx(x: String): Int { val i = Defaults.groupOrder.indexOf(x); return if (i >= 0) i else if (x == "Inne") 999 else 100 }
        val ia = idx(a); val ib = idx(b)
        if (ia != ib) ia - ib else java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pl")).compare(a, b)
    }
    val groups = names.map { LabGroup(it, map[it]!!) }
    val order = out.map { it.marker.id } + groups.flatMap { g -> g.items.map { it.marker.id } }
    return LabsModel(out, groups, none, order, with)
}

// ---------- Zgodność z planem ----------

data class AdherenceRow(
    val med: Med, val slot: Slot, val plan: Int, val taken: Int, val skipped: Int,
    val reasons: Map<String, Int>, val late: Int, val offsets: List<Int>,
) {
    val missing get() = plan - taken - skipped
    val avgOffset get() = if (offsets.isEmpty()) null else Math.round(offsets.sum().toDouble() / offsets.size).toInt()
}

/** Zgodność z planem w okresie. Dzisiejsze dawki, których pora jeszcze nie minęła, nie liczą się jako brak. */
fun AppState.adherence(from: LocalDate, to: LocalDate, today: LocalDate, nowMinutes: Int): List<AdherenceRow> {
    class Acc(val med: Med, val slot: Slot) {
        var plan = 0; var taken = 0; var skipped = 0; var late = 0
        val reasons = LinkedHashMap<String, Int>(); val offs = ArrayList<Int>()
    }
    val map = LinkedHashMap<String, Acc>()
    for (d in dayList(from, if (to < today) to else today)) {
        val r = day(d)
        for (x in scheduleFor(d)) {
            if (d == today && x.slot.time.minutes > nowMinutes && x.key !in r.taken) continue
            val a = map.getOrPut(x.key) { Acc(x.med, x.slot) }
            a.plan++
            if (x.key in r.taken) {
                a.taken++
                r.times[x.key]?.let {
                    val off = it.minutes - x.slot.time.minutes
                    a.offs.add(off)
                    if (settings.lateMin > 0 && off > settings.lateMin) a.late++
                }
            } else r.skipped[x.key]?.let {
                a.skipped++
                a.reasons[it.reason] = (a.reasons[it.reason] ?: 0) + 1
            }
        }
    }
    return map.values.map { AdherenceRow(it.med, it.slot, it.plan, it.taken, it.skipped, it.reasons, it.late, it.offs) }
}

// ---------- Przypomnienia ----------

data class Reminder(val id: Int, val title: String, val body: String, val atMillis: Long)

private fun reminderId(d: LocalDate, slotIndex: Int): Int = (Math.floorMod(d.toEpochDay(), 20000L).toInt()) * 100 + slotIndex + 10

/** Przypomnienia na 14 dni: jedno na porę, w której zostały nieodhaczone dawki. Id 1 to stoper po dawce. */
fun AppState.reminders(zone: java.time.ZoneId, nowMillis: Long, today: LocalDate): List<Reminder> {
    val out = ArrayList<Reminder>()
    val slots = sortedSlots()
    for (i in 0 until 14) {
        val d = today.plusDays(i.toLong())
        val items = scheduleFor(d)
        val r = day(d)
        slots.forEachIndexed { si, sl ->
            val open = items.filter { it.slot.id == sl.id && it.key !in r.taken && it.key !in r.skipped }
            if (open.isEmpty()) return@forEachIndexed
            val at = d.atTime(sl.time).minusMinutes(settings.remindLead.toLong()).atZone(zone).toInstant().toEpochMilli()
            if (at <= nowMillis + 5000) return@forEachIndexed
            out.add(Reminder(reminderId(d, si), "Pora na leki: " + sl.name, open.joinToString(", ") { it.med.name + " " + num(it.dose) + " " + it.med.unit }, at))
        }
    }
    if (timerEnd > nowMillis + 5000) out.add(Reminder(1, "Minął czas po: " + timerMed.ifEmpty { "leku" }, timerNote.ifEmpty { "Możesz przejść do kolejnego kroku." }, timerEnd))
    return out
}

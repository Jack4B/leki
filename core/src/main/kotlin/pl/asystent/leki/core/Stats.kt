package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.math.abs

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

/** Ocena wyniku: gdy ma własny zakres odniesienia (z badania), liczy się on, w przeciwnym razie zakres parametru. */
fun labFlag(l: Lab, m: Marker): String = labFlag(l.value, if (l.lo != null || l.hi != null) m.copy(lo = l.lo, hi = l.hi) else m)

/** Zakres odniesienia obowiązujący dla wyniku (własny albo parametru) w postaci tekstu. */
fun rangeText(l: Lab, m: Marker): String = rangeText(if (l.lo != null || l.hi != null) m.copy(lo = l.lo, hi = l.hi) else m)

fun rangeText(m: Marker): String = when {
    m.lo != null && m.hi != null -> lnum(m.lo) + "-" + lnum(m.hi)
    m.lo != null -> "od " + lnum(m.lo)
    m.hi != null -> "do " + lnum(m.hi)
    else -> ""
}

fun AppState.labsOf(markerId: String, upTo: LocalDate? = null): List<Lab> =
    // sortowanie jest stabilne: kilka wyników z tego samego dnia zostaje w kolejności dodania, więc „ostatni” to ostatnio dodany (tak samo w porównaniu dni)
    labs.filter { it.marker == markerId && (upTo == null || it.date <= upTo) }.sortedBy { it.date }

data class LabRow(val marker: Marker, val list: List<Lab>) {
    val last: Lab? get() = list.lastOrNull()
}

data class LabGroup(val name: String, val items: List<LabRow>)

/** Kolejność parametrów na liście: w grupach (z wynikami poza zakresem na górze), alfabetycznie albo od ostatnio badanych. */
enum class LabSort { Groups, Alpha, Recent }

/** Parametry z wynikami: [flat] to jedna lista w wybranej kolejności (dla A–Z i Najnowsze); [order] to kolejność do przechodzenia „poprzedni/następny” w szczegółach. */
data class LabsModel(
    val out: List<LabRow>, val groups: List<LabGroup>, val none: List<Marker>, val order: List<String>, val withResults: List<LabRow>,
    val flat: List<LabRow> = emptyList(), val sort: LabSort = LabSort.Groups,
    /** Przypięte parametry (w widoku Grupami osobna karta na górze; nie powtarzają się w grupach). */
    val pinned: List<LabRow> = emptyList(),
)

private val plCollator: java.text.Collator get() = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pl"))

/** Kolejność grup parametrów: znane grupy wg ustawień domyślnych, potem własne alfabetycznie, „Inne” na końcu. */
private val labGroupOrder = Comparator<String> { a, b ->
    fun idx(x: String): Int { val i = Defaults.groupOrder.indexOf(x); return if (i >= 0) i else if (x == "Inne") 999 else 100 }
    val ia = idx(a); val ib = idx(b)
    if (ia != ib) ia - ib else plCollator.compare(a, b)
}

/** Wiersz porównania dni: parametr i jego wyniki w kolejnych wybranych dniach (null, gdy tego dnia nie badano). */
data class LabCompareRow(val marker: Marker, val group: String, val labs: List<Lab?>, val delta: Double? = null, val pct: Double? = null, val significant: Boolean = false)

/**
 * Porównanie wyników z kilku dni: dla każdego parametru, który ma wynik w którymś z [dates], wynik z danego dnia (a przy kilku tego dnia
 * ostatni dodany). Kolejność kolumn jak w [dates]; wiersze w grupach, w grupie alfabetycznie.
 */
fun AppState.labCompare(dates: List<LocalDate>): List<LabCompareRow> {
    val col = plCollator
    return markers.mapNotNull { m ->
        val cells = dates.map { d -> labs.filter { it.marker == m.id && it.date == d }.lastOrNull() }
        if (cells.all { it == null }) return@mapNotNull null
        val present = cells.filterNotNull()
        val first = present.first(); val last = present.last()
        // zmiana między pierwszym a ostatnim z porównywanych dni, w którym parametr badano
        val delta = if (present.size >= 2) last.value - first.value else null
        val pct = if (delta != null && first.value != 0.0) delta / abs(first.value) else null
        LabCompareRow(m, m.grp.ifEmpty { "Inne" }, cells, delta, pct, delta != null && labSignificant(m, delta, first.value))
    }.sortedWith(compareBy<LabCompareRow, String>(labGroupOrder) { it.group }.thenComparator { a, b -> col.compare(a.marker.name, b.marker.name) })
}

/** Dni, w których są wyniki (od najnowszego), z liczbą wyników; do wyboru daty na liście parametrów. */
fun AppState.labDays(): List<Pair<LocalDate, Int>> = labs.groupingBy { it.date }.eachCount().toList().sortedByDescending { it.first }

/**
 * Model listy parametrów. Bez [asOf] każdy parametr pokazuje ostatni wynik (z jego własnej daty).
 * Z [asOf] to stan na ten dzień: ostatni wynik z tego dnia lub wcześniejszy (wykres i zmiana też liczą się do tego dnia);
 * z [exact] tylko parametry, które mają wynik dokładnie z tego dnia.
 */
fun AppState.labsModel(sort: LabSort = LabSort.Groups, asOf: LocalDate? = null, exact: Boolean = false, filter: LabFilter = LabFilter()): LabsModel {
    val rows = markers.map { LabRow(it, labsOf(it.id, asOf)) }
    val with = rows.filter { it.last != null && !(exact && asOf != null && it.last!!.date != asOf) }
    val none = rows.filter { it !in with }.map { it.marker }
    val shown = with.filter { filter.matches(it) }
    val col = plCollator
    val byName = Comparator<LabRow> { a, b -> col.compare(a.marker.name, b.marker.name) }
    val pinned = shown.filter { it.marker.pin }.sortedWith(byName)
    val out = shown.filter { !it.marker.pin && labFlag(it.last!!, it.marker).isNotEmpty() }.sortedByDescending { it.last!!.date }
    val rest = shown.filter { !it.marker.pin && labFlag(it.last!!, it.marker).isEmpty() }
    val map = LinkedHashMap<String, MutableList<LabRow>>()
    rest.forEach { map.getOrPut(it.marker.grp.ifEmpty { "Inne" }) { ArrayList() }.add(it) }
    val names = map.keys.sortedWith(labGroupOrder)
    val groups = names.map { LabGroup(it, map[it]!!.sortedWith(byName)) }
    val flat = when (sort) {
        LabSort.Groups -> emptyList()
        LabSort.Alpha -> shown.sortedWith(compareByDescending<LabRow> { it.marker.pin }.then(byName))
        LabSort.Recent -> shown.sortedWith(compareByDescending<LabRow> { it.marker.pin }.thenByDescending { it.last!!.date }.then(byName))
    }
    val order = if (sort == LabSort.Groups) pinned.map { it.marker.id } + out.map { it.marker.id } + groups.flatMap { g -> g.items.map { it.marker.id } } else flat.map { it.marker.id }
    return LabsModel(out, groups, none, order, with, flat, sort, pinned)
}

// ---------- Zgodność z planem ----------

data class AdherenceRow(
    val med: Med, val slot: Slot, val plan: Int, val taken: Int, val skipped: Int,
    val reasons: Map<String, Int>, val late: Int, val offsets: List<Int>,
) {
    val missing get() = plan - taken - skipped
    val avgOffset get() = if (offsets.isEmpty()) null else Math.round(offsets.sum().toDouble() / offsets.size).toInt()
}

/** Odchylenie godziny przyjęcia od pory w minutach, w przedziale od -12 do +12 godzin (dawka o 00:15 przy porze 23:30 to +45, a nie -1395). */
fun doseOffset(takenMinutes: Int, slotMinutes: Int): Int = Math.floorMod(takenMinutes - slotMinutes + 720, 1440) - 720

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
                    val off = doseOffset(it.minutes, x.slot.time.minutes)
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
    out.addAll(housekeepingReminders(zone, nowMillis, today))
    if (!settings.remindOn) return out
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

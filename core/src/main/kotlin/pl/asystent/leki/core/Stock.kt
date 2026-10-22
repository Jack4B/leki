package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Zapas leku. W leku zapisany jest stan z początku dnia [Med.stockDate] ([Med.stock], w jednostkach leku, np. tabletkach); ile zostało dziś,
 * wynika z odjęcia dawek odhaczonych od tego dnia. Przy wpisaniu „zostało teraz” aplikacja dolicza dawki już odhaczone dzisiaj ([withStockLeft]),
 * więc wpisana liczba zgadza się z tym, co użytkownik widzi w opakowaniu.
 */
data class StockForecast(
    /** Ile zostało teraz. */
    val left: Double,
    /** Za ile dni zabraknie pełnej dawki (0 = już dziś); null, gdy według planu lek nie jest brany w najbliższym roku. */
    val daysLeft: Int?,
    val runsOutOn: LocalDate?,
)

/** Suma dawek [m] odhaczonych w dniach od [from] do [to] włącznie, według dawkowania obowiązującego tego dnia. */
fun AppState.takenUnits(m: Med, from: LocalDate, to: LocalDate): Double {
    if (to < from) return 0.0
    val prefix = m.id + "|"
    var sum = 0.0
    for (d in dayList(from, to)) {
        val r = days[d] ?: continue
        val phase = m.phaseAt(d) ?: continue
        for (k in r.taken) if (k.startsWith(prefix)) sum += phase.doses[k.substring(prefix.length)] ?: 0.0
    }
    return sum
}

fun AppState.stockLeft(m: Med, today: LocalDate): Double? {
    val s = m.stock ?: return null
    val from = m.stockDate ?: return null
    return maxOf(0.0, s - takenUnits(m, from, today))
}

/** Lek z zapisanym stanem „zostało teraz” ([left]) na dzień [today]. */
fun Med.withStockLeft(s: AppState, left: Double, today: LocalDate): Med = copy(stock = maxOf(0.0, left) + s.takenUnits(this, today, today), stockDate = today)

fun Med.withoutStock(): Med = copy(stock = null, stockDate = null)

/**
 * Prognoza zapasu: dawki z planu (dziś tylko jeszcze nieodhaczone i niepominięte) są odejmowane aż do pierwszej, na którą nie starczy.
 * null, gdy lek nie ma zapisanego zapasu.
 */
fun AppState.stockForecast(m: Med, today: LocalDate, horizonDays: Int = 400): StockForecast? {
    val now = stockLeft(m, today) ?: return null
    var left = now
    val r0 = day(today)
    for (i in 0..horizonDays) {
        val d = today.plusDays(i.toLong())
        for (it in scheduleFor(d)) {
            if (it.med.id != m.id) continue
            if (i == 0 && (it.key in r0.taken || it.key in r0.skipped)) continue
            if (left + 1e-9 < it.dose) return StockForecast(now, i, d)
            left -= it.dose
        }
    }
    return StockForecast(now, null, null)
}

fun stockText(f: StockForecast, unit: String): String {
    val daysTxt = when (f.daysLeft) {
        null -> ""
        0 -> ", nie starczy już na dzisiejsze dawki"
        else -> ", starczy na około ${f.daysLeft} ${plN(f.daysLeft, "dzień", "dni", "dni")}"
    }
    return "zostało " + num(f.left) + " " + unit + daysTxt
}

/** Leki, którym zapasu starczy na [warnDays] dni lub mniej (albo już zabrakło). */
fun AppState.lowStock(today: LocalDate, warnDays: Int = settings.stockWarnDays): List<Pair<Med, StockForecast>> {
    if (warnDays <= 0) return emptyList()
    return meds.mapNotNull { m -> stockForecast(m, today)?.takeIf { it.daysLeft != null && it.daysLeft <= warnDays }?.let { m to it } }
}

data class StockRow(val med: Med, val forecast: StockForecast)

/** Stan zapasów wszystkich leków, którym śledzimy zapas (bez zakończonych): najpierw te, którym zabraknie najszybciej; bez prognozy na końcu. */
fun AppState.stockOverview(today: LocalDate): List<StockRow> =
    meds.filter { it.end == null || it.end >= today }
        .mapNotNull { m -> stockForecast(m, today)?.let { StockRow(m, it) } }
        .sortedWith(compareBy<StockRow> { it.forecast.daysLeft ?: Int.MAX_VALUE }.thenBy { it.med.name.lowercase() })

const val REMINDER_BACKUP = 3
const val REMINDER_STOCK = 4

private fun at(date: LocalDate, time: LocalTime, zone: ZoneId) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

/** Pierwsza chwila o [time] nie wcześniejsza niż dzień [from] i późniejsza niż teraz. */
private fun nextAt(from: LocalDate, time: LocalTime, zone: ZoneId, nowMillis: Long): Long {
    var d = from
    while (at(d, time, zone) <= nowMillis + 5000) d = d.plusDays(1)
    return at(d, time, zone)
}

/**
 * Przypomnienia „systemowe”, niezależne od przypomnień o dawkach: kopia zapasowa (gdy ostatnia jest starsza niż [Settings.backupRemindDays])
 * i kończący się zapas leków (gdy starczy na [Settings.stockWarnDays] dni lub mniej). Planowane są na dzień, w którym stają się aktualne,
 * a po wyświetleniu aplikacja planuje je od nowa, więc przypominają codziennie, dopóki sprawa nie zostanie załatwiona.
 */
fun AppState.housekeepingReminders(zone: ZoneId, nowMillis: Long, today: LocalDate): List<Reminder> {
    val out = ArrayList<Reminder>()
    val warn = settings.stockWarnDays
    if (warn > 0) {
        val due = meds.mapNotNull { m ->
            val f = stockForecast(m, today) ?: return@mapNotNull null
            val dl = f.daysLeft ?: return@mapNotNull null
            Triple(m, f, maxOf(0, dl - warn))
        }
        val first = due.minOfOrNull { it.third }
        if (first != null) {
            val date = today.plusDays(first.toLong())
            val lines = due.filter { it.third <= first }.joinToString("; ") { (m, f, _) ->
                val left = f.daysLeft!! - first
                m.name + ": " + if (left <= 0) "zapas się kończy" else "starczy na około $left ${plN(left, "dzień", "dni", "dni")}"
            }
            out.add(Reminder(REMINDER_STOCK, "Kończą się leki", lines + ". Pora na receptę lub zakup.", nextAt(date, LocalTime.of(9, 0), zone, nowMillis)))
        }
    }
    val bd = settings.backupRemindDays
    val hasData = meds.isNotEmpty() || bp.isNotEmpty() || labs.isNotEmpty() || ecgs.isNotEmpty()
    if (bd > 0 && hasData) {
        val last = lastBackup ?: created
        val dueDate = last.plusDays(bd.toLong())
        out.add(Reminder(
            REMINDER_BACKUP, "Czas na kopię zapasową",
            "Ostatnia kopia: " + (lastBackup?.let { plDate(it) } ?: "jeszcze nie zrobiono") + ". Dane są tylko w tym telefonie: zrób kopię w Ustawieniach.",
            nextAt(maxOf(today, dueDate), LocalTime.of(10, 0), zone, nowMillis),
        ))
    }
    return out
}

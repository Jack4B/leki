package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime

/** Jeden zapis EKG na wykresie trendu: tętno z przebiegu (mediana) i liczba oznaczeń do omówienia z lekarzem. */
data class EcgTrendPoint(val id: String, val date: LocalDate, val time: LocalTime, val hr: Double?, val attention: Int)

object EcgTrend {
    /** Zapisy od najstarszego, z tętnem z przebiegu (a gdy go nie dało się policzyć, z tętnem z zegarka). */
    fun points(ecgs: List<Ecg>): List<EcgTrendPoint> = ecgs.sortedWith(compareBy<Ecg> { it.date }.thenBy { it.time }).map { e ->
        val r = EcgAnalysis.analyze(e)
        EcgTrendPoint(e.id, e.date, e.time, r.hrMedian ?: e.watchHr.takeIf { it > 0 }?.toDouble(), r.flags.count { it.severity == EcgSeverity.Attention })
    }

    /** Krótki opis trendu do podpisu wykresu. */
    fun summary(points: List<EcgTrendPoint>): String {
        val hrs = points.mapNotNull { it.hr }
        if (hrs.size < 2) return ""
        val flagged = points.count { it.attention > 0 }
        return "Tętno ${Math.round(hrs.min())}–${Math.round(hrs.max())}/min w ${points.size} zapisach" + if (flagged > 0) ", $flagged z oznaczeniami do omówienia." else "."
    }
}

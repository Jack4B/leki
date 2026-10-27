package pl.asystent.leki.core

/**
 * Wybór zapisów EKG do raportów. [ReportParts.ecgIds] równe null oznacza tryb automatyczny (najnowsze z okresu, w raporcie dla lekarza
 * [AUTO_DOCTOR], w raporcie dla AI [AUTO_AI]); lista (także pusta) oznacza wybór ręczny: tylko zaznaczone zapisy z wybranego okresu.
 */
object EcgSelection {
    const val AUTO_DOCTOR = 6
    const val AUTO_AI = 5

    /** [chosen]: zapisy do raportu, od najnowszego; [inPeriod]: ile zapisów jest w okresie. */
    class Pick(val chosen: List<Ecg>, val inPeriod: Int)

    /** Wszystkie zapisy z okresu, od najnowszego. */
    fun inPeriod(s: AppState, per: Period): List<Ecg> =
        s.ecgs.filter { it.date >= per.from && it.date <= per.to }.sortedWith(compareByDescending<Ecg> { it.date }.thenByDescending { it.time })

    fun forReport(s: AppState, per: Period, ids: List<String>?, autoCount: Int): Pick {
        val all = inPeriod(s, per)
        return Pick(if (ids == null) all.take(autoCount) else all.filter { it.id in ids }, all.size)
    }

    /** Lista wyborów po dotknięciu zapisu [id]: z trybu automatycznego przechodzi się do ręcznego, zaczynając od zapisów, które trafiłyby do raportu dla lekarza. */
    fun toggled(s: AppState, per: Period, ids: List<String>?, id: String): List<String> {
        val base = ids ?: forReport(s, per, null, AUTO_DOCTOR).chosen.map { it.id }
        return if (id in base) base - id else base + id
    }
}

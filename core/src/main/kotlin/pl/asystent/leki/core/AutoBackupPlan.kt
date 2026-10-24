package pl.asystent.leki.core

import java.time.LocalDate

/** Reguły automatycznej kopii do folderu (część bez Androida, żeby dało się je przetestować): kiedy jest należna, jak nazywa się plik i które pliki usunąć. */
object AutoBackupPlan {
    const val PREFIX = "asystent-lekow-kopia-"
    const val KEEP = 7
    /** Po nieudanej próbie nie ponawiamy częściej niż co godzinę, żeby zepsuty folder nie uruchamiał pracy przy każdym zapisie danych. */
    const val RETRY_MS = 3_600_000L

    /** Kopia jest należna, gdy jeszcze żadnej nie było albo minął odstęp [intervalDays] dni od ostatniej udanej, i nie ma świeżej (z ostatniej godziny) nieudanej próby. */
    fun due(lastSuccessDay: Long, today: LocalDate, intervalDays: Int, lastAttemptMillis: Long, nowMillis: Long): Boolean {
        val intervalOk = lastSuccessDay < 0 || today.toEpochDay() - lastSuccessDay >= intervalDays.coerceAtLeast(1)
        val retryOk = lastAttemptMillis <= 0 || nowMillis - lastAttemptMillis >= RETRY_MS || nowMillis < lastAttemptMillis
        return intervalOk && retryOk
    }

    fun fileName(date: LocalDate, encrypted: Boolean): String = PREFIX + date + (if (encrypted) ".zaszyfrowana" else "") + ".json"

    /** Nazwy plików kopii do usunięcia: wszystkie poza [keep] najnowszymi (według daty w nazwie; pliki obce, bez prefiksu, są pomijane). */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sortedDescending().drop(keep)
}

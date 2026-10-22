package pl.asystent.leki.core

/** Zasady blokady aplikacji (odcisk palca, twarz lub blokada ekranu telefonu). Czas liczony zegarem monotonicznym (nie da się go oszukać zmianą daty). */
object AppLock {
    /** Dostępne opóźnienia: aplikacja zablokuje się, gdy była poza ekranem co najmniej tyle sekund. */
    val GRACE_SECONDS = listOf(0, 30, 300)
    val GRACE_LABELS = listOf("Zaraz", "30 s", "5 min")

    fun graceSeconds(index: Int): Int = GRACE_SECONDS.getOrElse(index) { 0 }

    /**
     * Czy po powrocie do aplikacji trzeba ją odblokować. [leftAtMs]: kiedy aplikacja zniknęła z ekranu (null = nie wiadomo, np. świeży start, więc blokujemy).
     */
    fun shouldLock(enabled: Boolean, leftAtMs: Long?, nowMs: Long, graceIndex: Int): Boolean {
        if (!enabled) return false
        if (leftAtMs == null) return true
        val away = nowMs - leftAtMs
        return away < 0 || away >= graceSeconds(graceIndex) * 1000L
    }
}

package pl.asystent.leki.core

/**
 * Okno podglądu przebiegu: sekunda przy lewej krawędzi i liczba widocznych sekund. Całe położenie jest stanem (a nie pikselami przewijania zależnymi od układu),
 * więc przejście do zdarzenia zawsze trafia w to samo miejsce, a powiększanie zachowuje sekundę pod palcem.
 */
object EcgViewport {
    const val MIN_VISIBLE = 2f

    data class Window(val start: Float, val visible: Float) {
        val end: Float get() = start + visible
        fun contains(t: Double) = t >= start && t <= end
    }

    fun clamp(w: Window, total: Float): Window {
        val v = w.visible.coerceIn(minOf(MIN_VISIBLE, total), total)
        return Window(w.start.coerceIn(0f, maxOf(0f, total - v)), v)
    }

    fun whole(total: Float) = Window(0f, total)

    fun centerOn(midSec: Float, visible: Float, total: Float) = clamp(Window(midSec - visible / 2, visible), total)

    /** Okno pokazujące zdarzenie na środku, z otoczeniem (co najmniej 4 s, dla zdarzenia rozciągniętego w czasie jego długość plus 2 s). */
    fun forEvent(e: EcgEvent, total: Float): Window {
        val end = e.endSec ?: e.tSec
        return centerOn(((e.tSec + end) / 2).toFloat(), maxOf(4f, (end - e.tSec).toFloat() + 2f), total)
    }

    /** Powiększenie ([factor] > 1) lub zmniejszenie wokół punktu ekranu ([anchorFrac] od 0 do 1): sekunda pod palcem zostaje pod palcem. */
    fun zoom(w: Window, factor: Float, anchorFrac: Float, total: Float): Window {
        if (factor <= 0f || factor.isNaN()) return clamp(w, total)
        val anchorSec = w.start + anchorFrac * w.visible
        val nv = (w.visible / factor).coerceIn(minOf(MIN_VISIBLE, total), total)
        return clamp(Window(anchorSec - anchorFrac * nv, nv), total)
    }

    /** Przesunięcie o ułamek szerokości okna (dodatni: ku późniejszym sekundom). */
    fun pan(w: Window, fraction: Float, total: Float) = clamp(Window(w.start + fraction * w.visible, w.visible), total)
}

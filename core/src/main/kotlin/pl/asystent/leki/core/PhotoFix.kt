package pl.asystent.leki.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Obraz w skali szarości (0–255) do analizy zdjęcia; mały (ok. 256 px), bo służy tylko do ustalenia korekty. */
class Gray(val w: Int, val h: Int, val px: IntArray) {
    init { require(px.size == w * h && w > 0 && h > 0) }
    fun at(x: Int, y: Int) = px[y * w + x]
}

/** Prostokąt w ułamkach rozmiaru obrazu (0–1). */
data class FracBox(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

/**
 * Plan automatycznej korekty zdjęcia badania: najpierw obrót o [rotateDeg] (zgodnie z ruchem wskazówek zegara, ten sam wzór co [PhotoFix.rotate]), potem przycięcie do [crop]
 * (ułamki obrazu po obrocie), potem rozciągnięcie jasności z przedziału [lo]–[hi] na 0–255.
 */
data class PhotoPlan(val rotateDeg: Double = 0.0, val crop: FracBox? = null, val lo: Int = 0, val hi: Int = 255) {
    val isIdentity get() = rotateDeg == 0.0 && crop == null && lo <= 8 && hi >= 247
}

/**
 * Automatyczna korekta zdjęć badań obrazowych (USG, tomografia, rezonans, RTG fotografowane z wydruku lub ekranu): kadry badania są ciemne na jasnym tle (stół, kartka),
 * więc ustalamy ich obszar, wyprostowujemy zdjęcie (pochylenie z ostrości rzutu ciemnych pikseli na pionową oś), przycinamy do kadrów i rozciągamy jasność.
 * Zdjęcie strony z tekstem albo w całości ciemne nie ma „tła do odcięcia”, więc zostaje bez zmian. Działa na małym obrazie, a wynik (obrót, ułamki, poziomy) stosuje Android.
 */
object PhotoFix {
    private const val MAX_SKEW = 12.0
    private const val MIN_SKEW = 0.75
    private const val MARGIN = 0.015
    private const val MIN_DARK_IN_BOX = 0.25
    private const val MAX_BOX_AREA = 0.92
    private const val MIN_BOX_AREA = 0.06

    fun plan(g: Gray): PhotoPlan {
        val thr = otsu(g)
        var c = skewCorrection(g)
        var r = g
        if (c != 0.0) {
            r = rotate(g, c)
            // jedna poprawka resztkowego pochylenia (pierwsze oszacowanie bywa o ok. 1° obok)
            val rest = skewCorrection(r)
            if (rest != 0.0 && abs(rest) <= 3.0) { c += rest; r = rotate(g, c) }
        }
        val box = darkBox(r, thr) ?: return PhotoPlan()
        val (lo, hi) = levels(r, box)
        return PhotoPlan(c, box, lo, hi)
    }

    /** Próg ciemny/jasny metodą Otsu (największa wariancja międzyklasowa histogramu). */
    fun otsu(g: Gray): Int {
        val hist = IntArray(256); g.px.forEach { hist[it.coerceIn(0, 255)]++ }
        val total = g.px.size.toDouble(); val sumAll = (0 until 256).sumOf { it * hist[it].toDouble() }
        var wB = 0.0; var sumB = 0.0; var best = 0.0; var first = 127; var last = 127
        for (t in 0 until 256) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            // przy dwóch skupiskach wartości wariancja jest stała między nimi: bierzemy środek tego przedziału, a nie jego brzeg
            if (v > best * 1.0000001) { best = v; first = t; last = t } else if (v >= best * 0.9999999) last = t
        }
        return (first + last) / 2 + 1
    }

    /** Ramka ciemnych kadrów (z marginesem) albo null, gdy to nie wygląda na zdjęcie badania z tłem do odcięcia (tekst, obraz w całości ciemny lub jasny). */
    internal fun darkBox(g: Gray, thr: Int): FracBox? {
        val rows = IntArray(g.h); val cols = IntArray(g.w)
        for (y in 0 until g.h) for (x in 0 until g.w) if (g.at(x, y) < thr) { rows[y]++; cols[x]++ }
        val maxR = rows.max(); val maxC = cols.max()
        if (maxR == 0) return null
        val ys = rows.indices.filter { rows[it] >= maxR * 0.1 }; val xs = cols.indices.filter { cols[it] >= maxC * 0.1 }
        if (ys.isEmpty() || xs.isEmpty()) return null
        val x0 = xs.first(); val x1 = xs.last(); val y0 = ys.first(); val y1 = ys.last()
        val area = (x1 - x0 + 1).toDouble() * (y1 - y0 + 1)
        var dark = 0
        for (y in y0..y1) for (x in x0..x1) if (g.at(x, y) < thr) dark++
        val boxShare = area / (g.w.toDouble() * g.h)
        if (dark / area < MIN_DARK_IN_BOX || boxShare > MAX_BOX_AREA || boxShare < MIN_BOX_AREA) return null
        return FracBox(
            max(0.0, x0.toDouble() / g.w - MARGIN), max(0.0, y0.toDouble() / g.h - MARGIN),
            min(1.0, (x1 + 1.0) / g.w + MARGIN), min(1.0, (y1 + 1.0) / g.h + MARGIN),
        )
    }

    /**
     * Kąt obrotu (stopnie, zgodnie z ruchem wskazówek zegara), który prostuje zdjęcie; 0, gdy jest proste albo wynik jest niepewny. Kadry badań mają proste krawędzie,
     * więc bierzemy dominujący kierunek gradientu jasności (Sobel) złożony modulo 90° (krawędzie poziome i pionowe dają ten sam kąt), ważony siłą krawędzi, z histogramu w
     * zakresie ±[MAX_SKEW]° z wygładzeniem. Obraz obrócony o φ ma krawędzie odchylone o φ, a korekta to −φ.
     */
    fun skewCorrection(g: Gray, @Suppress("UNUSED_PARAMETER") thr: Int = 0): Double {
        val bins = (2 * MAX_SKEW / 0.25).toInt() + 1
        val hist = DoubleArray(bins)
        var maxMag = 0.0
        val mags = DoubleArray(g.w * g.h); val angs = DoubleArray(g.w * g.h)
        for (y in 1 until g.h - 1) for (x in 1 until g.w - 1) {
            val gx = (g.at(x + 1, y - 1) + 2 * g.at(x + 1, y) + g.at(x + 1, y + 1)) - (g.at(x - 1, y - 1) + 2 * g.at(x - 1, y) + g.at(x - 1, y + 1))
            val gy = (g.at(x - 1, y + 1) + 2 * g.at(x, y + 1) + g.at(x + 1, y + 1)) - (g.at(x - 1, y - 1) + 2 * g.at(x, y - 1) + g.at(x + 1, y - 1))
            val m = Math.hypot(gx.toDouble(), gy.toDouble()); val i = y * g.w + x
            mags[i] = m; if (m > maxMag) maxMag = m
            var a = Math.toDegrees(Math.atan2(gy.toDouble(), gx.toDouble())) % 90.0   // krawędzie poziome i pionowe zwijają się do jednego kąta
            if (a > 45) a -= 90.0 else if (a < -45) a += 90.0
            angs[i] = a
        }
        if (maxMag == 0.0) return 0.0
        var total = 0.0
        for (i in mags.indices) {
            if (mags[i] < maxMag * 0.25) continue          // tylko wyraźne krawędzie
            val a = angs[i]; if (abs(a) > MAX_SKEW) continue
            hist[((a + MAX_SKEW) / 0.25).toInt().coerceIn(0, bins - 1)] += mags[i]; total += mags[i]
        }
        if (total == 0.0) return 0.0
        // wygładzenie oknem ±1° i wybór szczytu
        val win = 4
        val sm = DoubleArray(bins) { b -> (max(0, b - win)..min(bins - 1, b + win)).sumOf { hist[it] } }
        val peak = sm.indices.maxByOrNull { sm[it] } ?: return 0.0
        // dokładniejsza wartość: średnia ważona surowego histogramu w ±1,5° wokół szczytu
        val from = max(0, peak - 6); val to = min(bins - 1, peak + 6)
        val mass = (from..to).sumOf { hist[it] }
        val phi = if (mass > 0) (from..to).sumOf { (-MAX_SKEW + it * 0.25) * hist[it] } / mass else -MAX_SKEW + peak * 0.25
        val mean = sm.average()
        // szczyt musi wyraźnie wystawać ponad tło i być dostatecznie daleko od zera, żeby warto było obracać
        return if (abs(phi) >= MIN_SKEW && sm[peak] > mean * 2.0 && sm[peak] > sm[bins / 2] * 1.3) -phi else 0.0
    }

    /** Obraz obrócony o [deg] stopni zgodnie z ruchem wskazówek zegara (wzór x' = x cos − y sin, y' = x sin + y cos względem środka), na powiększonym płótnie wypełnionym tłem. */
    fun rotate(g: Gray, deg: Double): Gray {
        val a = Math.toRadians(deg); val s = sin(a); val co = cos(a)
        val nw = ceil(g.w * abs(co) + g.h * abs(s)).toInt().coerceAtLeast(1); val nh = ceil(g.w * abs(s) + g.h * abs(co)).toInt().coerceAtLeast(1)
        val border = ArrayList<Int>()
        for (x in 0 until g.w) { border.add(g.at(x, 0)); border.add(g.at(x, g.h - 1)) }
        for (y in 0 until g.h) { border.add(g.at(0, y)); border.add(g.at(g.w - 1, y)) }
        val fill = border.sorted()[border.size / 2]
        val out = IntArray(nw * nh) { fill }
        for (v in 0 until nh) for (u in 0 until nw) {
            val du = u + 0.5 - nw / 2.0; val dv = v + 0.5 - nh / 2.0
            val x = du * co + dv * s + g.w / 2.0; val y = -du * s + dv * co + g.h / 2.0
            if (x >= 0 && y >= 0 && x < g.w - 1 && y < g.h - 1) {
                // interpolacja dwuliniowa: schodki na krawędziach nie zaburzają oceny kierunku krawędzi
                val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
                val top = g.at(xi, yi) * (1 - fx) + g.at(xi + 1, yi) * fx; val bot = g.at(xi, yi + 1) * (1 - fx) + g.at(xi + 1, yi + 1) * fx
                out[v * nw + u] = Math.round(top * (1 - fy) + bot * fy).toInt()
            }
        }
        return Gray(nw, nh, out)
    }

    /** Poziomy do rozciągnięcia jasności w ramce (1. i 99. percentyl); bez zmiany, gdy zakres jest już pełny, zbyt wąski (jednolity obraz) albo wzmocnienie byłoby większe niż 2×. */
    internal fun levels(g: Gray, box: FracBox): Pair<Int, Int> {
        val hist = IntArray(256); var n = 0
        // bez marginesu dodanego do ramki (tło wokół kadrów zawyżałoby górny poziom)
        val x0 = floor((box.x0 + MARGIN) * g.w).toInt().coerceIn(0, g.w - 1); val x1 = ceil((box.x1 - MARGIN) * g.w).toInt().coerceIn(x0 + 1, g.w)
        val y0 = floor((box.y0 + MARGIN) * g.h).toInt().coerceIn(0, g.h - 1); val y1 = ceil((box.y1 - MARGIN) * g.h).toInt().coerceIn(y0 + 1, g.h)
        for (y in y0 until y1) for (x in x0 until x1) { hist[g.at(x, y).coerceIn(0, 255)]++; n++ }
        fun pct(p: Double): Int { var acc = 0; for (t in 0 until 256) { acc += hist[t]; if (acc >= n * p) return t }; return 255 }
        val lo = pct(0.01); var hi = pct(0.99)
        if (hi - lo < 40) return 0 to 255
        if (hi < lo + 128) hi = lo + 128
        return if (lo <= 8 && hi >= 247) 0 to 255 else lo to min(255, hi)
    }
}

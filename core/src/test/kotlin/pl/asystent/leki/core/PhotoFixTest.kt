package pl.asystent.leki.core

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Automatyczna korekta zdjęć badań na syntetycznych obrazach (tło stołu, biała kartka, ciemne kadry z jasnymi plamkami). */
class PhotoFixTest {
    private val W = 300; private val H = 400

    /** Pasek: kartka (240) z trzema ciemnymi kadrami (25) i jasnymi plamkami na jasnym tle stołu (190). */
    private fun strip(): Gray {
        val px = IntArray(W * H) { 190 }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) { for (y in y0 until y1) for (x in x0 until x1) px[y * W + x] = v }
        rect(70, 40, 230, 360, 240)
        val rnd = Random(3)
        for ((y0, y1) in listOf(70 to 160, 180 to 270, 290 to 340)) {
            rect(90, y0, 210, y1, 25)
            repeat(250) { px[(y0 + rnd.nextInt(y1 - y0)) * W + 90 + rnd.nextInt(120)] = 150 + rnd.nextInt(100) }
        }
        return Gray(W, H, px)
    }

    private fun near(expected: Double, actual: Double, eps: Double, what: String) = assertTrue(abs(expected - actual) <= eps, "$what: oczekiwano ok. $expected, jest $actual")

    @Test fun cropsToTheDarkFramesAndLeavesAMargin() {
        val p = PhotoFix.plan(strip())
        val c = assertNotNull(p.crop)
        near(90.0 / W - 0.015, c.x0, 0.02, "lewy"); near(210.0 / W + 0.015, c.x1, 0.02, "prawy")
        near(70.0 / H - 0.015, c.y0, 0.02, "górny"); near(340.0 / H + 0.015, c.y1, 0.02, "dolny")
        assertEquals(0.0, p.rotateDeg, "proste zdjęcie nie jest obracane")
    }

    @Test fun aTiltedPhotoIsStraightenedAndCroppedTightly() {
        for (deg in listOf(4.0, -6.0, 9.0)) {
            val tilted = PhotoFix.rotate(strip(), deg)
            val p = PhotoFix.plan(tilted)
            near(-deg, p.rotateDeg, 1.2, "korekta dla pochylenia $deg° (resztkowe pochylenie do ok. 1° jest niewidoczne)")
            val c = assertNotNull(p.crop, "ramka po wyprostowaniu ($deg°)")
            // po wyprostowaniu ramka ma szerokość kadrów (120 px) i wysokość całego paska kadrów (270 px) plus margines po 1,5% z każdej strony
            val rotated = PhotoFix.rotate(tilted, p.rotateDeg)
            near(120.0 + 2 * 0.015 * rotated.w, (c.x1 - c.x0) * rotated.w, 12.0, "szerokość ramki z marginesem ($deg°)")
            near(270.0 + 2 * 0.015 * rotated.h, (c.y1 - c.y0) * rotated.h, 14.0, "wysokość ramki z marginesem ($deg°)")
        }
    }

    @Test fun textPagesAndFullFrameImagesAreLeftAlone() {
        // strona tekstu: biała z rzadkimi ciemnymi wierszami
        val page = IntArray(W * H) { 235 }
        for (row in 0 until 30) for (x in 30 until 270) if ((x / 3 + row) % 4 != 0) page[(40 + row * 11) * W + x] = 20
        assertEquals(PhotoPlan(), PhotoFix.plan(Gray(W, H, page)))
        // obraz w całości ciemny (kadr bez tła)
        assertNull(PhotoFix.plan(Gray(W, H, IntArray(W * H) { 30 + (it % 7) * 10 })).crop)
        // jednolity obraz
        assertEquals(PhotoPlan(), PhotoFix.plan(Gray(W, H, IntArray(W * H) { 128 })))
    }

    @Test fun levelsStretchALowContrastFrameButNotAFullRangeOne() {
        val px = IntArray(W * H) { 200 }
        for (y in 80 until 320) for (x in 80 until 220) px[y * W + x] = 60 + ((x + y) % 60)   // kadr 60–119
        val low = PhotoFix.plan(Gray(W, H, px))
        assertTrue(low.crop != null && low.lo in 50..70 && low.hi in 110..190, "poziomy ${low.lo}–${low.hi}")
        assertTrue(255.0 / (low.hi - low.lo) <= 2.01, "wzmocnienie nie przekracza 2×")
        val full = PhotoFix.plan(strip())
        assertTrue(full.lo <= 8 || full.hi - full.lo >= 120, "kadr z jasnymi plamkami ma szeroki zakres")
        assertTrue(PhotoPlan().isIdentity && !PhotoPlan(rotateDeg = 2.0).isIdentity)
    }

    @Test fun rotationKeepsTheSizeFormulaAndFillsWithTheBackground() {
        val r = PhotoFix.rotate(strip(), 5.0)
        assertTrue(r.w > W && r.h > H)
        assertEquals(190, r.at(0, 0), "róg płótna ma kolor tła")
        assertEquals(PhotoFix.otsu(strip()), PhotoFix.otsu(strip()))
    }
}

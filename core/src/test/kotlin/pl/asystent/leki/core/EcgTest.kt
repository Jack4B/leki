package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Wszystkie sygnały są syntetyczne (zmyślone). */
class EcgTest {
    private val fs = 125.0

    private fun g(t: Double, c: Double, a: Double, s: Double) = a * exp(-((t - c) * (t - c)) / (2 * s * s))

    /** Syntetyczne EKG: P, QRS (Q, R, S), T w zadanych momentach uderzeń, z dryfem linii i szumem. */
    private fun ecg(beatTimes: List<Double>, seconds: Double = 30.0, rAmp: Double = 1.2, wide: Set<Int> = emptySet(), noise: Double = 0.01, extra: (Double) -> Double = { 0.0 }): DoubleArray {
        val n = (seconds * fs).toInt()
        val rnd = java.util.Random(7)
        return DoubleArray(n) { i ->
            val t = i / fs
            var v = 0.2 * sin(2 * PI * 0.3 * t) + noise * rnd.nextGaussian() + extra(t)
            for ((k, b) in beatTimes.withIndex()) {
                if (abs(t - b) > 0.6) continue
                v += if (k in wide) g(t, b, 1.5, 0.028) + g(t, b + 0.30, -0.45, 0.06)   // szerokie uderzenie bez P, z odwróconym T (jak komorowe)
                else g(t, b - 0.16, 0.15, 0.025) + g(t, b - 0.025, -0.15, 0.010) + g(t, b, rAmp, 0.012) + g(t, b + 0.027, -0.25, 0.010) + g(t, b + 0.28, 0.3, 0.045)
            }
            v
        }
    }

    private fun regular(rr: Double, seconds: Double = 30.0): List<Double> = generateSequence(0.5) { it + rr }.takeWhile { it < seconds - 0.4 }.toList()
    private fun fromRr(rrs: List<Double>): List<Double> { var t = 0.5; val out = arrayListOf(t); for (r in rrs) { t += r; out.add(t) }; return out.filter { it < 29.6 } }
    private fun codes(r: EcgResult) = r.flags.map { it.code }

    @Test fun regularRhythmHasNoFlags() {
        val r = EcgAnalysis.analyze(ecg(regular(60.0 / 72)), fs)
        assertTrue(abs(r.hrMedian!! - 72) < 2, "tętno ${r.hrMedian}")
        assertTrue(r.beats.size in 34..37)
        assertEquals(listOf("none"), codes(r))
        assertEquals("dobra", r.quality)
        assertTrue(r.cv!! < 0.03 && r.rmssdMs!! < 30)
    }

    @Test fun irregularRhythmIsFlagged() {
        val rnd = java.util.Random(3)
        val rrs = List(60) { 0.45 + rnd.nextDouble() * 0.7 }
        val r = EcgAnalysis.analyze(ecg(fromRr(rrs)), fs)
        assertTrue("irregular" in codes(r) || "af_pattern" in codes(r), codes(r).toString())
    }

    @Test fun singlePrematureBeatIsACandidate() {
        val rrs = MutableList(40) { 0.8 }
        rrs[12] = 0.5; rrs[13] = 1.1
        val r = EcgAnalysis.analyze(ecg(fromRr(rrs)), fs)
        assertEquals(1, r.prematureAt.size)
        assertTrue("premature" in codes(r)); assertTrue("irregular" !in codes(r))
    }

    @Test fun slowAndFastRates() {
        assertTrue("brady" in codes(EcgAnalysis.analyze(ecg(regular(60.0 / 44)), fs)))
        assertTrue("tachy" in codes(EcgAnalysis.analyze(ecg(regular(60.0 / 118)), fs)))
    }

    @Test fun longPauseIsFlagged() {
        val rrs = MutableList(40) { 0.8 }
        rrs[10] = 2.6
        val r = EcgAnalysis.analyze(ecg(fromRr(rrs)), fs)
        assertTrue("pause" in codes(r), codes(r).toString())
    }

    @Test fun motionArtifactIsExcludedNotCountedAsBeats() {
        val beats = regular(0.8)
        val clean = EcgAnalysis.analyze(ecg(beats), fs)
        val noisy = EcgAnalysis.analyze(ecg(beats) { t -> if (t in 12.0..12.25) -2.6 * sin((t - 12.0) / 0.25 * PI) else 0.0 }, fs)
        assertTrue(noisy.artifacts.any { 12.1 in it })
        assertTrue(noisy.rr.any { it == null })
        assertTrue(abs(noisy.hrMedian!! - clean.hrMedian!!) < 2)
        assertTrue("irregular" !in codes(noisy) && "premature" !in codes(noisy), codes(noisy).toString())
    }

    @Test fun flatOrShortSignalGivesPoorQualityNotACrash() {
        assertTrue("poor" in codes(EcgAnalysis.analyze(DoubleArray(125 * 30), fs)))
        assertTrue("poor" in codes(EcgAnalysis.analyze(DoubleArray(100), fs)))
        assertTrue("poor" in codes(EcgAnalysis.analyze(ecg(listOf(1.0, 2.0), 30.0), fs)))
    }

    @Test fun recordRoundTripKeepsRate() {
        val mv = ecg(regular(0.8))
        val cmv = EcgAnalysis.toRecord(mv, fs, 100)
        assertEquals(3000, cmv.size)
        val e = Ecg("x", LocalDate.of(2026, 1, 1), LocalTime.of(8, 0), fs = 100, cmv = cmv)
        assertEquals(30.0, e.durationS)
        assertTrue(abs(EcgAnalysis.analyze(e).hrMedian!! - 75) < 2)
    }

    // ---------- odtwarzanie przebiegu z obrazu ----------

    private class Img(val w: Int, val h: Int) {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        fun set(x: Int, y: Int, c: Int) { if (x in 0 until w && y in 0 until h) px[y * w + x] = c }
        fun line(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            val n = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
            for (i in 0..n) { val x = x0 + (x1 - x0) * i / n; val y = y0 + (y1 - y0) * i / n; set(x, y, c); set(x + 1, y, c); set(x, y + 1, c) }
        }
    }

    private val red = 0xFFE03040.toInt()

    private fun render(mv: DoubleArray, pxPerMm: Double, vertical: Boolean): Img {
        val perStrip = (10 * fs).toInt()
        val len = (10 * 25 * pxPerMm).toInt()
        val band = (4 * 10 * pxPerMm).toInt()            // 4 mV na pas
        val img = if (vertical) Img(3 * band + 40, len + 40) else Img(len + 40, 3 * band + 40)
        val grid = 0xFFBBBBBB.toInt()
        for (x in 0 until img.w step (5 * pxPerMm).toInt()) for (y in 0 until img.h) img.set(x, y, grid)
        for (y in 0 until img.h step (5 * pxPerMm).toInt()) for (x in 0 until img.w) img.set(x, y, grid)
        for (k in 0 until 3) {
            var px0 = 0; var py0 = 0
            for (i in 0 until perStrip) {
                val v = mv[k * perStrip + i]
                val u = 20 + (i / fs * 25 * pxPerMm).toInt()
                val x: Int; val y: Int
                if (vertical) { x = (img.w - 20 - (k + 0.5) * band + v * 10 * pxPerMm).toInt(); y = u }
                else { x = u; y = (20 + (k + 0.5) * band - v * 10 * pxPerMm).toInt() }
                if (i > 0) img.line(px0, py0, x, y, red)
                px0 = x; py0 = y
            }
        }
        return img
    }

    @Test fun digitizesVerticalAndHorizontalPrintouts() {
        val mv = ecg(regular(0.8))
        for (vertical in listOf(true, false)) {
            val img = render(mv, 6.0, vertical)
            val d = assertNotNull(EcgDigitizer.digitize(img.px, img.w, img.h, 6.0))
            assertEquals(3, d.strips)
            val r = EcgAnalysis.analyze(d.mv, d.fs)
            assertTrue(abs(r.hrMedian!! - 75) < 2.5, "tętno ${r.hrMedian} (pion=$vertical)")
            val amp = r.beats.map { it.amplitudeMv }.sorted()[r.beats.size / 2]
            assertTrue(abs(amp - 1.2) < 0.25, "amplituda $amp")
            assertTrue(abs(d.mv.size / d.fs - 30.0) < 0.5)
        }
    }

    @Test fun imageWithoutTraceIsRejected() {
        val img = Img(300, 300)
        assertEquals(null, EcgDigitizer.digitize(img.px, img.w, img.h, 6.0))
    }

    @Test fun metaFromWatchPrintout() {
        val m = EcgMeta.parse(listOf("Imię i nazwisko:Jan Testowy", "Rytm zatokowy", "Ten zapis EKG nie wykazuje wyraźnych oznak arytmii.", "Średnie tętno:73ud./min", "Godzina 2026-10-13 19:08"))
        assertEquals(73, m.hr); assertEquals(LocalDate.of(2026, 10, 13), m.date); assertEquals(LocalTime.of(19, 8), m.time)
        assertEquals("Rytm zatokowy", m.note)
        assertEquals(0, EcgMeta.parse(listOf("nic")).hr)
    }

    // ---------- zapis, kopia zapasowa, raport ----------

    private fun sampleEcg(id: String = "e1", date: LocalDate = LocalDate.of(2026, 10, 13)): Ecg =
        Ecg(id, date, LocalTime.of(19, 8), watchHr = 73, watchNote = "Rytm zatokowy", fs = 100, cmv = EcgAnalysis.toRecord(ecg(regular(0.8)), fs, 100), note = "po spacerze")

    @Test fun ecgSurvivesBackupAndEncryptedBackup() {
        val s = AppState().saveEcg(sampleEcg())
        val back = Backup.parseState(Backup.export(s))
        assertEquals(s.ecgs, back.ecgs)
        val enc = BackupCrypto.encrypt("haslo-12345", Backup.export(s), iter = 100_000)
        assertEquals(s.ecgs, Backup.parseState(BackupCrypto.decrypt("haslo-12345", Backup.parseEnvelope(enc))).ecgs)
        assertTrue(back.deleteEcg("e1").ecgs.isEmpty())
        // stara kopia bez pola ecgs
        assertTrue(Fixtures.state.ecgs.isEmpty())
    }

    @Test fun corruptEcgEntriesAreDropped() {
        val json = """{"v":3,"days":{},"ecgs":[
            {"id":"a","date":"2026-10-13","time":"19:08","fs":100,"cmv":[1,2,3]},
            {"id":"b","date":"2026-10-13","time":"19:08","fs":9,"cmv":[]},
            {"id":"c","date":"2026-10-13","time":"19:08","fs":100,"cmv":[${(0 until 300).joinToString(",") { "99999" }}]}]}"""
        val st = Backup.parseState(json)
        assertEquals(listOf("c"), st.ecgs.map { it.id })
        assertTrue(st.ecgs.single().cmv.all { it == 1000 })
    }

    @Test fun doctorReportShowsEcgSectionOnlyWhenThereIsAnEcg() {
        val base = Fixtures.state
        val plain = DoctorReport.html(base, Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertTrue("EKG z zegarka" !in plain)
        val s = base.saveEcg(sampleEcg(date = Fixtures.today.minusDays(3)))
        val html = DoctorReport.html(s, Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertTrue("EKG z zegarka" in html && "Rytm zatokowy" in html && "<polyline" in html && "tętno wg zegarka: <b>73/min</b>" in html)
        assertTrue("bez oceny odstępu QT" in html)
        val off = s.copy(settings = s.settings.copy(rp = s.settings.rp.copy(ecg = false)))
        assertTrue("EKG z zegarka" !in DoctorReport.html(off, Fixtures.period(30), Fixtures.today, Fixtures.now))
        // zapis spoza okresu nie trafia do raportu
        val old = base.saveEcg(sampleEcg(date = Fixtures.today.minusDays(200)))
        assertTrue("EKG z zegarka" !in DoctorReport.html(old, Fixtures.period(30), Fixtures.today, Fixtures.now))
    }

    @Test fun userTextInEcgReportIsEscaped() {
        val e = sampleEcg(date = Fixtures.today).copy(watchNote = "<script>x</script>", note = "<img onerror=1>")
        val html = DoctorReport.html(Fixtures.state.saveEcg(e), Fixtures.period(7), Fixtures.today, Fixtures.now)
        assertTrue("<script>x" !in html && "<img onerror" !in html)
    }

    @Test fun metaNoteIsCleanedOfMergedHeaderColumns() {
        assertEquals("Rytm zatokowy", EcgMeta.cleanNote("Rytm zatokowy  Średnie tętno:73ud./min"))
        assertEquals("Rytm zatokowy", EcgMeta.cleanNote("Rytm zatokowy Godzina 2026-10-13 19:08"))
        assertEquals("", EcgMeta.cleanNote("Średnie tętno:73"))
        val m = EcgMeta.parse(listOf("Rytm zatokowy  Średnie tętno:73ud./min", "Godzina 2026-10-13 19:08"))
        assertEquals("Rytm zatokowy", m.note); assertEquals(73, m.hr)
    }

    @Test fun aiReportIncludesEcgOnlyWhenPresentAndEnabled() {
        val plain = AiReport.build(Fixtures.state, Fixtures.period(30), Fixtures.today, Fixtures.now, "x")
        assertTrue("EKG z zegarka" !in plain)
        val s = Fixtures.state.saveEcg(sampleEcg(date = Fixtures.today.minusDays(2)))
        val t = AiReport.build(s, Fixtures.period(30), Fixtures.today, Fixtures.now, "x")
        assertTrue("## EKG z zegarka (jedno odprowadzenie)" in t)
        assertTrue("Tętno wg zegarka: 73/min." in t && "Ocena zegarka: Rytm zatokowy." in t)
        assertTrue("Kolejne wiarygodne odstępy RR (ms): " in t && "Metoda obliczeń:" in t && "Nie oceniaj odstępu QT" in t)
        assertTrue("5. **Powiązania z wynikami badań, ciśnieniem i EKG z zegarka:**" in t)
        assertTrue("Surowy przebieg EKG nie jest dołączony" in t)
        val off = s.copy(settings = s.settings.copy(ai = s.settings.ai.copy(ecg = false)))
        assertEquals(plain, AiReport.build(off, Fixtures.period(30), Fixtures.today, Fixtures.now, "x"))
        // zapis spoza okresu nie trafia do raportu
        val old = Fixtures.state.saveEcg(sampleEcg(date = Fixtures.today.minusDays(200)))
        assertEquals(plain, AiReport.build(old, Fixtures.period(30), Fixtures.today, Fixtures.now, "x"))
    }

    @Test fun aiEcgFlagRoundTripsAndDefaultsToOnForOldBackups() {
        assertTrue(Fixtures.state.settings.ai.ecg)
        val off = Fixtures.state.copy(settings = Fixtures.state.settings.copy(ai = Fixtures.state.settings.ai.copy(ecg = false)))
        assertEquals(false, Backup.parseState(Backup.export(off)).settings.ai.ecg)
    }

    @Test fun methodDescriptionMatchesTheCodeConstants() {
        val all = EcgInfo.STEPS.joinToString(" ") { it.text }
        // wartości z opisu muszą odpowiadać stałym w algorytmie (0,6 s, 60 ms, 40%, 250 ms, 0,35 s, 2,5, 0,9, 4x; progi 50 i 100)
        for (k in listOf("0,6 s", "60 ms", "25%", "250 ms", "±70 ms", "±0,35 s", "2,5", "0,9", "4 razy", "poniżej 50/min", "powyżej 100/min", "35%", "15%", "80%", "110%", "1,6 mediany", "1,8 s", "8 uderzeń", "6 wiarygodnych")) assertTrue(k in all, k)
    }

    // ---------- wzorzec losowej nieregularności, kształt uderzeń, jakość ----------

    @Test fun afLikeRandomRrIsFlaggedWithItsOwnMessage() {
        var hits = 0
        for (seed in 0 until 8) {
            val r2 = java.util.Random(seed + 100L)
            val rrs = List(60) { Math.exp(Math.log(0.7) + 0.22 * r2.nextGaussian()).coerceIn(0.35, 1.6) }
            val res = EcgAnalysis.analyze(ecg(fromRr(rrs)), fs)
            if (res.afPattern) { hits++; assertTrue("af_pattern" in codes(res) && "irregular" !in codes(res)) }
        }
        assertTrue(hits >= 6, "wykryto $hits z 8 (oczekiwane co najmniej 6)")
    }

    @Test fun regularPatternsAreNotCalledAf() {
        // bigeminia, trigeminia, zmienność oddechowa i pojedyncze przedwczesne uderzenie
        val bigeminy = fromRr(List(40) { if (it % 2 == 0) 0.55 else 1.05 })
        val trigeminy = fromRr(List(40) { listOf(0.8, 0.55, 1.05)[it % 3] })
        val rsa = fromRr(List(45) { 0.8 + 0.07 * sin(2 * PI * it * 0.8 / 4.5) + 0.01 * ((it * 7919) % 13 - 6) / 6.0 })
        for ((name, t) in listOf("bigeminia" to bigeminy, "trigeminia" to trigeminy, "oddech" to rsa)) {
            assertTrue(!EcgAnalysis.analyze(ecg(t), fs).afPattern, name)
        }
        val single = MutableList(40) { 0.8 }; single[12] = 0.5; single[13] = 1.1
        assertTrue(!EcgAnalysis.analyze(ecg(fromRr(single)), fs).afPattern)
    }

    @Test fun indicatorsBehaveAsDocumented() {
        assertEquals(1.0, EcgAnalysis.turningPointRatio(List(20) { if (it % 2 == 0) 0.5 else 1.0 }))
        assertEquals(0.0, EcgAnalysis.turningPointRatio(List(20) { it * 0.01 }))
        assertTrue(EcgAnalysis.sampleEntropy(List(40) { if (it % 2 == 0) 0.5 else 1.0 })!! < 0.3)
        val rnd = java.util.Random(5)
        assertTrue(EcgAnalysis.sampleEntropy(List(40) { 0.4 + rnd.nextDouble() })!! > 1.2)
        assertEquals(null, EcgAnalysis.turningPointRatio(listOf(1.0, 2.0)))
        assertEquals(0.0, EcgAnalysis.sampleEntropy(List(20) { 0.8 }))
    }

    @Test fun prematureBeatShapeIsDescribed() {
        val rrs = MutableList(40) { 0.8 }; rrs[12] = 0.5; rrs[13] = 1.1
        val times = fromRr(rrs)
        val narrow = EcgAnalysis.analyze(ecg(times), fs)
        assertTrue(narrow.prematureShapes.single().contains("zbliżony") && narrow.prematureShapes.single().contains("przedsionkowe"), narrow.prematureShapes.toString())
        val wide = EcgAnalysis.analyze(ecg(times, wide = setOf(13)), fs)
        assertTrue(wide.prematureShapes.single().contains("odmienny") && wide.prematureShapes.single().contains("komorowe"), wide.prematureShapes.toString())
        assertTrue(wide.flags.single { it.code == "premature" }.text.contains("komorowe"))
    }

    @Test fun qrsWidthAndShapeQualityAreReported() {
        val res = EcgAnalysis.analyze(ecg(regular(0.8)), fs)
        assertTrue(res.qrsWidthMs!! in 30.0..150.0, "szerokość ${res.qrsWidthMs}")
        assertTrue(res.shapeCorr!! > 0.9, "korelacja ${res.shapeCorr}")
        assertEquals("dobra", res.quality)
        // silny szum pogarsza korelację kształtu i jakość
        val noisy = EcgAnalysis.analyze(ecg(regular(0.8), noise = 0.45), fs)
        assertTrue(noisy.quality != "dobra", "jakość ${noisy.quality}, korelacja ${noisy.shapeCorr}")
    }

    @Test fun newMethodConstantsAreDescribed() {
        val all = EcgInfo.STEPS.joinToString(" ") { it.text }
        for (k in listOf("0,12", "od 55% do 82%", "r = 0,2", "co najmniej 1,0", "co najmniej 20 odstępów", "od -0,10 do +0,14 s", "poniżej 0,8 jakość umiarkowana", "poniżej 0,6 słaba", "15% maksymalnego", "niższa niż 0,75", "o ponad 30% (i 10 ms)")) assertTrue(k in all, k)
    }
}

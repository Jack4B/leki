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
    private fun ecg(beatTimes: List<Double>, seconds: Double = 30.0, rAmp: Double = 1.2, wide: Set<Int> = emptySet(), noise: Double = 0.01, rate: Double = fs, extra: (Double) -> Double = { 0.0 }): DoubleArray {
        val n = (seconds * rate).toInt()
        val rnd = java.util.Random(7)
        return DoubleArray(n) { i ->
            val t = i / rate
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
        Ecg(id, date, LocalTime.of(19, 8), watchHr = 73, watchNote = "Rytm zatokowy", fs = 100, cmv = EcgVector.normalize(EcgAnalysis.toRecord(ecg(regular(0.8)), fs, 100)), note = "po spacerze")

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
        val oldHtml = DoctorReport.html(old, Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertTrue("Brak zapisów EKG w wybranym okresie" in oldHtml, oldHtml.takeLast(400)); assertTrue("Rytm zatokowy" !in oldHtml)
        // więcej niż 6 zapisów w okresie: raport mówi, że pokazano najnowsze
        var many = base
        for (d in 1..8) many = many.saveEcg(sampleEcg(date = Fixtures.today.minusDays(d.toLong())).copy(id = "e$d"))
        val manyHtml = DoctorReport.html(many, Fixtures.period(30), Fixtures.today, Fixtures.now)
        assertTrue("Pokazano 6 najnowszych z 8" in manyHtml, "ecgs=${many.ecgs.size} " + manyHtml.takeLast(300))
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

    @Test fun ecgPackingIsLosslessAndCompact() {
        val cmv = EcgAnalysis.toRecord(ecg(regular(0.8)), fs, 125)
        val packed = EcgPacking.pack(cmv)
        assertEquals(cmv, EcgPacking.unpack(packed))
        assertTrue(packed.length < cmv.size * 3, "zapis ${packed.length} znaków dla ${cmv.size} próbek")
        assertEquals(listOf(0, 1000, -1000, 5), EcgPacking.unpack(EcgPacking.pack(listOf(0, 1000, -1000, 5))))
        assertEquals(null, EcgPacking.unpack("%%%nie-base64%%%"))
        assertEquals(null, EcgPacking.unpack(java.util.Base64.getEncoder().encodeToString(ByteArray(5) { 0xFF.toByte() })))
        assertEquals(emptyList(), EcgPacking.unpack(""))
        val json = Backup.export(AppState().saveEcg(sampleEcg()))
        assertTrue(json.length < 20_000, "kopia z jednym EKG ma ${json.length} znaków")
        assertTrue("\"cmvp\"" in json && "\"cmv\"" !in json && "\"cmvz\"" !in json)
    }

    @Test fun legacyListFormatStillLoads() {
        val e = sampleEcg()
        val legacy = """{"v":3,"days":{},"ecgs":[{"id":"old","date":"2026-10-13","time":"19:08","source":"zegarek","watchHr":73,"watchNote":"Rytm zatokowy","fs":100,"note":"","cmv":[${e.cmv.joinToString(",")}]}]}"""
        val st = Backup.parseState(legacy)
        assertEquals(e.cmv, st.ecgs.single().cmv)
        // uszkodzony zapis zwarty jest pomijany, reszta kopii się wczytuje
        val bad = """{"v":3,"days":{},"ecgs":[{"id":"x","date":"2026-10-13","time":"19:08","fs":100,"cmvz":"!!!"}]}"""
        assertTrue(Backup.parseState(bad).ecgs.isEmpty())
    }

    @Test fun digitizesAtThreeHundredDpiScaleInReasonableTime() {
        val mv = ecg(regular(0.8))
        val img = render(mv, 300 / 25.4, true)
        val t0 = System.nanoTime()
        val d = assertNotNull(EcgDigitizer.digitize(img.px, img.w, img.h, 300 / 25.4))
        val ms = (System.nanoTime() - t0) / 1_000_000
        val r = EcgAnalysis.analyze(d.mv, d.fs)
        assertTrue(abs(r.hrMedian!! - 75) < 2.5, "tętno ${r.hrMedian}")
        assertTrue(abs(d.fs - 295.3) < 1.0, "fs ${d.fs}")
        assertTrue(ms < 15_000, "digitalizacja trwała $ms ms")
    }

    @Test fun licenseNoticesAreCompleteAndConsistent() {
        for (n in Licenses.included + Licenses.evaluated) {
            assertTrue(n.name.isNotBlank() && n.license.isNotBlank() && n.role.isNotBlank() && n.copyright.isNotBlank() && n.url.startsWith("https://"), n.name)
        }
        assertTrue(Licenses.included.any { it.name.startsWith("Phosphor") && it.license == "MIT" && "2023" in it.copyright })
        assertTrue(Licenses.included.none { "HeartKit" in it.name }, "HeartKit nie jest dołączony do aplikacji")
        val hk = Licenses.evaluated.single { "HeartKit" in it.name }
        assertEquals("BSD-3-Clause", hk.license); assertTrue("Ambiq" in hk.copyright)
        assertTrue(Licenses.references.size >= 3)
    }

    @Test fun vectorStorageKeepsErrorBelowToleranceAndIsMuchSmaller() {
        val cmv = EcgAnalysis.toRecord(ecg(regular(0.8), noise = 0.004), 250.0, 250)
        val packed = EcgVector.pack(cmv)
        val back = EcgVector.unpack(packed)!!
        assertEquals(cmv.size, back.size)
        val maxErr = cmv.indices.maxOf { abs(cmv[it] - back[it]) }
        assertTrue(maxErr <= EcgVector.EPS, "błąd $maxErr")
        assertTrue(packed.length < EcgPacking.pack(cmv).length, "wektor ${packed.length} B, różnice ${EcgPacking.pack(cmv).length} B")
        // szczyty R zostają zachowane (są wierzchołkami)
        val peak = cmv.indices.maxByOrNull { cmv[it] }!!
        assertTrue(abs(back[peak] - cmv[peak]) <= EcgVector.EPS)
        // wynik analizy po zapisie jest taki sam
        val a = EcgAnalysis.analyze(Ecg("a", LocalDate.of(2026, 1, 1), LocalTime.of(8, 0), fs = 250, cmv = cmv))
        val b = EcgAnalysis.analyze(Ecg("b", LocalDate.of(2026, 1, 1), LocalTime.of(8, 0), fs = 250, cmv = back))
        assertEquals(a.beats.size, b.beats.size); assertTrue(abs(a.hrMedian!! - b.hrMedian!!) < 0.1)
    }

    @Test fun repeatedSavingDoesNotDrift() {
        for (noise in listOf(0.004, 0.02, 0.05)) {
            val raw = EcgAnalysis.toRecord(ecg(regular(0.8), noise = noise), 250.0, 250)
            val first = EcgVector.normalize(raw)
            var cur = first
            repeat(6) { cur = EcgVector.unpack(EcgVector.pack(cur))!! }
            assertEquals(first, cur, "wielokrotny zapis i odczyt nie może zmieniać przebiegu (szum $noise)")
            val err = raw.indices.maxOf { abs(raw[it] - first[it]) }
            assertTrue(err <= 2 * EcgVector.EPS, "błąd po normalizacji $err")
        }
    }

    @Test fun corruptVectorDataIsRejected() {
        assertEquals(null, EcgVector.unpack("%%%"))
        assertEquals(null, EcgVector.unpack(java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 0))))        // brak punktów
        assertEquals(null, EcgVector.unpack(java.util.Base64.getEncoder().encodeToString(byteArrayOf(5, 2, 1, 2))))  // pierwszy punkt nie na początku
        assertEquals(emptyList(), EcgVector.unpack(EcgVector.pack(emptyList())))
        assertEquals(listOf(7), EcgVector.unpack(EcgVector.pack(listOf(7))))
        assertEquals(listOf(1, 2), EcgVector.unpack(EcgVector.pack(listOf(1, 2))))
    }

    @Test fun maskDigitizerMatchesPixelDigitizer() {
        val img = render(ecg(regular(0.8)), 6.0, true)
        val a = EcgDigitizer.digitize(img.px, img.w, img.h, 6.0)!!
        val b = EcgDigitizer.digitizeMask(EcgDigitizer.mask(img.px, img.w, img.h), img.w, img.h, 6.0)!!
        assertEquals(a.mv.toList(), b.mv.toList())
    }

    @Test fun eventsHaveTimesAndDescriptions() {
        val rrs = MutableList(40) { 0.8 }; rrs[12] = 0.5; rrs[13] = 1.1
        val times = fromRr(rrs)
        val r = EcgAnalysis.analyze(ecg(times), fs)
        val pre = r.events.single { it.code == "premature" }
        assertTrue(abs(pre.tSec - times[13]) < 0.05, "czas ${pre.tSec}")
        assertTrue(pre.title.contains("Przedwczesne uderzenie") && pre.text.contains("zamiast typowego") && pre.text.contains("po nim przerwa"), pre.text)
        // pauza
        val p2 = MutableList(40) { 0.8 }; p2[10] = 2.6
        val pr = EcgAnalysis.analyze(ecg(fromRr(p2)), fs)
        val pause = pr.events.single { it.code == "pause" }
        assertTrue(pause.endSec!! - pause.tSec in 2.4..2.8 && pause.severity == EcgSeverity.Attention)
        // zakłócenie
        val noisy = EcgAnalysis.analyze(ecg(regular(0.8)) { t -> if (t in 12.0..12.25) -2.6 * sin((t - 12.0) / 0.25 * PI) else 0.0 }, fs)
        val art = noisy.events.single { it.code == "artifact" }
        assertTrue(12.1 in art.tSec..art.endSec!! && art.text.contains("pominięty"))
        // czysty zapis: brak zdarzeń; zdarzenia są posortowane
        assertTrue(EcgAnalysis.analyze(ecg(regular(0.8)), fs).events.isEmpty())
        val both = EcgAnalysis.analyze(ecg(times) { t -> if (t in 3.0..3.25) -2.6 * sin((t - 3.0) / 0.25 * PI) else 0.0 }, fs)
        assertEquals(both.events.sortedBy { it.tSec }, both.events)
    }

    @Test fun standaloneEcgDocumentIsATrueScaleLandscapePage() {
        val rrs = MutableList(40) { 0.8 }; rrs[12] = 0.5; rrs[13] = 1.1
        val e = Ecg("p", LocalDate.of(2026, 10, 13), LocalTime.of(19, 8), watchHr = 73, watchNote = "Rytm <b>zatokowy</b>", fs = 250,
            cmv = EcgVector.normalize(EcgAnalysis.toRecord(ecg(fromRr(rrs)), fs, 250)), note = "po spacerze")
        val st = AppState(settings = Settings(), patient = "Jan <Testowy>")
        val html = EcgReport.document(st, e, Fixtures.today, Fixtures.now)
        assertTrue("size:A4 landscape" in html && "width:257.5mm" in html, "skala papieru")
        assertTrue("25 mm/s i 10 mm/mV" in html && "Metoda:" in html && "nie jest badaniem EKG ani diagnozą" !in html && "to nie jest badanie EKG ani diagnoza" in html)
        assertTrue("Przedwczesne uderzenie" in html && "zamiast typowego" in html && "<polyline" in html)
        assertTrue("Jan &lt;Testowy&gt;" in html && "Rytm &lt;b&gt;zatokowy" in html && "<b>zatokowy</b>" !in html)
        assertTrue("tętno wg zegarka: <b>73/min</b>" in html && "Notatka pacjenta: po spacerze" in html)
        // sekcja w raporcie dla lekarza ma te same zdarzenia, ale bez skali papieru
        val sec = EcgReport.html(listOf(e))
        assertTrue("zamiast typowego" in sec && "width:257.5mm" !in sec)
    }

    // ---------- okno podglądu: przejście do zdarzenia musi trafiać w zdarzenie ----------

    @Test fun viewportJumpsToTheEvent() {
        val total = 29.6f
        val pre = EcgEvent(7.7, null, "premature", "t", "x", EcgSeverity.Info)
        val w = EcgViewport.forEvent(pre, total)
        assertEquals(4f, w.visible); assertEquals(5.7f, w.start, 0.001f)
        assertTrue(w.contains(7.7) && abs((w.start + w.visible / 2) - 7.7f) < 0.01f)
        val art = EcgEvent(25.3, 26.5, "artifact", "t", "x", EcgSeverity.Info)
        val wa = EcgViewport.forEvent(art, total)
        assertTrue(wa.contains(25.3) && wa.contains(26.5) && wa.end <= total + 0.001f)
        // zdarzenia przy brzegach zapisu: okno nie wychodzi poza zapis, a zdarzenie jest w oknie
        for (t in listOf(0.0, 0.2, 29.4, 29.6)) {
            val ww = EcgViewport.forEvent(EcgEvent(t, null, "premature", "t", "x", EcgSeverity.Info), total)
            assertTrue(ww.start >= 0f && ww.end <= total + 0.001f && ww.contains(t), "t=$t okno ${ww.start}..${ww.end}")
        }
        // wszystkie zdarzenia prawdziwej analizy są widoczne po przejściu do nich
        val r = EcgAnalysis.analyze(ecg(regular(0.8)) { t -> if (t in 12.0..12.25) -2.6 * sin((t - 12.0) / 0.25 * PI) else 0.0 }, fs)
        for (ev in r.events) assertTrue(EcgViewport.forEvent(ev, 30f).contains(ev.tSec) && EcgViewport.forEvent(ev, 30f).contains(ev.endSec ?: ev.tSec))
    }

    @Test fun viewportZoomKeepsTheSecondUnderTheFinger() {
        val total = 30f
        val w = EcgViewport.Window(10f, 10f)
        val z = EcgViewport.zoom(w, 2f, 0.25f, total)
        assertEquals(5f, z.visible)
        assertEquals(w.start + 0.25f * w.visible, z.start + 0.25f * z.visible, 0.001f)
        // zmniejszenie poza zapis jest ograniczone, a kolejne przybliżenia nie schodzą poniżej 2 s
        val out = EcgViewport.zoom(w, 0.1f, 0.5f, total)
        assertEquals(total, out.visible); assertEquals(0f, out.start)
        assertEquals(2f, EcgViewport.zoom(w, 100f, 0.5f, total).visible)
        // niepoprawny współczynnik nie psuje okna
        assertEquals(w, EcgViewport.zoom(w, Float.NaN, 0.5f, total))
        assertEquals(w, EcgViewport.zoom(w, 0f, 0.5f, total))
    }

    @Test fun viewportPanAndClampStayInsideTheRecord() {
        val total = 30f
        assertEquals(0f, EcgViewport.pan(EcgViewport.Window(1f, 10f), -5f, total).start)
        assertEquals(20f, EcgViewport.pan(EcgViewport.Window(19f, 10f), 5f, total).start)
        assertEquals(12f, EcgViewport.pan(EcgViewport.Window(10f, 10f), 0.2f, total).start, 0.001f)
        assertEquals(EcgViewport.Window(0f, 3f), EcgViewport.clamp(EcgViewport.Window(-5f, 3f), total))
        // krótki zapis: okno nigdy nie jest dłuższe niż zapis
        val s = EcgViewport.clamp(EcgViewport.Window(3f, 10f), 2.5f)
        assertEquals(2.5f, s.visible); assertEquals(0f, s.start)
        assertEquals(EcgViewport.Window(0f, 30f), EcgViewport.whole(30f))
        assertEquals(10f, EcgViewport.centerOn(15f, 10f, 30f).start)
    }

    // ---------- poprawki z przeglądu ----------

    @Test fun pausesLongerThanThreeSecondsAreReported() {
        for (gap in listOf(3.2, 4.5)) {
            val before = generateSequence(0.5) { it + 0.8 }.takeWhile { it < 12.0 }.toList()
            val after = generateSequence(before.last() + gap) { it + 0.8 }.takeWhile { it < 29.4 }.toList()
            val r = EcgAnalysis.analyze(ecg(before + after), fs)
            assertTrue("pause" in codes(r), "przerwa $gap s: ${codes(r)}")
            assertTrue("none" !in codes(r))
            val ev = r.events.first { it.code == "pause" }
            assertTrue(abs((ev.endSec!! - ev.tSec) - gap) < 0.1, "czas przerwy ${ev.endSec!! - ev.tSec}")
            // statystyki nie są zaburzone przerwą
            assertTrue(abs(r.hrMedian!! - 75) < 2.5)
        }
    }

    @Test fun hugeSameDirectionBeatIsABeatButAnOppositeSpikeStaysAnArtifact() {
        val rrs = MutableList(40) { 0.8 }
        rrs[12] = 0.5; rrs[13] = 1.1
        val times = fromRr(rrs)
        val tp = times[13]
        // duże uderzenie tego samego kierunku: uderzenie do oceny, nie zakłócenie
        val big = EcgAnalysis.analyze(ecg(times) { t -> g(t, tp, 2.0, 0.03) }, fs)
        assertTrue("premature" in codes(big), codes(big).toString())
        assertTrue(big.events.none { it.code == "artifact" && tp >= it.tSec - 0.1 && tp <= (it.endSec ?: it.tSec) + 0.1 })
        assertTrue(big.beats.any { abs(it.tSec - tp) < 0.06 })
        // głęboki skok w przeciwną stronę (ruch, odklejenie) nie jest uderzeniem: zostaje zakłóceniem i nie tworzy fałszywych uderzeń
        val spike = EcgAnalysis.analyze(ecg(regular(0.8)) { t -> g(t, 12.3, -4.2, 0.03) }, fs)
        assertTrue(spike.events.any { it.code == "artifact" && 12.3 in it.tSec..it.endSec!! })
        assertTrue(spike.beats.none { abs(it.tSec - 12.3) < 0.1 })
    }

    @Test fun analysisWorksAtTheStoredRate() {
        val rate = 250.0
        val rrs = MutableList(40) { 0.8 }
        rrs[12] = 0.5; rrs[13] = 1.1
        val r = EcgAnalysis.analyze(ecg(fromRr(rrs), rate = rate), rate)
        assertEquals(1, r.prematureAt.size)
        assertTrue(abs(r.hrMedian!! - 75) < 2.5, "tętno ${r.hrMedian}")
        val w = r.qrsWidthMs!!
        assertTrue(w in 20.0..120.0, "QRS $w ms")
        val reg = EcgAnalysis.analyze(ecg(regular(0.8), rate = rate), rate)
        assertEquals(listOf("none"), codes(reg))
    }

    @Test fun corruptedVectorRecordIsRejectedNotThrown() {
        fun varint(v: Long): List<Byte> { var x = v; val o = ArrayList<Byte>(); while (x >= 0x80) { o.add(((x and 0x7F) or 0x80).toByte()); x = x shr 7 }; o.add(x.toByte()); return o }
        val bytes = (varint(100) + varint(3) + varint(0) + varint(0) + varint(5) + varint(0) + varint(2147483647L) + varint(0)).toByteArray()
        assertEquals(null, EcgVector.unpack(java.util.Base64.getEncoder().encodeToString(bytes)))
        // w kopii takie EKG jest pomijane, a reszta danych się wczytuje
        val json = """{"v":3,"days":{},"ecgs":[{"id":"e1","date":"2025-03-01","time":"08:00","fs":250,"cmvp":"${java.util.Base64.getEncoder().encodeToString(bytes)}"}]}"""
        assertTrue(Backup.parseState(json).ecgs.isEmpty())
    }

    @Test fun tinyRedMarksAreNotTreatedAsAStrip() {
        val w = 1000; val h = 600
        val m = ByteArray(w * h)
        for (x in 50 until 950) { val y = 300 + (20 * sin(x / 15.0)).toInt(); m[y * w + x] = 1; m[(y + 1) * w + x] = 1 }
        for (y in 50 until 65) for (x in 500 until 515) m[y * w + x] = 1        // ikona: 15 px (2,5 mm) nad przebiegiem
        val d = assertNotNull(EcgDigitizer.digitizeMask(m, w, h, 6.0))
        assertEquals(1, d.strips)
        assertTrue(abs(d.mv.size - 900) <= 2, "próbek ${d.mv.size}")
    }
}

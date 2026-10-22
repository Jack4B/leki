package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Zapis EKG z zegarka (jedno odprowadzenie, zwykle 30 s). Przechowujemy sam przebieg w mV (próbkowanie [fs] Hz, wartości w setnych mV),
 * a wszystkie wskaźniki liczymy na żądanie, więc ulepszenia algorytmu działają też wstecz. Imię, wzrost, wagę i inne dane z wydruku
 * zegarka celowo pomijamy.
 */
data class Ecg(
    val id: String,
    val date: LocalDate,
    val time: LocalTime,
    val source: String = "zegarek",
    /** Średnie tętno podane przez zegarek (0 = brak). */
    val watchHr: Int = 0,
    /** Ocena rytmu podana przez zegarek, np. „Rytm zatokowy”. */
    val watchNote: String = "",
    val fs: Int = 250,
    /** Przebieg w setnych częściach mV. */
    val cmv: List<Int> = emptyList(),
    val note: String = "",
) {
    val durationS: Double get() = if (fs > 0) cmv.size.toDouble() / fs else 0.0
    fun signalMv(): DoubleArray = DoubleArray(cmv.size) { cmv[it] / 100.0 }
}

enum class EcgSeverity { Info, Attention }

data class EcgFlag(val code: String, val title: String, val text: String, val severity: EcgSeverity)

/** Zdarzenie w zapisie z położeniem w czasie: do pokazania na wykresie i wylistowania pod nim. [endSec] ma tylko zdarzenie rozciągnięte w czasie (zakłócenie, przerwa). */
data class EcgEvent(val tSec: Double, val endSec: Double?, val code: String, val title: String, val text: String, val severity: EcgSeverity)

data class EcgBeat(val tSec: Double, val amplitudeMv: Double)

data class EcgResult(
    val durationS: Double,
    val beats: List<EcgBeat>,
    /** Odstępy RR w sekundach, tylko wiarygodne (bez przerw na artefakty); indeks i to odstęp między uderzeniami i oraz i+1 z listy [beats]. */
    val rr: List<Double?>,
    val hrMean: Double?, val hrMedian: Double?, val hrMin: Double?, val hrMax: Double?,
    val sdnnMs: Double?, val rmssdMs: Double?, val pnn50: Double?, val cv: Double?,
    val artifactRatio: Double,
    val artifacts: List<ClosedFloatingPointRange<Double>>,
    val flags: List<EcgFlag>,
    val prematureAt: List<Double>,
    /** Znormalizowane RMSSD (RMSSD / średni RR), odsetek punktów zwrotnych w ciągu RR i entropia próbkowa RR: wskaźniki losowej nieregularności. */
    val nRmssd: Double? = null, val tpr: Double? = null, val sampEn: Double? = null,
    /** Odstępy RR układają się w wzorzec losowej nieregularności typowy dla migotania przedsionków (heurystyka, nie rozpoznanie). */
    val afPattern: Boolean = false,
    /** Szerokość QRS typowego uderzenia (orientacyjnie, rozdzielczość jednej próbki: 4 ms przy 250 Hz) i mediana korelacji kształtu uderzeń z szablonem. */
    val qrsWidthMs: Double? = null, val shapeCorr: Double? = null,
    /** Opis kształtu każdego przedwczesnego uderzenia (równolegle do [prematureAt]). */
    val prematureShapes: List<String> = emptyList(),
    /** Zdarzenia z położeniem w czasie (przedwczesne uderzenia, przerwy, zakłócenia), posortowane. */
    val events: List<EcgEvent> = emptyList(),
) {
    val validRr: List<Double> get() = rr.filterNotNull()
    val quality: String get() = when {
        beats.size < 8 || artifactRatio > 0.35 || (shapeCorr != null && shapeCorr < 0.6) -> "słaba"
        artifactRatio > 0.1 || (shapeCorr != null && shapeCorr < 0.8) -> "umiarkowana"
        else -> "dobra"
    }
}

/**
 * Prosta analiza rytmu z jednego odprowadzenia: wykrywanie załamków R, odstępy RR, tętno, zmienność i kandydaci na nieprawidłowości.
 * To narzędzie pomocnicze do rozmowy z lekarzem, a nie diagnoza: nie mierzy odstępu QT ani przesunięć ST i nie rozpoznaje niedokrwienia.
 */
object EcgAnalysis {
    private fun pl2(v: Double) = "%.2f".format(java.util.Locale.ROOT, v).replace('.', ',')

    /** Odsetek punktów zwrotnych w ciągu (losowy ciąg ma około 2/3, gładka zmienność oddechowa znacznie mniej, bigeminia 1). */
    fun turningPointRatio(x: List<Double>): Double? {
        if (x.size < 5) return null
        var tp = 0
        for (i in 1 until x.size - 1) if ((x[i] - x[i - 1]) * (x[i] - x[i + 1]) > 0) tp++
        return tp.toDouble() / (x.size - 2)
    }

    /** Entropia próbkowa (Richman i Moorman 2000) z m = 1 i r = 0,2 odchylenia standardowego: niska dla regularnych wzorców (np. bigeminia), wysoka dla losowych. */
    fun sampleEntropy(x: List<Double>): Double? {
        val n = x.size
        if (n < 10) return null
        val mean = x.average()
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / n)
        if (sd < 1e-9) return 0.0
        val r = 0.2 * sd
        var b = 0L; var a = 0L
        for (i in 0 until n - 1) for (j in i + 1 until n - 1) {
            if (abs(x[i] - x[j]) <= r) { b++; if (abs(x[i + 1] - x[j + 1]) <= r) a++ }
        }
        if (a == 0L || b == 0L) return ln(max(1.0, (n - 1.0) * (n - 2.0) / 2.0))
        return -ln(a.toDouble() / b)
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val da = a[i] - ma; val db = b[i] - mb; sab += da * db; saa += da * da; sbb += db * db }
        return if (saa < 1e-12 || sbb < 1e-12) 0.0 else sab / sqrt(saa * sbb)
    }

    /** Szerokość QRS w ms: od początku do końca najszybszej zmiany napięcia wokół R (próg 15% maksymalnego nachylenia). Rozdzielczość to jedna próbka (4 ms przy 250 Hz). */
    private fun qrsWidthMs(w: DoubleArray, r: Int, fs: Double): Double? {
        if (w.size < 8) return null
        val d = DoubleArray(w.size - 1) { w[it + 1] - w[it] }
        val a = max(0, r - (0.06 * fs).toInt()); val b = min(d.size - 1, r + (0.06 * fs).toInt())
        var maxSlope = 0.0
        for (i in a..b) maxSlope = max(maxSlope, abs(d[i]))
        if (maxSlope < 1e-9) return null
        val thr = 0.15 * maxSlope
        // początek i koniec to pierwszy i ostatni punkt w oknie ±90 ms, w którym nachylenie przekracza próg (szczyt R ma nachylenie bliskie zeru, więc nie można zaczynać od niego)
        val lo = max(0, r - (0.09 * fs).toInt()); val hi = min(d.size - 1, r + (0.09 * fs).toInt())
        var on = -1; var off = -1
        for (i in lo..hi) if (abs(d[i]) >= thr) { if (on < 0) on = i; off = i }
        if (on < 0) return null
        return (off + 1 - on) / fs * 1000
    }

    /** Liczba z jednym miejscem po przecinku, w polskim zapisie (11,0). */
    private fun pl1(v: Double) = "%.1f".format(java.util.Locale.ROOT, v).replace('.', ',')

    private fun median(x: List<Double>): Double {
        val s = x.sorted(); val n = s.size
        return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Mediana ruchoma z okna o [window] próbkach (przy brzegach okno jest krótsze); okno trzymane jest w posortowanej tablicy, więc koszt jest liniowy względem długości sygnału. */
    private fun movingMedian(x: DoubleArray, window: Int): DoubleArray {
        val w = max(3, window or 1); val h = w / 2
        val out = DoubleArray(x.size)
        val sorted = DoubleArray(w + 2)
        var cnt = 0; var lo = 0; var hi = -1
        fun pos(v: Double): Int { var l = 0; var r = cnt; while (l < r) { val m = (l + r) ushr 1; if (sorted[m] < v) l = m + 1 else r = m }; return l }
        for (i in x.indices) {
            val newLo = max(0, i - h); val newHi = min(x.size - 1, i + h)
            while (hi < newHi) { hi++; val p = pos(x[hi]); System.arraycopy(sorted, p, sorted, p + 1, cnt - p); sorted[p] = x[hi]; cnt++ }
            while (lo < newLo) { val p = pos(x[lo]); System.arraycopy(sorted, p + 1, sorted, p, cnt - p - 1); cnt--; lo++ }
            out[i] = sorted[cnt / 2]
        }
        return out
    }

    fun analyze(signal: DoubleArray, fs: Double): EcgResult {
        val n = signal.size
        val dur = if (fs > 0) n / fs else 0.0
        if (n < fs * 3 || fs < 40) return empty(dur, "Zapis jest za krótki do analizy.")

        // 1. usunięcie dryfu linii izoelektrycznej
        val base = movingMedian(signal, (0.6 * fs).toInt())
        val x = DoubleArray(n) { signal[it] - base[it] }

        // 2. energia pochodnej, wygładzona: duża w zespole QRS, mała w załamkach P i T
        val w = max(1, (0.06 * fs).toInt())
        val d2 = DoubleArray(n) { if (it == 0) 0.0 else (x[it] - x[it - 1]).let { d -> d * d } }
        val s = DoubleArray(n)
        var acc = 0.0
        for (i in 0 until n) { acc += d2[i]; if (i >= w) acc -= d2[i - w]; s[i] = acc / w }

        // 3. odniesienie: mediana maksimów z okien 1,5 s (odporna na pojedyncze artefakty)
        val win = max(1, (1.5 * fs).toInt())
        val winMax = ArrayList<Double>()
        var i0 = 0
        while (i0 + win <= n) { winMax.add(s.slice(i0 until i0 + win).max()); i0 += win }
        if (winMax.isEmpty()) winMax.add(s.max())
        val ref = median(winMax)
        if (ref <= 1e-9) return empty(dur, "Nie wykryto wyraźnych zespołów QRS (płaski zapis).")
        val thr = 0.25 * ref

        // 4. kandydaci: lokalne maksima powyżej progu, co najmniej 0,25 s od siebie (większe wygrywają)
        val minDist = (0.25 * fs).toInt()
        val peaks = (1 until n - 1).filter { s[it] >= s[it - 1] && s[it] > s[it + 1] && s[it] > thr }.sortedByDescending { s[it] }
        val taken = ArrayList<Int>()
        for (p in peaks) if (taken.none { abs(it - p) < minDist }) taken.add(p)
        taken.sort()

        // 5. biegunowość QRS: większość zespołów ma ten sam kierunek; wychylenia przeciwne i duże to zwykle artefakty, nie uderzenia
        val half = max(1, (0.07 * fs).toInt())
        fun extreme(p: Int, sign: Int): Int {
            val a = max(0, p - half); val b = min(n - 1, p + half)
            var best = a
            for (i in a..b) if (sign * x[i] > sign * x[best]) best = i
            return best
        }
        val firstPass = taken.map { p -> val i = extreme(p, 1); val j = extreme(p, -1); if (abs(x[i]) >= abs(x[j])) 1 else -1 }
        val pol = if (firstPass.count { it > 0 } >= firstPass.count { it < 0 }) 1 else -1
        val cand0 = taken.map { p -> val r = extreme(p, pol); r to pol * x[r] }
        if (cand0.isEmpty()) return empty(dur, "Nie wykryto uderzeń serca.")
        val ampRef = median(cand0.map { it.second }.filter { it > 0 }.ifEmpty { listOf(1.0) })
        fun wideAt(i: Int) = abs(x[i]) > 2.5 * ampRef || pol * x[i] < -0.9 * ampRef
        // uderzenie szerokie, duże albo o przeciwnym kierunku (np. komorowe) ma stromość zwykłego QRS (od połowy do 4 razy typowej energii pochodnej), w odróżnieniu od skoku z ruchu albo powolnego falowania: takie nie jest zakłóceniem, tylko uderzeniem do oceny
        val reach = (0.10 * fs).toInt()
        val rescued = HashSet<Int>()
        val protect = BooleanArray(n)
        val shield = (0.25 * fs).toInt()   // cały szeroki załamek, razem z jego zboczami
        for (p in taken) {
            if (s[p] > 4.0 * ref || s[p] < 0.5 * ref || (max(0, p - reach)..min(n - 1, p + reach)).none { wideAt(it) }) continue
            val big = max(abs(x[extreme(p, 1)]), abs(x[extreme(p, -1)]))
            if (big < 0.5) continue   // szum i bardzo małe wychylenia nie są uderzeniami do ratowania
            rescued.add(p); for (i in max(0, p - shield)..min(n - 1, p + shield)) protect[i] = true
        }
        val cand = cand0.mapIndexed { k, c ->
            if (taken[k] !in rescued) c else { val i = extreme(taken[k], 1); val j = extreme(taken[k], -1); val r = if (abs(x[i]) >= abs(x[j])) i else j; r to abs(x[r]) }
        }

        // 6. artefakty: wychylenia kilkakrotnie większe niż typowy QRS albo skoki znacznie bardziej strome niż QRS (ruch, odklejenie elektrody)
        val bad = BooleanArray(n)
        val pad = (0.35 * fs).toInt()
        fun markBad(i: Int) { for (k in max(0, i - pad)..min(n - 1, i + pad)) bad[k] = true }
        // wychylenie przeciwne do QRS o amplitudzie zbliżonej do R nie jest załamkiem S (S jest zwykle kilkakrotnie mniejsze): to ruch lub zakłócenie
        for (i in 0 until n) if (!protect[i] && wideAt(i)) markBad(i)
        for (p in taken) if (s[p] > 4.0 * ref) markBad(p)
        val artifacts = ArrayList<ClosedFloatingPointRange<Double>>()
        var st = -1
        for (i in 0..n) {
            val b = i < n && bad[i]
            if (b && st < 0) st = i
            if (!b && st >= 0) { artifacts.add(st / fs..(i - 1) / fs); st = -1 }
        }
        val artifactRatio = bad.count { it }.toDouble() / n

        val beatsIdx = cand.filter { (idx, amp) -> !bad[idx] && amp >= 0.35 * ampRef }.map { it.first }
        val beats = beatsIdx.map { EcgBeat(it / fs, abs(x[it])) }

        // 7. odstępy RR: tylko między sąsiednimi uderzeniami bez artefaktu pomiędzy nimi
        val rr = ArrayList<Double?>()
        val gaps = ArrayList<Pair<Int, Double>>()   // odstępy 3-10 s bez zakłócenia: nie liczą się do statystyk, ale są przerwami w rytmie
        for (k in 0 until beatsIdx.size - 1) {
            val a = beatsIdx[k]; val b = beatsIdx[k + 1]
            var ok = true
            for (i in a..b) if (bad[i]) { ok = false; break }
            val v = (b - a) / fs
            rr.add(if (ok && v in 0.25..3.0) v else null)
            if (ok && v > 3.0 && v <= 10.0) gaps.add(k to v)
        }
        val v = rr.filterNotNull()
        val flags = ArrayList<EcgFlag>()
        val premature = ArrayList<Double>()
        if (beats.size < 8 || v.size < 6) {
            flags.add(EcgFlag("poor", "Zapis słabej jakości", "Za mało czytelnych uderzeń do oceny rytmu. Powtórz pomiar: siedząc, w spokoju, z rękami opartymi i nieruchomo.", EcgSeverity.Attention))
            return EcgResult(dur, beats, rr, null, null, null, null, null, null, null, null, artifactRatio, artifacts, flags, premature)
        }
        val m = median(v)
        val mean = v.average()
        val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
        val hrMean = 60.0 / mean; val hrMed = 60.0 / m
        val diffs = ArrayList<Double>()
        for (k in 0 until rr.size - 1) { val a = rr[k]; val b = rr[k + 1]; if (a != null && b != null) diffs.add(b - a) }
        val rmssd = if (diffs.isNotEmpty()) sqrt(diffs.sumOf { it * it } / diffs.size) * 1000 else null
        val pnn = if (diffs.isNotEmpty()) diffs.count { abs(it) > 0.05 }.toDouble() / diffs.size else null
        val cv = sd / mean

        // 8. kandydaci na nieprawidłowości
        val prematureIdx = ArrayList<Int>()
        for (k in 0 until rr.size - 1) {
            val a = rr[k]; val b = rr[k + 1]
            if (a != null && b != null && a < 0.8 * m && b > 1.1 * m) { premature.add(beats[k + 1].tSec); prematureIdx.add(k + 1) }
        }

        // 8a. wskaźniki losowej nieregularności odstępów RR (bez fragmentów z zakłóceniami)
        val nRmssd = rmssd?.let { it / 1000 / mean }
        val tpr = turningPointRatio(v)
        val sampEn = sampleEntropy(v)
        val afPattern = v.size >= 20 && nRmssd != null && tpr != null && sampEn != null && nRmssd >= 0.12 && tpr in 0.55..0.82 && sampEn >= 1.0

        // 8b. kształt uderzeń: szablon (mediana czystych uderzeń), korelacja z szablonem i szerokość QRS
        val pre = max(2, (0.10 * fs).toInt()); val post = max(2, (0.14 * fs).toInt())
        fun window(i: Int): DoubleArray? = if (i - pre >= 0 && i + post < n) DoubleArray(pre + post + 1) { x[i - pre + it] } else null
        val normalIdx = beatsIdx.indices.filter { it !in prematureIdx }
        val wins = normalIdx.mapNotNull { window(beatsIdx[it]) }
        var qrsWidth: Double? = null; var shapeCorr: Double? = null
        val shapes = ArrayList<String>()
        if (wins.size >= 6) {
            val tpl = DoubleArray(pre + post + 1) { k -> median(wins.map { it[k] }) }
            qrsWidth = qrsWidthMs(tpl, pre, fs)
            shapeCorr = median(wins.map { correlation(it, tpl) })
            for (bi in prematureIdx) {
                val w = window(beatsIdx[bi])
                if (w == null) { shapes.add("kształt nieoceniony (uderzenie przy brzegu zapisu)"); continue }
                val c = correlation(w, tpl); val wd = qrsWidthMs(w, pre, fs)
                val wide = qrsWidth != null && wd != null && wd > 1.3 * qrsWidth + 10
                shapes.add(if (c < 0.75 || wide) "kształt odmienny od pozostałych uderzeń (możliwe pobudzenie komorowe)" else "kształt zbliżony do pozostałych uderzeń (możliwe pobudzenie przedsionkowe lub nadkomorowe)")
            }
        } else for (bi in prematureIdx) shapes.add("kształt nieoceniony (za mało czystych uderzeń)")
        val deviating = v.count { abs(it - m) / m > 0.15 }.toDouble() / v.size
        val longest = max(v.max(), gaps.maxOfOrNull { it.second } ?: 0.0)

        if (hrMed > 100) flags.add(EcgFlag("tachy", "Szybkie tętno: ${hrMed.toInt()}/min", "Tętno powyżej 100/min. Jeśli utrzymuje się w spoczynku, omów z lekarzem.", EcgSeverity.Attention))
        if (hrMed < 50) flags.add(EcgFlag("brady", "Wolne tętno: ${hrMed.toInt()}/min", "Tętno poniżej 50/min. Jeśli towarzyszą zawroty głowy lub omdlenia, skontaktuj się z lekarzem.", EcgSeverity.Attention))
        if (afPattern) {
            flags.add(EcgFlag("af_pattern", "Odstępy jak przy migotaniu przedsionków", "Odstępy między uderzeniami są nieregularne w sposób losowy (RMSSD/RR ${pl2(nRmssd!!)}, punkty zwrotne ${"%.0f".format(java.util.Locale.ROOT, tpr!! * 100)}%, entropia ${pl1(sampEn!!)}). Aplikacja nie rozpoznaje migotania przedsionków i może się mylić: pokaż zapis lekarzowi i zrób EKG w gabinecie.", EcgSeverity.Attention))
        } else if (deviating >= 0.35 && cv > 0.1) {
            flags.add(EcgFlag("irregular", "Rytm wyraźnie nieregularny", "${"%.0f".format(java.util.Locale.ROOT, deviating * 100)}% odstępów między uderzeniami odbiega o ponad 15% od typowego. Przyczyną może być migotanie przedsionków albo częste pobudzenia dodatkowe. Aplikacja tego nie rozpoznaje: pokaż zapis lekarzowi i zrób EKG w gabinecie.", EcgSeverity.Attention))
        } else {
            if (premature.size >= 3) flags.add(EcgFlag("premature_many", "Kilka przedwczesnych uderzeń (${premature.size})", "Po każdym następuje dłuższa przerwa. Mogą to być pobudzenia dodatkowe (${shapes.groupingBy { it.substringBefore(" (") }.eachCount().entries.joinToString("; ") { "${it.value}× ${it.key}" }}); omów z lekarzem, zwłaszcza gdy je czujesz.", EcgSeverity.Attention))
            else if (premature.isNotEmpty()) flags.add(EcgFlag("premature", "Pojedyncze przedwczesne uderzenie", "Po nim następuje dłuższa przerwa (około ${pl1(premature[0])} s od początku zapisu); ${shapes.firstOrNull() ?: "kształt nieoceniony"}. Takie pobudzenia bywają u zdrowych osób, ale może to być też zakłócenie. Warto o nie zapytać lekarza.", EcgSeverity.Info))
        }
        if (longest > 1.6 * m && longest > 1.8) flags.add(EcgFlag("pause", "Przerwa w rytmie: ${pl1(longest)} s", "Najdłuższy odstęp między uderzeniami (typowo ${pl1(m)} s). Omów z lekarzem.", EcgSeverity.Attention))
        if (artifactRatio > 0.1) flags.add(EcgFlag("artifacts", "Zakłócenia w zapisie", "Ruch lub słaby kontakt elektrody: około ${"%.0f".format(java.util.Locale.ROOT, artifactRatio * 100)}% czasu pominięto w analizie (szare tło na przebiegu).", EcgSeverity.Info))
        val poorQuality = artifactRatio > 0.35 || (shapeCorr != null && shapeCorr < 0.6)
        if (flags.none { it.severity == EcgSeverity.Attention || it.code == "premature" } && poorQuality) flags.add(0, EcgFlag("poor", "Zapis słabej jakości", "Dużo zakłóceń albo uderzenia bardzo różnią się kształtem, więc rytmu nie da się wiarygodnie ocenić. Powtórz pomiar: siedząc, w spokoju, z rękami opartymi i nieruchomo.", EcgSeverity.Attention))
        else if (flags.none { it.severity == EcgSeverity.Attention || it.code == "premature" }) flags.add(0, EcgFlag("none", "Rytm regularny", "Aplikacja nie wykryła nieregularności w czytelnych fragmentach. To nie wyklucza choroby serca.", EcgSeverity.Info))

        // zdarzenia z położeniem w czasie
        val events = ArrayList<EcgEvent>()
        for ((n, bi) in prematureIdx.withIndex()) {
            val short = rr.getOrNull(bi - 1); val after = rr.getOrNull(bi)
            val many = premature.size >= 3
            events.add(EcgEvent(beats[bi].tSec, null, "premature", "Przedwczesne uderzenie, ${pl1(beats[bi].tSec)} s",
                "Odstęp " + (short?.let { pl2(it) } ?: "?") + " s zamiast typowego ${pl2(m)} s" + (after?.let { ", po nim przerwa ${pl2(it)} s" } ?: "") + ". " +
                    shapes.getOrElse(n) { "kształt nieoceniony" }.replaceFirstChar { it.uppercase() } + ". Takie pobudzenia bywają u zdrowych osób, ale mogą też być zakłóceniem; warto zapytać lekarza.",
                if (many) EcgSeverity.Attention else EcgSeverity.Info))
        }
        if (longest > 1.6 * m && longest > 1.8) {
            val k = gaps.firstOrNull { it.second == longest }?.first ?: rr.indexOfFirst { it == longest }
            if (k >= 0) events.add(EcgEvent(beats[k].tSec, beats[k + 1].tSec, "pause", "Przerwa w rytmie: ${pl1(longest)} s, od ${pl1(beats[k].tSec)} s",
                "Odstęp między kolejnymi uderzeniami wynosi ${pl2(longest)} s (typowo ${pl2(m)} s). Omów z lekarzem.", EcgSeverity.Attention))
        }
        for (a in artifacts) events.add(EcgEvent(a.start, a.endInclusive, "artifact", "Zakłócenie, ${pl1(a.start)}–${pl1(a.endInclusive)} s",
            "Ruch lub słaby kontakt elektrody. Ten fragment (${pl1(a.endInclusive - a.start)} s) jest pominięty w analizie, więc uderzenia i odstępy w nim nie są liczone.", EcgSeverity.Info))
        events.sortBy { it.tSec }

        return EcgResult(
            dur, beats, rr, hrMean, hrMed, 60.0 / v.max(), 60.0 / v.min(), sd * 1000, rmssd, pnn, cv, artifactRatio, artifacts, flags, premature,
            nRmssd, tpr, sampEn, afPattern, qrsWidth, shapeCorr, shapes, events,
        )
    }

    private fun empty(dur: Double, why: String) = EcgResult(
        dur, emptyList(), emptyList(), null, null, null, null, null, null, null, null, 0.0, emptyList(),
        listOf(EcgFlag("poor", "Zapis nie nadaje się do analizy", why, EcgSeverity.Attention)), emptyList(),
    )

    /** Zmniejsza liczbę próbek do [fsOut] (liniowo) i zaokrągla do setnych mV. */
    fun toRecord(mv: DoubleArray, fsIn: Double, fsOut: Int = 125): List<Int> {
        if (mv.isEmpty() || fsIn <= 0) return emptyList()
        val n = (mv.size * fsOut / fsIn).toInt()
        return List(n) { i ->
            val pos = i * fsIn / fsOut
            val a = pos.toInt().coerceIn(0, mv.size - 1); val b = min(mv.size - 1, a + 1)
            val f = pos - a
            Math.round((mv[a] * (1 - f) + mv[b] * f) * 100).toInt().coerceIn(-1000, 1000)
        }
    }

    private data class CacheKey(val id: String, val fs: Int, val size: Int, val hash: Int)
    private val cache = object : LinkedHashMap<CacheKey, EcgResult>(16, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, EcgResult>?) = size > 24 }

    /** Analiza zapisu z pamięcią podręczną (klucz: identyfikator i zawartość przebiegu), bo ekrany i raporty liczą ją przy każdym odświeżeniu. */
    fun analyze(e: Ecg): EcgResult {
        val key = CacheKey(e.id, e.fs, e.cmv.size, e.cmv.hashCode())
        synchronized(cache) { cache[key]?.let { return it } }
        val r = analyze(e.signalMv(), e.fs.toDouble())
        synchronized(cache) { cache[key] = r }
        return r
    }
}

/** Odtwarzanie przebiegu z obrazu wydruku (PDF z zegarka po wyrenderowaniu do bitmapy): czerwona linia na siatce. */
object EcgDigitizer {
    /** [vertical]: pasy były ułożone pionowo (strona obrócona o 90°), więc do odczytu tekstu obraz trzeba obrócić o 90° w lewo. */
    class Result(val fs: Double, val mv: DoubleArray, val strips: Int, val vertical: Boolean)

    /** Czy piksel ARGB należy do czerwonej linii przebiegu. */
    fun isTrace(p: Int): Boolean {
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        return r >= 170 && g <= 130 && b <= 130 && r - max(g, b) >= 70
    }

    /** Maska jednobajtowa (1 = ślad): cztery razy mniej pamięci niż piksele ARGB, więc pełna strona w 300 dpi nie wymaga trzymania dużej bitmapy. */
    fun mask(px: IntArray, w: Int, h: Int): ByteArray = ByteArray(w * h) { if (isTrace(px[it])) 1 else 0 }

    fun digitize(px: IntArray, w: Int, h: Int, pxPerMm: Double, gainMmPerMv: Double = 10.0, paperMmPerS: Double = 25.0): Result? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        return digitizeMask(mask(px, w, h), w, h, pxPerMm, gainMmPerMv, paperMmPerS)
    }

    /**
     * [px] to piksele ARGB (wiersz po wierszu), [pxPerMm] to liczba pikseli na milimetr papieru (np. dpi / 25,4).
     * Wydruk zegarka ma przebieg w pasach po 10 s: na stronie pionowej pasy są obrócone o 90° (czas biegnie w dół, pierwszy pas po prawej).
     * Zakładamy standard wydruku: 25 mm/s i 10 mm/mV.
     */
    fun digitizeMask(m: ByteArray, w: Int, h: Int, pxPerMm: Double, gainMmPerMv: Double = 10.0, paperMmPerS: Double = 25.0): Result? {
        if (w <= 0 || h <= 0 || m.size < w * h || pxPerMm <= 0) return null
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1; var count = 0
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x].toInt() != 0) {
            count++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (count < 300) return null
        val vertical = (y1 - y0) > (x1 - x0)
        val uN = if (vertical) h else w
        val vN = if (vertical) w else h
        fun hit(u: Int, v: Int) = (if (vertical) m[u * w + v] else m[v * w + u]).toInt() != 0

        // pasy: ciągłe zakresy osi amplitudy, w których jest ślad; luka większa niż 8 mm oddziela kolejne pasy
        val has = BooleanArray(vN)
        for (u in 0 until uN) for (v in 0 until vN) if (hit(u, v)) has[v] = true
        val gap = (8 * pxPerMm).toInt()
        val bands = ArrayList<IntRange>()
        var s = -1; var lastHas = -1
        for (v in 0..vN) {
            val hv = v < vN && has[v]
            if (hv) { if (s < 0) s = v; lastHas = v }
            else if (s >= 0 && (v >= vN || v - lastHas > gap)) { bands.add(s..lastHas); s = -1 }
        }
        val real0 = bands.filter { b -> var c = 0; for (u in 0 until uN) for (v in b) if (hit(u, v)) c++; c >= 200 }
        if (real0.isEmpty()) return null
        // pasy znacznie krótsze od typowego (ikona, plama, pojedyncza kreska) to nie przebieg: ich doklejenie przesunęłoby cały czas
        val extents = real0.map { b -> val f = (0 until uN).first { u -> b.any { v -> hit(u, v) } }; val l = (uN - 1 downTo 0).first { u -> b.any { v -> hit(u, v) } }; l - f + 1 }
        val typical = extents.sorted()[extents.size / 2]
        val minLen = max(0.15 * typical, 12.5 * pxPerMm)
        val real = real0.filterIndexed { i, _ -> extents[i] >= minLen }
        if (real.isEmpty()) return null
        // kolejność w czasie: strona pionowa - od prawej do lewej, pozioma - od góry do dołu
        val ordered = if (vertical) real.sortedByDescending { it.first } else real.sortedBy { it.first }
        val sign = if (vertical) 1.0 else -1.0

        val out = ArrayList<Double>()
        for (b in ordered) {
            val first = (0 until uN).first { u -> b.any { v -> hit(u, v) } }
            val last = (uN - 1 downTo 0).first { u -> b.any { v -> hit(u, v) } }
            val len = last - first + 1
            val means = DoubleArray(len) { Double.NaN }
            for (k in 0 until len) {
                var sum = 0.0; var c = 0
                for (v in b) if (hit(first + k, v)) { sum += v; c++ }
                if (c > 0) means[k] = sum / c
            }
            val sorted = means.filter { !it.isNaN() }.sorted()
            val baseline = sorted[sorted.size / 2]
            val sig = DoubleArray(len) { Double.NaN }
            for (k in 0 until len) {
                var best = Double.NaN
                for (v in b) if (hit(first + k, v)) { val d = v - baseline; if (best.isNaN() || abs(d) > abs(best)) best = d }
                sig[k] = best
            }
            // luki (brak piksela w wierszu) uzupełniamy liniowo
            var k = 0
            while (k < len) {
                if (sig[k].isNaN()) {
                    var j = k; while (j < len && sig[j].isNaN()) j++
                    val a = if (k > 0) sig[k - 1] else 0.0; val bb = if (j < len) sig[j] else a
                    for (t in k until j) sig[t] = a + (bb - a) * (t - k + 1) / (j - k + 1)
                    k = j
                } else k++
            }
            for (t in 0 until len) out.add(sign * sig[t] / pxPerMm / gainMmPerMv)
        }
        return Result(pxPerMm * paperMmPerS, out.toDoubleArray(), ordered.size, vertical)
    }
}

/**
 * Zwarty zapis przebiegu w kopii zapasowej: różnice kolejnych próbek (zigzag, zmienna długość) jako base64. Typowy 30-sekundowy zapis ma ok. 8 KB
 * zamiast ok. 90 KB jako lista liczb, więc plik danych nie puchnie przy dziesiątkach zapisów.
 */
object EcgPacking {
    fun pack(cmv: List<Int>): String {
        val out = java.io.ByteArrayOutputStream(cmv.size * 2)
        var prev = 0
        for (v in cmv) {
            val d = v - prev; prev = v
            var z = (d shl 1) xor (d shr 31)
            while (z >= 0x80) { out.write((z and 0x7F) or 0x80); z = z ushr 7 }
            out.write(z)
        }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Odwrotność [pack]; null przy uszkodzonych danych. Wartości są ograniczone do ±1000 (±10 mV). */
    fun unpack(s: String, maxSamples: Int = 200_000): List<Int>? {
        val b = try { java.util.Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { return null }
        val out = ArrayList<Int>(b.size)
        var i = 0; var prev = 0
        while (i < b.size) {
            var z = 0; var shift = 0
            while (true) {
                if (i >= b.size || shift > 28) return null
                val x = b[i++].toInt() and 0xFF
                z = z or ((x and 0x7F) shl shift)
                if (x < 0x80) break
                shift += 7
            }
            prev += (z ushr 1) xor -(z and 1)
            out.add(prev.coerceIn(-1000, 1000))
            if (out.size > maxSamples) return null
        }
        return out
    }
}

/**
 * Zapis wektorowy: zamiast równych próbek przechowujemy tylko punkty załamania linii (uproszczenie Ramera, Douglasa i Peuckera z błędem pionowym
 * do [EPS] setnych mV). Odtworzenie przez interpolację liniową różni się od przebiegu o najwyżej [EPS] (0,02 mV, a błąd samego odtworzenia
 * z obrazu PDF to około 0,05 mV), a załamki i szczyty R zostają zachowane dokładnie, bo są wierzchołkami. Fragmenty płaskie między uderzeniami zajmują kilka punktów.
 */
object EcgVector {
    /** Dopuszczalny błąd pionowy w setnych mV (0,02 mV to około 2 piksele wydruku w 300 dpi, mniej niż błąd samego odtworzenia z obrazu). */
    const val EPS = 2

    /** Indeksy zachowanych punktów (pierwszy i ostatni zawsze). */
    fun simplify(y: List<Int>, eps: Int = EPS): List<Int> {
        val n = y.size
        if (n <= 2) return y.indices.toList()
        val keep = BooleanArray(n); keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>(); stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            var maxD = -1.0; var idx = -1
            for (i in a + 1 until b) {
                val line = y[a] + (y[b] - y[a]).toDouble() * (i - a) / (b - a)
                val d = abs(y[i] - line)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > eps) { keep[idx] = true; stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b)) }
        }
        return keep.indices.filter { keep[it] }
    }

    private fun put(out: java.io.ByteArrayOutputStream, v: Int) { var z = v; while (z >= 0x80) { out.write((z and 0x7F) or 0x80); z = z ushr 7 }; out.write(z) }
    private fun zig(d: Int) = (d shl 1) xor (d shr 31)

    /** Pakuje przebieg do tekstu: liczba próbek, liczba punktów, potem dla każdego punktu odstęp indeksu i zmiana wartości (zigzag), wszystko jako zmienna długość i base64. */
    fun pack(cmv: List<Int>, eps: Int = EPS): String {
        val idx = simplify(cmv, eps)
        val out = java.io.ByteArrayOutputStream(idx.size * 3 + 8)
        put(out, cmv.size); put(out, idx.size)
        var pi = 0; var pv = 0
        for (i in idx) { put(out, i - pi); put(out, zig(cmv[i] - pv)); pi = i; pv = cmv[i] }
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * Sprowadza przebieg do punktu stałego zapisu (zapis i odczyt nie zmieniają go już ani o jedną próbkę), więc przebieg w pamięci jest taki sam jak ten w pliku
     * i wielokrotne zapisywanie nie powoduje dryfu. Pierwsze przejście jest stratne (do [EPS]), kolejne zwykle niczego nie zmieniają.
     */
    fun normalize(cmv: List<Int>, maxPasses: Int = 8): List<Int> {
        var cur = cmv
        repeat(maxPasses) {
            val next = unpack(pack(cur)) ?: return cur
            if (next == cur) return cur
            cur = next
        }
        return cur
    }

    /** Odtwarza równe próbki przez interpolację liniową; null przy uszkodzonych danych. */
    fun unpack(s: String, maxSamples: Int = 200_000): List<Int>? {
        val b = try { java.util.Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { return null }
        var pos = 0
        fun get(): Int? {
            var z = 0; var shift = 0
            while (true) {
                if (pos >= b.size || shift > 28) return null
                val x = b[pos++].toInt() and 0xFF
                z = z or ((x and 0x7F) shl shift)
                if (x < 0x80) return z
                shift += 7
            }
        }
        val n = get() ?: return null; val k = get() ?: return null
        if (n < 0 || n > maxSamples || k < 0 || k > n || (n > 0 && k < 1)) return null
        val out = IntArray(n)
        var pi = 0; var pv = 0; var first = true
        repeat(k) {
            val di = get() ?: return null; val dz = get() ?: return null
            val il = pi.toLong() + di
            if (il >= n || il < 0 || (!first && di <= 0) || (first && il != 0L)) return null
            val i = il.toInt(); val v = (pv + ((dz ushr 1) xor -(dz and 1))).coerceIn(-1000, 1000)
            if (!first) for (t in pi + 1..i) out[t] = Math.round(pv + (v - pv).toDouble() * (t - pi) / (i - pi)).toInt()
            out[i] = v; pi = i; pv = v; first = false
        }
        if (n > 0 && pi != n - 1) return null
        return out.toList()
    }
}

/** Dane odczytane z tekstu wydruku zegarka (po OCR). Imię, płeć, wiek, wzrost i wagę celowo pomijamy. */
object EcgMeta {
    /** OCR skleja kolumny nagłówka w jeden wiersz („Rytm zatokowy Średnie tętno:73…”): zostawiamy tylko samą ocenę rytmu. */
    fun cleanNote(raw: String): String {
        val cut = Regex("""(?:^|\s+)(Średnie|Srednie|Godzina|Czas\s+trwania|Wzmocnienie|Prędkość|Predkosc|Imię|Imie|Płeć|Plec|Wiek|Wzrost|Waga|Dane\s+osobowe|Ten\s+zapis)\b""", RegexOption.IGNORE_CASE).find(raw)
        return (if (cut != null) raw.substring(0, cut.range.first) else raw).trim().trim(',', ';', ':').take(120)
    }

    data class Meta(val date: LocalDate?, val time: LocalTime?, val hr: Int, val note: String)

    fun parse(lines: List<String>): Meta {
        val all = lines.joinToString("\n")
        val hr = Regex("""[ŚSs][rR]ednie\s+t[eę]tno\s*:?\s*(\d{2,3})""", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val dt = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{1,2}):(\d{2})""").find(all)
        val date = dt?.let { runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull() }
        val time = dt?.let { runCatching { LocalTime.of(it.groupValues[4].toInt(), it.groupValues[5].toInt()) }.getOrNull() }
        val rhythm = lines.firstOrNull { Regex("""^\s*(rytm|migotanie|arytmia|trzepotanie|tachykardia|bradykardia|nieregularny|nie\s+mo[zż]na)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()
        return Meta(date, time, hr, cleanNote(rhythm))
    }
}

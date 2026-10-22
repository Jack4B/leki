package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
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
    val fs: Int = 125,
    /** Przebieg w setnych częściach mV. */
    val cmv: List<Int> = emptyList(),
    val note: String = "",
) {
    val durationS: Double get() = if (fs > 0) cmv.size.toDouble() / fs else 0.0
    fun signalMv(): DoubleArray = DoubleArray(cmv.size) { cmv[it] / 100.0 }
}

enum class EcgSeverity { Info, Attention }

data class EcgFlag(val code: String, val title: String, val text: String, val severity: EcgSeverity)

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
) {
    val validRr: List<Double> get() = rr.filterNotNull()
    val quality: String get() = when {
        beats.size < 8 || artifactRatio > 0.35 -> "słaba"
        artifactRatio > 0.1 -> "umiarkowana"
        else -> "dobra"
    }
}

/**
 * Prosta analiza rytmu z jednego odprowadzenia: wykrywanie załamków R, odstępy RR, tętno, zmienność i kandydaci na nieprawidłowości.
 * To narzędzie pomocnicze do rozmowy z lekarzem, a nie diagnoza: nie mierzy odstępu QT ani przesunięć ST i nie rozpoznaje niedokrwienia.
 */
object EcgAnalysis {
    /** Liczba z jednym miejscem po przecinku, w polskim zapisie (11,0). */
    private fun pl1(v: Double) = "%.1f".format(java.util.Locale.ROOT, v).replace('.', ',')

    private fun median(x: List<Double>): Double {
        val s = x.sorted(); val n = s.size
        return if (n == 0) 0.0 else if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun movingMedian(x: DoubleArray, window: Int): DoubleArray {
        val w = max(3, window or 1); val h = w / 2
        val out = DoubleArray(x.size)
        val buf = DoubleArray(w)
        for (i in x.indices) {
            val a = max(0, i - h); val b = min(x.size - 1, i + h)
            val len = b - a + 1
            for (k in 0 until len) buf[k] = x[a + k]
            java.util.Arrays.sort(buf, 0, len)
            out[i] = buf[len / 2]
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
        val thr = 0.4 * ref

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
        val cand = taken.map { p -> val r = extreme(p, pol); r to pol * x[r] }
        if (cand.isEmpty()) return empty(dur, "Nie wykryto uderzeń serca.")
        val ampRef = median(cand.map { it.second }.filter { it > 0 }.ifEmpty { listOf(1.0) })

        // 6. artefakty: wychylenia kilkakrotnie większe niż typowy QRS albo skoki znacznie bardziej strome niż QRS (ruch, odklejenie elektrody)
        val bad = BooleanArray(n)
        val pad = (0.35 * fs).toInt()
        fun markBad(i: Int) { for (k in max(0, i - pad)..min(n - 1, i + pad)) bad[k] = true }
        // wychylenie przeciwne do QRS o amplitudzie zbliżonej do R nie jest załamkiem S (S jest zwykle kilkakrotnie mniejsze): to ruch lub zakłócenie
        for (i in 0 until n) if (abs(x[i]) > 2.5 * ampRef || pol * x[i] < -0.9 * ampRef) markBad(i)
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
        for (k in 0 until beatsIdx.size - 1) {
            val a = beatsIdx[k]; val b = beatsIdx[k + 1]
            var ok = true
            for (i in a..b) if (bad[i]) { ok = false; break }
            val v = (b - a) / fs
            rr.add(if (ok && v in 0.25..3.0) v else null)
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
        for (k in 0 until rr.size - 1) {
            val a = rr[k]; val b = rr[k + 1]
            if (a != null && b != null && a < 0.8 * m && b > 1.1 * m) premature.add(beats[k + 1].tSec)
        }
        val deviating = v.count { abs(it - m) / m > 0.15 }.toDouble() / v.size
        val longest = v.max()

        if (hrMed > 100) flags.add(EcgFlag("tachy", "Szybkie tętno: ${hrMed.toInt()}/min", "Tętno powyżej 100/min. Jeśli utrzymuje się w spoczynku, omów z lekarzem.", EcgSeverity.Attention))
        if (hrMed < 50) flags.add(EcgFlag("brady", "Wolne tętno: ${hrMed.toInt()}/min", "Tętno poniżej 50/min. Jeśli towarzyszą zawroty głowy lub omdlenia, skontaktuj się z lekarzem.", EcgSeverity.Attention))
        if (deviating >= 0.35 && cv > 0.1) {
            flags.add(EcgFlag("irregular", "Rytm wyraźnie nieregularny", "${"%.0f".format(deviating * 100)}% odstępów między uderzeniami odbiega o ponad 15% od typowego. Przyczyną może być migotanie przedsionków albo częste pobudzenia dodatkowe. Aplikacja tego nie rozpoznaje: pokaż zapis lekarzowi i zrób EKG w gabinecie.", EcgSeverity.Attention))
        } else {
            if (premature.size >= 3) flags.add(EcgFlag("premature_many", "Kilka przedwczesnych uderzeń (${premature.size})", "Po każdym następuje dłuższa przerwa. Mogą to być pobudzenia dodatkowe; omów z lekarzem, zwłaszcza gdy je czujesz.", EcgSeverity.Attention))
            else if (premature.isNotEmpty()) flags.add(EcgFlag("premature", "Pojedyncze przedwczesne uderzenie", "Po nim następuje dłuższa przerwa (${premature.size} w zapisie, około ${premature.joinToString(", ") { pl1(it) + " s" }}). Takie pobudzenia bywają u zdrowych osób, ale może to być też zakłócenie. Warto o nie zapytać lekarza.", EcgSeverity.Info))
        }
        if (longest > 1.6 * m && longest > 1.8) flags.add(EcgFlag("pause", "Przerwa w rytmie: ${pl1(longest)} s", "Najdłuższy odstęp między uderzeniami (typowo ${pl1(m)} s). Omów z lekarzem.", EcgSeverity.Attention))
        if (artifactRatio > 0.1) flags.add(EcgFlag("artifacts", "Zakłócenia w zapisie", "Ruch lub słaby kontakt elektrody: około ${"%.0f".format(artifactRatio * 100)}% czasu pominięto w analizie (szare tło na przebiegu).", EcgSeverity.Info))
        if (flags.none { it.severity == EcgSeverity.Attention || it.code == "premature" }) flags.add(0, EcgFlag("none", "Rytm regularny", "Aplikacja nie wykryła nieregularności w czytelnych fragmentach. To nie wyklucza choroby serca.", EcgSeverity.Info))

        return EcgResult(
            dur, beats, rr, hrMean, hrMed, 60.0 / v.max(), 60.0 / v.min(), sd * 1000, rmssd, pnn, cv, artifactRatio, artifacts, flags, premature,
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

    fun analyze(e: Ecg): EcgResult = analyze(e.signalMv(), e.fs.toDouble())
}

/** Odtwarzanie przebiegu z obrazu wydruku (PDF z zegarka po wyrenderowaniu do bitmapy): czerwona linia na siatce. */
object EcgDigitizer {
    /** [vertical]: pasy były ułożone pionowo (strona obrócona o 90°), więc do odczytu tekstu obraz trzeba obrócić o 90° w lewo. */
    class Result(val fs: Double, val mv: DoubleArray, val strips: Int, val vertical: Boolean)

    private fun isTrace(p: Int): Boolean {
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
        return r >= 170 && g <= 130 && b <= 130 && r - max(g, b) >= 70
    }

    /**
     * [px] to piksele ARGB (wiersz po wierszu), [pxPerMm] to liczba pikseli na milimetr papieru (np. dpi / 25,4).
     * Wydruk zegarka ma przebieg w pasach po 10 s: na stronie pionowej pasy są obrócone o 90° (czas biegnie w dół, pierwszy pas po prawej).
     * Zakładamy standard wydruku: 25 mm/s i 10 mm/mV.
     */
    fun digitize(px: IntArray, w: Int, h: Int, pxPerMm: Double, gainMmPerMv: Double = 10.0, paperMmPerS: Double = 25.0): Result? {
        if (w <= 0 || h <= 0 || px.size < w * h || pxPerMm <= 0) return null
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1; var count = 0
        for (y in 0 until h) for (x in 0 until w) if (isTrace(px[y * w + x])) {
            count++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (count < 300) return null
        val vertical = (y1 - y0) > (x1 - x0)
        val uN = if (vertical) h else w
        val vN = if (vertical) w else h
        fun hit(u: Int, v: Int) = if (vertical) isTrace(px[u * w + v]) else isTrace(px[v * w + u])

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
        val real = bands.filter { b -> var c = 0; for (u in 0 until uN) for (v in b) if (hit(u, v)) c++; c >= 200 }
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

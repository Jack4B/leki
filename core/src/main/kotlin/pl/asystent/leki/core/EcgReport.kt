package pl.asystent.leki.core

/** Sekcja „EKG z zegarka” w raporcie dla lekarza: opis, wskaźniki z analizy i przebieg w układzie jak na wydruku (25 mm/s, 10 mm/mV, trzy pasy po 10 s). */
object EcgReport {
    private const val W = 1000.0          // 10 s = 250 mm, czyli 4 jednostki na milimetr
    private const val ROW = 140.0
    private const val BASE = 75.0         // linia izoelektryczna od góry pasa
    private const val MV = 40.0           // jednostek na mV (10 mm/mV)

    private fun f(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)

    private val LOCATED = setOf("premature", "premature_many", "pause", "artifacts")

    /** [trueScale]: szerokość w milimetrach tak, by 10 s zajmowało 250 mm (25 mm/s, 10 mm/mV), do wydruku A4 poziomo. */
    fun svg(e: Ecg, r: EcgResult, trueScale: Boolean = false): String {
        val sig = e.signalMv()
        if (sig.isEmpty()) return ""
        val per = (10.0 * e.fs).toInt().coerceAtLeast(1)
        val rows = ((sig.size + per - 1) / per).coerceIn(1, 3)
        val sb = StringBuilder()
        sb.append("<svg class=\"chart\" viewBox=\"0 0 ${f(W + 30)} ${f(ROW * rows)}\"" + (if (trueScale) " style=\"width:${f((W + 30) / 4)}mm;max-width:none\"" else "") + " role=\"img\" aria-label=\"Przebieg EKG\">")
        for (k in 0 until rows) {
            val top = k * ROW
            sb.append("<g transform=\"translate(24 ${f(top)})\">")
            var x = 0.0
            while (x <= W) { sb.append("<line x1=\"${f(x)}\" x2=\"${f(x)}\" y1=\"0\" y2=\"${f(ROW - 12)}\" stroke=\"${if ((x / 20).toInt() % 5 == 0) "#e3b5b5" else "#f1d8d8"}\" stroke-width=\"1\"/>"); x += 20.0 }
            var y = BASE % 20
            while (y <= ROW - 12) { sb.append("<line x1=\"0\" x2=\"${f(W)}\" y1=\"${f(y)}\" y2=\"${f(y)}\" stroke=\"#f1d8d8\" stroke-width=\"1\"/>"); y += 20.0 }
            // zakłócenia pominięte w analizie
            for (a in r.artifacts) {
                val a0 = a.start - k * 10.0; val a1 = a.endInclusive - k * 10.0
                if (a1 < 0 || a0 > 10) continue
                sb.append("<rect x=\"${f(maxOf(0.0, a0) * W / 10)}\" y=\"0\" width=\"${f((minOf(10.0, a1) - maxOf(0.0, a0)) * W / 10)}\" height=\"${f(ROW - 12)}\" fill=\"#b07000\" opacity=\".12\"/>")
            }
            val pts = StringBuilder()
            val from = k * per; val to = minOf(sig.size, from + per)
            for (i in from until to) pts.append(f((i - from).toDouble() / e.fs * W / 10)).append(',').append(f(BASE - sig[i].coerceIn(-(ROW - 12 - BASE) / MV, BASE / MV) * MV)).append(' ')
            sb.append("<polyline points=\"${pts.toString().trim()}\" fill=\"none\" stroke=\"#1a1a1a\" stroke-width=\"1.2\" stroke-linejoin=\"round\"/>")
            for (b in r.beats) {
                val t = b.tSec - k * 10.0
                if (t < 0 || t >= 10) continue
                sb.append("<path d=\"M${f(t * W / 10 - 3)},4 L${f(t * W / 10 + 3)},4 L${f(t * W / 10)},11 Z\" fill=\"#0b6b58\"/>")
            }
            // zdarzenia z listy pod wykresem: pomarańczowy pas z przerywanymi brzegami (przedwczesne uderzenie, przerwa) i numer; zakłócenia mają szare tło, więc same numer
            r.events.forEachIndexed { n, ev ->
                val a0 = if (ev.endSec == null) ev.tSec - 0.35 else ev.tSec
                val a1 = if (ev.endSec == null) ev.tSec + 0.35 else ev.endSec
                val lo = maxOf(a0, k * 10.0); val hi = minOf(a1, k * 10.0 + 10)
                if (hi <= lo) return@forEachIndexed
                val x0 = (lo - k * 10.0) * W / 10; val x1 = (hi - k * 10.0) * W / 10
                if (ev.code != "artifact") {
                    sb.append("<rect class=\"ev\" x=\"${f(x0)}\" y=\"0\" width=\"${f(x1 - x0)}\" height=\"${f(ROW - 12)}\" fill=\"#c25b00\" opacity=\".16\"/>")
                    sb.append("<line x1=\"${f(x0)}\" x2=\"${f(x0)}\" y1=\"0\" y2=\"${f(ROW - 12)}\" stroke=\"#c25b00\" stroke-width=\"1\" stroke-dasharray=\"4 3\"/><line x1=\"${f(x1)}\" x2=\"${f(x1)}\" y1=\"0\" y2=\"${f(ROW - 12)}\" stroke=\"#c25b00\" stroke-width=\"1\" stroke-dasharray=\"4 3\"/>")
                }
                val cx = (x0 + x1) / 2
                sb.append("<circle class=\"evn\" cx=\"${f(cx)}\" cy=\"24\" r=\"8\" fill=\"${if (ev.code == "artifact") "#666" else "#c25b00"}\"/><text x=\"${f(cx)}\" y=\"27.3\" font-size=\"9.5\" font-weight=\"bold\" text-anchor=\"middle\" fill=\"#fff\">${n + 1}</text>")
            }
            sb.append("<text x=\"2\" y=\"${f(ROW - 2)}\" font-size=\"9\" fill=\"#666\">${k * 10}–${k * 10 + 10} s</text>")
            sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** Opis jednego zapisu: wskaźniki, wyniki (ogólne i zdarzenia z czasem) i przebieg. */
    private fun entry(e: Ecg, r: EcgResult, heading: Boolean, trueScale: Boolean): String {
        val h = StringBuilder("<div class=\"ecg-e\">")
        if (heading) h.append("<h4>${plDate(e.date)} ${e.time.hhmm()}, ${"%.0f".format(java.util.Locale.ROOT, e.durationS)} s</h4>")
        val parts = ArrayList<String>()
        if (e.watchHr > 0) parts.add("tętno wg zegarka: <b>${e.watchHr}/min</b>")
        if (e.watchNote.isNotBlank()) parts.add("ocena zegarka: <b>${esc(e.watchNote)}</b>")
        r.hrMedian?.let { parts.add("tętno z zapisu: <b>${Math.round(it)}/min</b>" + (if (r.hrMin != null && r.hrMax != null) " (zakres ${Math.round(r.hrMin)}–${Math.round(r.hrMax)})" else "")) }
        r.sdnnMs?.let { parts.add("zmienność odstępów RR (SDNN): ${Math.round(it)} ms") }
        r.qrsWidthMs?.let { parts.add("szerokość QRS: ok. ${Math.round(it)} ms (orientacyjnie)") }
        parts.add("jakość zapisu: ${r.quality}")
        h.append("<p style=\"margin:0 0 6px\">${parts.joinToString("; ")}.</p>")
        h.append("<ul>")
        r.flags.filter { it.code !in LOCATED }.forEach { h.append("<li><b>${esc(it.title)}.</b> ${esc(it.text)}</li>") }
        r.events.forEachIndexed { n, ev -> h.append("<li><b>${n + 1}. ${esc(ev.title)}.</b> ${esc(ev.text)}</li>") }
        h.append("</ul>")
        if (e.note.isNotBlank()) h.append("<p class=\"muted\">Notatka pacjenta: ${esc(e.note)}</p>")
        h.append(svg(e, r, trueScale))
        if (r.events.isNotEmpty()) h.append("<p class=\"muted\" style=\"margin:2px 0 0\">Numery na wykresie odpowiadają zdarzeniom z listy powyżej; pomarańczowe pasy wskazują miejsce przedwczesnego uderzenia lub przerwy w rytmie, szare tło to zakłócenia pominięte w analizie.</p>")
        if (e.durationS > 30.5) h.append("<p class=\"muted\">Na wykresie są pierwsze 30 s zapisu; wskaźniki obejmują cały zapis.</p>")
        return h.append("</div>").toString()
    }

    fun html(list: List<Ecg>): String {
        val h = StringBuilder()
        h.append("<h3>EKG z zegarka (jedno odprowadzenie)</h3>")
        h.append("<p class=\"muted\" style=\"margin:0 0 6px\">Zapis z zegarka lub opaski, wczytany przez pacjenta z wydruku PDF; to nie jest badanie EKG w gabinecie. Wskaźniki są liczone przez aplikację orientacyjnie (rytm i odstępy między uderzeniami), bez oceny odstępu QT, odcinka ST ani niedokrwienia. Trójkąty oznaczają wykryte uderzenia, szare tło to zakłócenia pominięte w analizie.</p>")
        for (e in list) h.append(entry(e, EcgAnalysis.analyze(e), heading = true, trueScale = false))
        h.append("<p class=\"muted\" style=\"margin:6px 0 0\">Metoda: ${esc(EcgInfo.SHORT)}</p>")
        return h.toString()
    }

    /** Samodzielny dokument jednego zapisu do wydruku lub PDF: A4 poziomo, przebieg w skali papieru (25 mm/s i 10 mm/mV), wyniki ze zdarzeniami i opis metody. */
    fun document(s: AppState, e: Ecg, today: java.time.LocalDate, now: java.time.LocalDateTime): String {
        val r = EcgAnalysis.analyze(e)
        val b = StringBuilder()
        b.append("<h2 class=\"rp-title\">EKG z zegarka</h2>")
        b.append("<div class=\"rp-meta\">Pacjent: <b>${esc(s.patient.ifEmpty { "bez imienia" })}</b><br>Zapis z ${plDate(e.date)} ${e.time.hhmm()}, czas trwania ${"%.0f".format(java.util.Locale.ROOT, e.durationS)} s. Przebieg w skali papieru: 25 mm/s i 10 mm/mV (wydruk A4 poziomo, 100% skali).</div>")
        b.append(entry(e, r, heading = false, trueScale = true))
        b.append("<p class=\"muted\" style=\"margin:8px 0 0\">Metoda: ${esc(EcgInfo.SHORT)}</p>")
        b.append("<p class=\"rp-foot\">Wygenerowano ${plDate(today)} ${now.toLocalTime().hhmm()}. Przebieg odtworzony przez aplikację z wydruku PDF zegarka (przybliżenie z błędem rzędu 0,05 mV); to nie jest badanie EKG ani diagnoza.</p>")
        return "<!doctype html><html lang=\"pl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>${DoctorReport.CSS}@page{size:A4 landscape;margin:10mm}</style></head><body>$b</body></html>"
    }
}

package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime

data class Period(val from: LocalDate, val to: LocalDate) {
    companion object {
        /** [days] = 0 oznacza cały okres od pierwszych danych. [custom] ma pierwszeństwo. */
        fun resolve(days: Int, custom: Pair<LocalDate?, LocalDate?>?, today: LocalDate, first: LocalDate): Period {
            var r = if (custom != null && (custom.first != null || custom.second != null)) Period(custom.first ?: first, custom.second ?: today)
            else if (days == 0) Period(first, today) else Period(today.minusDays((days - 1).toLong()), today)
            if (r.from > r.to) r = Period(r.to, r.from)
            return r
        }
    }
}

/** Raport dla lekarza jako dokument HTML (podgląd w aplikacji i wydruk/PDF). */
object DoctorReport {
    const val CSS = """
:root{--ink:#000;--ink-2:#222;--ink-3:#444;--line:#bdbdbd;--accent:#0B6B58;--accent-soft:#DDEEE8;--warn:#7a4800;--danger:#B3261E;--surface:#fff;
  --mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
*{box-sizing:border-box}
body{margin:0;padding:16px;background:#fff;color:var(--ink);font:13px/1.45 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;-webkit-print-color-adjust:exact;print-color-adjust:exact}
h2.rp-title{margin:0 0 4px;font-size:19px;font-weight:650}
.rp-meta{font-size:13px;color:var(--ink-2);line-height:1.6}
h3{margin:22px 0 8px;font-size:14px;font-weight:650}
.muted{font-size:12.5px;color:var(--ink-3)}
.tbl{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:12.5px}
th{text-align:left;font-weight:600;color:var(--ink-2);padding:6px 6px 6px 0;border-bottom:1px solid var(--ink-3);white-space:nowrap}
td{padding:6px 6px 6px 0;border-bottom:1px solid #ddd;vertical-align:top}
tr{break-inside:avoid}
.num{font-family:var(--mono);font-variant-numeric:tabular-nums;white-space:nowrap}
ul{margin:0;padding-left:18px;font-size:12.5px;color:var(--ink-2)}
li{margin-bottom:6px}
.rp-foot{margin-top:18px;font-size:11.5px;color:var(--ink-3)}
.rp-sum{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-bottom:10px}
.rp-sum b{display:block;font:600 18px var(--mono)}
.rp-sum span{font-size:11.5px;color:var(--ink-3)}
.rp-lab{margin:14px 0 4px;font-size:13px;font-weight:650}
.legend{display:flex;flex-wrap:wrap;gap:4px 16px;font-size:12px;color:var(--ink-2);margin-bottom:6px}
.legend span{display:inline-flex;align-items:center;gap:6px}
.sw{width:10px;height:10px;border-radius:50%;display:inline-block}
.sw.sys{background:var(--accent)}
.sw.dia{border:2px solid var(--ink-3);background:#fff}
.chart{width:100%;height:auto;display:block}
.chart .gr{stroke:#ddd;stroke-width:1}
.chart .sepv{stroke:#ddd;stroke-width:1;stroke-dasharray:2 3}
.chart .ax{fill:var(--ink-3);font:10px var(--mono)}
.chart .l-sys{fill:none;stroke:var(--accent);stroke-width:2;stroke-linejoin:round}
.chart .l-dia{fill:none;stroke:var(--ink-3);stroke-width:2;stroke-linejoin:round}
.chart .p-sys{fill:var(--accent);stroke:#fff;stroke-width:1.5}
.chart .p-dia{fill:#fff;stroke:var(--ink-3);stroke-width:2}
.chart .band{fill:var(--accent-soft)}
.chart .bl{stroke:var(--accent);stroke-width:1;stroke-dasharray:3 3;opacity:.7}
.chart .l-lab{fill:none;stroke:var(--accent);stroke-width:2;stroke-linejoin:round}
.chart .p-lab{fill:var(--accent);stroke:#fff;stroke-width:1.5}
.chart .p-lab.out{fill:var(--danger)}
.chart .vl{fill:#000;font:600 10px var(--mono)}
@page{size:A4;margin:14mm}
@media print{body{padding:0;font-size:12px}}
"""

    fun document(body: String): String =
        "<!doctype html><html lang=\"pl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>$CSS</style></head><body>$body</body></html>"

    fun html(s: AppState, per: Period, today: LocalDate, now: LocalDateTime): String {
        val c = s.settings
        val P = c.rp
        val list = s.bp.filter { it.date >= per.from && it.date <= per.to }.sortedWith(bpOrder)
        val st = bpStats(list)
        val tt = ArrayList<String>()
        if (P.bp) tt.add("ciśnienia")
        if (P.meds) tt.add("przyjmowania leków")
        val h = StringBuilder()
        h.append("<h2 class=\"rp-title\">").append(if (tt.isNotEmpty()) "Dziennik " + tt.joinToString(" i ") else if (P.labs) "Wyniki badań" else "Raport dla lekarza").append("</h2>")
        h.append("<div class=\"rp-meta\">Pacjent: <b>${esc(s.patient.ifEmpty { "bez imienia" })}</b><br>Okres: ${plDate(per.from)} do ${plDate(per.to)}")
        if (c.reportNote.isNotEmpty()) h.append("<br>").append(esc(c.reportNote))
        h.append("</div>")
        if (!P.any) h.append("<p class=\"muted\">Nie wybrano żadnych sekcji. Zaznacz, co ma się znaleźć w raporcie.</p>")

        if (P.bp) {
            h.append("<h3>Pomiary ciśnienia</h3>")
            if (list.isEmpty()) h.append("<p class=\"muted\">Brak pomiarów w wybranym okresie.</p>")
            else {
                h.append("<div class=\"rp-sum\"><div><b>${st.s}/${st.d}</b><span>średnie, mmHg</span></div><div><b>${st.n}</b><span>pomiarów</span></div><div><b>${st.p?.toString() ?: "brak"}</b><span>średnie tętno</span></div></div>")
                h.append("<p class=\"muted\" style=\"margin:0 0 10px\">Skurczowe ${st.minS}-${st.maxS}, rozkurczowe ${st.minD}-${st.maxD}.")
                if (st.rano.n > 0) h.append(" Rano średnio ${st.rano.s}/${st.rano.d} (${st.rano.n}).")
                if (st.wieczor.n > 0) h.append(" Wieczorem średnio ${st.wieczor.s}/${st.wieczor.d} (${st.wieczor.n}).")
                h.append("</p><div class=\"legend\"><span><i class=\"sw sys\"></i>skurczowe</span><span><i class=\"sw dia\"></i>rozkurczowe</span></div>")
                bpChart(list)?.let { h.append(bpChartSvg(it)) }
                h.append("<div class=\"tbl\"><table style=\"margin-top:12px\"><thead><tr><th>Data</th><th>Godz.</th><th>Ciśnienie</th><th>Tętno</th><th>Pora</th><th>Uwagi</th></tr></thead><tbody>")
                list.forEach { r ->
                    val f = c.bpFlag(r)
                    h.append("<tr><td class=\"num\">${shortDate(r.date)}</td><td class=\"num\">${r.time.hhmm()}</td><td class=\"num\"><b>${r.sys}/${r.dia}</b></td><td class=\"num\">${r.pulse ?: ""}</td><td>${esc(r.ctx)}</td><td>")
                    if (f.isNotEmpty()) h.append("<b>$f</b>")
                    if (f.isNotEmpty() && r.note.isNotEmpty()) h.append(", ")
                    h.append(esc(r.note)).append("</td></tr>")
                }
                h.append("</tbody></table></div><p class=\"muted\" style=\"margin:6px 0 0\">Oznaczenia orientacyjne: ${c.thresholdText()}.</p>")
            }
        }

        if (P.labs) h.append(labsHtml(s, per, P.labsPeriod))

        class Row(val d: LocalDate, val s: DayStats, val r: DayRec)
        class Skip(val d: LocalDate, val name: String, val slot: String, val reason: String, val note: String)
        val rows = ArrayList<Row>(); val skips = ArrayList<Skip>()
        var planned = 0; var taken = 0; var fullDays = 0; var schedDays = 0; var hasM = false; var lateTaken = 0
        for (d in dayList(per.from, if (per.to < today) per.to else today)) {
            val ds = s.dayStats(d); val r = s.day(d)
            if (ds.hasMmol) hasM = true
            ds.items.forEach { x ->
                val sk = r.skipped[x.key]
                if (sk != null && x.key !in r.taken) skips.add(Skip(d, x.med.name, x.slot.name, sk.reason, sk.note))
                val tm = r.times[x.key]
                if (x.key in r.taken && tm != null && c.lateMin > 0 && tm.minutes > x.slot.time.minutes + c.lateMin) lateTaken++
            }
            if (ds.tot == 0 && r.note.isEmpty() && r.water == 0) continue
            planned += ds.tot; taken += ds.n
            if (ds.tot > 0) { schedDays++; if (ds.n == ds.tot) fullDays++ }
            rows.add(Row(d, ds, r))
        }
        if (P.meds) {
            h.append("<h3>Przyjmowanie leków</h3>")
            if (rows.isEmpty()) h.append("<p class=\"muted\">Brak danych o lekach w wybranym okresie.</p>")
            else {
                h.append("<div class=\"rp-sum\"><div><b>$fullDays/$schedDays</b><span>dni z kompletem</span></div><div><b>$taken/$planned</b><span>przyjęte dawki</span></div><div><b>${if (planned > 0) Math.round(taken * 100.0 / planned) else 0}%</b><span>zgodność z planem</span></div></div>")
                h.append("<p class=\"muted\" style=\"margin:0 0 10px\">Pominięte dawki: ${skips.size}.")
                if (c.lateMin > 0) h.append(" Przyjęte po czasie (ponad ${c.lateMin} min po porze): $lateTaken.")
                h.append("</p><div class=\"tbl\"><table><thead><tr><th>Data</th><th>Leki</th>")
                if (hasM) h.append("<th>Potas, mmol</th>")
                if (c.waterGoal > 0) h.append("<th>Woda</th>")
                h.append("<th>Notatka</th></tr></thead><tbody>")
                rows.forEach { x ->
                    h.append("<tr><td class=\"num\">${shortDate(x.d)}</td><td class=\"num\">${x.s.n}/${x.s.tot}</td>")
                    if (hasM) h.append("<td class=\"num\">").append(if (x.s.hasMmol) num(x.s.mmolTaken) + "/" + num(x.s.mmolPlan) else "").append("</td>")
                    if (c.waterGoal > 0) h.append("<td class=\"num\">${num(x.r.water * c.glassMl / 1000.0)} l</td>")
                    h.append("<td>${esc(x.r.note)}</td></tr>")
                }
                h.append("</tbody></table></div>")
            }
            if (skips.isNotEmpty()) {
                h.append("<h3>Pominięte dawki</h3><div class=\"tbl\"><table><thead><tr><th>Data</th><th>Lek</th><th>Pora</th><th>Powód</th></tr></thead><tbody>")
                skips.forEach { x -> h.append("<tr><td class=\"num\">${shortDate(x.d)}</td><td>${esc(x.name)}</td><td>${esc(x.slot)}</td><td>${esc(x.reason)}${if (x.note.isNotEmpty()) ", " + esc(x.note) else ""}</td></tr>") }
                h.append("</tbody></table></div>")
            }
        }

        val ms = s.meds.filter { it.phases.first().from <= per.to && (it.end == null || it.end >= per.from) }
        if (P.plan && ms.isNotEmpty()) {
            h.append("<h3>Leki i dawkowanie w okresie</h3><ul>")
            ms.forEach { m ->
                val ph = m.phases.filterIndexed { i, p -> val nx = m.phases.getOrNull(i + 1); p.from <= per.to && (nx == null || nx.from > per.from) }
                h.append("<li><b>${esc(m.name)}</b>")
                if (m.substance.isNotEmpty()) h.append(" (${esc(m.substance)})")
                h.append(": ").append(ph.joinToString("; ") { "od " + shortDate(it.from) + " " + esc(s.phaseText(m, it)) })
                if (m.end != null && m.end <= per.to) h.append("; zakończono ").append(plDate(m.end))
                h.append("</li>")
            }
            h.append("</ul>")
        }

        val evs = s.events.filter { it.date >= per.from && it.date <= per.to }.sortedBy { it.date }
        if (P.events && evs.isNotEmpty()) {
            h.append("<h3>Kontrole i wyniki</h3><ul>")
            evs.forEach { e ->
                val res = s.day(e.date).results[e.id]
                h.append("<li>${plDate(e.date)}: ${esc(e.title)}")
                if (e.field.isNotEmpty()) h.append(". ${esc(e.field)}: ").append(if (!res.isNullOrEmpty()) "<b>${esc(res)}</b>" else "brak wyniku")
                h.append("</li>")
            }
            h.append("</ul>")
        }
        h.append("<p class=\"rp-foot\">Wygenerowano ${plDate(today)} ${now.toLocalTime().hhmm()}. Dane wpisał pacjent, raport nie zawiera oceny klinicznej.</p>")
        return h.toString()
    }

    private fun labsHtml(s: AppState, per: Period, onlyPeriod: Boolean): String {
        val h = StringBuilder()
        var any = false
        val singles = ArrayList<Pair<Marker, Lab>>()
        fun head() {
            if (!any) {
                h.append("<h3>Wyniki badań</h3><p class=\"muted\" style=\"margin:0 0 6px\">${if (onlyPeriod) "Tylko wyniki z wybranego okresu." else "Wyniki z całej historii do końca okresu."} Zakresy odniesienia pochodzą z laboratorium lub są orientacyjne.</p>")
                any = true
            }
        }
        for (m in s.markers) {
            val all = s.labsOf(m.id, per.to).filter { !onlyPeriod || it.date >= per.from }
            if (all.isEmpty()) continue
            val list = all.takeLast(12)
            if (list.size < 2) { singles.add(m to list[0]); continue }
            head()
            h.append("<div class=\"rp-lab\">${esc(m.name)}${if (m.unit.isNotEmpty()) ", " + esc(m.unit) else ""}")
            if (rangeText(m).isNotEmpty()) h.append(" (zakres odniesienia ${esc(rangeText(m))})")
            h.append("</div>")
            labChart(list, m)?.let { h.append(labChartSvg(it, m.name)) }
            h.append("<div class=\"tbl\"><table><thead><tr><th>Data</th><th>Wynik</th><th>Ocena</th><th>Uwagi</th></tr></thead><tbody>")
            list.forEach { l ->
                val f = labFlag(l.value, m)
                h.append("<tr><td class=\"num\">${plDate(l.date)}</td><td class=\"num\"><b>${lnum(l.value)}</b></td><td>${if (f.isNotEmpty()) "<b>$f</b>" else "w zakresie"}</td><td>${esc(l.note)}</td></tr>")
            }
            h.append("</tbody></table></div>")
        }
        if (singles.isNotEmpty()) {
            head()
            val coll = java.text.Collator.getInstance(java.util.Locale("pl"))
            singles.sortWith { a, b -> if (a.second.date != b.second.date) b.second.date.compareTo(a.second.date) else coll.compare(a.first.name, b.first.name) }
            h.append("<div class=\"rp-lab\">Pojedyncze wyniki</div><div class=\"tbl\"><table><thead><tr><th>Data</th><th>Parametr</th><th>Wynik</th><th>Zakres</th><th>Ocena</th></tr></thead><tbody>")
            singles.forEach { (m, l) ->
                val f = labFlag(l.value, m)
                h.append("<tr><td class=\"num\">${plDate(l.date)}</td><td>${esc(m.name)}</td><td class=\"num\"><b>${lnum(l.value)}</b> ${esc(m.unit)}</td><td class=\"num\">${esc(rangeText(m).ifEmpty { "-" })}</td><td>${if (f.isNotEmpty()) "<b>$f</b>" else "w zakresie"}</td></tr>")
            }
            h.append("</tbody></table></div>")
        }
        return h.toString()
    }
}

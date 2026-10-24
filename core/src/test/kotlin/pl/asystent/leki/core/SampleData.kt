package pl.asystent.leki.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random

/**
 * Zmyślone dane przykładowe do wczytania w aplikacji (katalog `przyklady/` w repozytorium). Nic tu nie pochodzi z prawdziwych danych:
 * osoby, leki, pomiary, wyniki i dziennik cyklu są wygenerowane deterministycznie (stałe ziarno), a pliki w repo muszą zgadzać się z tym generatorem
 * (patrz [SampleDataTest]; ponowne wygenerowanie: zmienna środowiskowa WRITE_SAMPLES=1).
 */
object SampleData {
    val last: LocalDate = LocalDate.of(2026, 10, 23)
    private val exportedAt: Instant = Instant.parse("2026-10-23T12:00:00Z")

    private fun time(r: Random, base: LocalTime, spread: Int) = base.plusMinutes((r.nextInt(spread * 2 + 1) - spread).toLong()).withSecond(0).withNano(0)

    private fun fillDays(s0: AppState, from: LocalDate, seed: Long): AppState {
        val r = Random(seed)
        var s = s0
        var d = from
        while (!d.isAfter(last)) {
            val items = s.scheduleFor(d)
            val today = d
            var rec = DayRec(water = 4 + r.nextInt(7))
            for (x in items) {
                val p = r.nextDouble()
                rec = when {
                    p < 0.92 -> rec.copy(taken = rec.taken + x.key, times = rec.times + (x.key to time(r, x.slot.time, 40)))
                    p < 0.95 -> rec.copy(skipped = rec.skipped + (x.key to Skipped("Zapomniano")))
                    else -> rec
                }
            }
            if (r.nextInt(14) == 0) rec = rec.copy(note = listOf("Dłuższy spacer", "Gorszy sen", "Dużo kawy", "Wizyta kontrolna")[r.nextInt(4)])
            s = s.updateDay(today) { rec }
            d = d.plusDays(1)
        }
        return s
    }

    private fun bpSeries(from: LocalDate, seed: Long): List<Bp> {
        val r = Random(seed)
        val out = ArrayList<Bp>()
        var n = 0
        var d = from
        while (!d.isAfter(last)) {
            if (r.nextInt(10) < 7) {
                n++
                val sys = 122 + r.nextInt(14) + if (r.nextInt(9) == 0) 10 else 0
                val dia = 76 + r.nextInt(9) + if (sys > 138) 4 else 0
                out.add(Bp("b$n", d, time(r, LocalTime.of(8, 0), 40), sys, dia, 62 + r.nextInt(14), "rano", ""))
            }
            if (r.nextInt(10) < 4) {
                n++
                val sys = 118 + r.nextInt(14)
                out.add(Bp("b$n", d, time(r, LocalTime.of(20, 0), 50), sys, 74 + r.nextInt(9), 64 + r.nextInt(12), "wieczór", ""))
            }
            d = d.plusDays(1)
        }
        // jeden wysoki pomiar po wysiłku i kontrolny po kilku minutach (pokazuje oznaczenia i wskazówkę bezpieczeństwa)
        val hi = last.minusDays(21)
        out.add(Bp("b${n + 1}", hi, LocalTime.of(18, 5), 184, 112, 96, "inne", "po wysiłku"))
        out.add(Bp("b${n + 2}", hi, LocalTime.of(18, 20), 152, 94, 82, "inne", "kontrolnie po 15 minutach"))
        return out
    }

    private val extraMarkers = listOf(
        Marker("tsh", "TSH", "µIU/ml", 0.27, 4.2, "Wątroba i tarczyca"),
        Marker("glukoza", "Glukoza na czczo", "mmol/l", 3.9, 5.5, "Nerki i metabolizm"),
    )

    fun personOne(): AppState {
        val first = last.minusDays(83)
        val all = Defaults.allDow
        val meds = listOf(
            Med("lek-a", "Lek A", "Substancja X", "Przykładowy lek stały", "tabl.", 0, 0.0, null,
                listOf(Phase(first.minusDays(30), mapOf("morning" to 1.0, "dinner" to 1.0), all)), stock = 180.0, stockDate = first),
            Med("lek-b", "Lek B", "Substancja Y", "Dawka zmieniona w trakcie (historia zostaje)", "tabl.", 0, 0.0, null,
                listOf(Phase(first.minusDays(30), mapOf("morning" to 0.5), all), Phase(last.minusDays(35), mapOf("morning" to 1.0), all)), stock = 70.0, stockDate = first),
            Med("wit-z", "Witamina Z", "", "Trzy razy w tygodniu", "kaps.", 0, 0.0, null,
                listOf(Phase(first.minusDays(30), mapOf("lunch" to 1.0), listOf(1, 3, 5)))),
        )
        val labDates = listOf(first.plusDays(5), first.plusDays(45), last.minusDays(3))
        val v = listOf(
            "potas" to listOf(4.3, 4.5, 4.1), "sod" to listOf(139.0, 137.0, 141.0), "magnez" to listOf(0.82, 0.78, 0.68),
            "tsh" to listOf(2.1, 2.6, 1.9), "glukoza" to listOf(5.0, 5.3, 4.9),
        )
        var n = 0
        val labs = v.flatMap { (m, xs) -> xs.mapIndexed { i, x -> n++; Lab("l$n", labDates[i], m, x) } }
        val base = AppState(
            patient = "Jan Przykładowy", created = first.minusDays(30), rules = "Dane przykładowe, zmyślone. Można je usunąć w Ustawienia → Kopie zapasowe i dane.",
            meds = meds, markers = Defaults.markers() + extraMarkers, labs = labs, bp = bpSeries(first, 101),
            events = listOf(
                Event("e1", last.plusDays(9), "Kontrola u lekarza (przykład)", "Zabrać wyniki i listę leków"),
                Event("e2", last.plusDays(30), "Badanie potasu (przykład)", "Rano, na czczo", "wynik", "potas"),
            ),
        )
        return fillDays(base, first, 11)
    }

    fun personTwo(): AppState {
        val first = last.minusDays(55)
        val all = Defaults.allDow
        val meds = listOf(
            Med("lek-c", "Lek C", "Substancja V", "", "tabl.", 0, 0.0, null, listOf(Phase(first.minusDays(10), mapOf("morning" to 1.0), all)), stock = 70.0, stockDate = first),
            Med("zel", "Żelazo (przykład)", "", "Po obiedzie", "tabl.", 0, 0.0, null, listOf(Phase(first.minusDays(10), mapOf("lunch" to 1.0), all))),
        )
        val d1 = first.plusDays(10); val d2 = last.minusDays(5)
        val labs = listOf(
            Lab("l1", d1, "tsh", 1.8), Lab("l2", d2, "tsh", 2.2), Lab("l3", d1, "glukoza", 4.8), Lab("l4", d2, "glukoza", 5.1),
        )
        val base = AppState(
            patient = "Ola Przykładowa", created = first.minusDays(10), meds = meds, markers = Defaults.markers() + extraMarkers, labs = labs,
            settings = Settings(cyklOn = true, bpOn = false), cykl = CyklData(cyklDiary(), ""),
        )
        return fillDays(base, first, 22)
    }

    /** Dziennik cyklu w formacie modułu: 8 cykli po 27–31 dni, ostatni w toku (okres spodziewany pod koniec października 2026). */
    fun cyklDiary(): String {
        val lens = listOf(29, 28, 31, 27, 30, 29, 28, 30)
        var start = LocalDate.of(2026, 2, 9)
        val r = Random(33)
        val sb = StringBuilder("{\n  \"entries\": [\n")
        var first = true
        fun add(type: String, date: LocalDate, extra: String = "") {
            if (!first) sb.append(",\n"); first = false
            sb.append("    {\"type\": \"$type\", \"date\": \"$date\"$extra}")
        }
        val starts = ArrayList<LocalDate>()
        for (l in lens) { starts.add(start); start = start.plusDays(l.toLong()) }
        starts.add(start)    // początek ostatniego cyklu (w toku)
        for ((i, s) in starts.withIndex()) {
            add("period_start", s)
            val flows = listOf(3, 3, 2, 2, 1)
            for ((k, f) in flows.withIndex()) add("flow", s.plusDays(k.toLong()), ", \"value\": $f")
            add("period_end", s.plusDays(4))
            add("pain", s, ", \"value\": ${1 + r.nextInt(3)}")
            if (i < lens.size) {
                val len = lens[i]
                if (i % 2 == 0) add("lh", s.plusDays((len - 15).toLong()), ", \"result\": \"pos\"")
                if (i % 3 == 1) add("spotting", s.plusDays((len - 14).toLong()))
                add("sex", s.plusDays((len - 17).toLong())); add("protected", s.plusDays((len - 17).toLong()))
                if (i % 2 == 1) add("sex", s.plusDays((len - 9).toLong()))
            }
        }
        sb.append("\n  ],\n  \"cycles\": {},\n  \"settings\": {\"ageBand\": \"26-41\"}\n}\n")
        return sb.toString()
    }

    fun labsFile(): String {
        val d = listOf(last.minusDays(60), last.minusDays(30), last.minusDays(2))
        val rows = ArrayList<String>()
        var n = 0
        fun add(m: String, i: Int, v: Double) { n++; rows.add("    {\"marker\": \"$m\", \"date\": \"${d[i]}\", \"value\": $v}") }
        listOf(4.4, 4.6, 4.2).forEachIndexed { i, v -> add("potas", i, v) }
        listOf(1.7, 2.4, 2.0).forEachIndexed { i, v -> add("tsh", i, v) }
        listOf(14.2, 13.9, 14.4).forEachIndexed { i, v -> add("hemoglobina", i, v) }
        listOf(88.0, 94.0, 97.0).forEachIndexed { i, v -> add("glukoza_mg", i, v) }
        return """{
  "app": "asystent-lekow-wyniki",
  "markers": [
    {"id": "potas", "name": "Potas", "unit": "mmol/l", "lo": 3.5, "hi": 5.1, "grp": "Elektrolity"},
    {"id": "tsh", "name": "TSH", "unit": "µIU/ml", "lo": 0.27, "hi": 4.2, "grp": "Wątroba i tarczyca"},
    {"id": "hemoglobina", "name": "Hemoglobina", "unit": "g/dl", "lo": 12.0, "hi": 17.5, "grp": "Morfologia"},
    {"id": "glukoza_mg", "name": "Glukoza na czczo (mg/dl)", "unit": "mg/dl", "lo": 70.0, "hi": 99.0, "grp": "Nerki i metabolizm"}
  ],
  "labs": [
${rows.joinToString(",\n")}
  ]
}
"""
    }

    fun oneBackup(): String = Backup.exportAll(listOf(personOne()), exportedAt)
    fun twoBackup(): String = Backup.exportAll(listOf(personOne(), personTwo()), exportedAt)

    fun files(): Map<String, String> = linkedMapOf(
        "przyklad-jedna-osoba.json" to oneBackup(),
        "przyklad-dwie-osoby.json" to twoBackup(),
        "przyklad-dziennik-cyklu.json" to cyklDiary(),
        "przyklad-wyniki-badan.json" to labsFile(),
    )
}

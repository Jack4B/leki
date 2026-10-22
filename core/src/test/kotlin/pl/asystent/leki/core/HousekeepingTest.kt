package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dane zmyślone. */
class HousekeepingTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val now = LocalDateTime.of(2026, 10, 21, 9, 7)
    private val zone = ZoneId.of("Europe/Warsaw")
    private fun ms(d: LocalDate, t: LocalTime) = d.atTime(t).atZone(zone).toInstant().toEpochMilli()

    /** Lek A: 2 tabletki rano i 1 wieczorem (3 dziennie). */
    private fun base(stockLeft: Double? = null): AppState {
        val m = Med("a", "Lek A", unit = "tabl.", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 2.0, "dinner" to 1.0), Defaults.allDow)))
        var s = AppState(created = today.minusDays(5), meds = listOf(m))
        if (stockLeft != null) s = s.copy(meds = listOf(m.withStockLeft(s, stockLeft, today)))
        return s
    }

    // ---------- zapas ----------

    @Test fun stockCountsDownTakenDosesAndForecastsRunOut() {
        var s = base(stockLeft = 30.0)
        assertEquals(30.0, s.stockLeft(s.meds[0], today))
        val f = s.stockForecast(s.meds[0], today)!!
        // 30 tabletek, 3 dziennie: dziś rano 2 + wieczorem 1 (3), potem 9 pełnych dni; dziesiątego dnia (dzień 10) już brakuje
        assertEquals(10, f.daysLeft)
        assertEquals(today.plusDays(10), f.runsOutOn)
        // odhaczenie dzisiejszej dawki zmniejsza zapas, ale nie zmienia daty końca (to samo zużycie)
        s = s.toggleDose(today, "a|morning", today, now)
        assertEquals(28.0, s.stockLeft(s.meds[0], today))
        assertEquals(10, s.stockForecast(s.meds[0], today)!!.daysLeft)
    }

    @Test fun enteringStockTodayAccountsForDosesAlreadyTaken() {
        // użytkownik odhacza poranną dawkę (2 tabl.), potem liczy opakowanie: zostało 20; zapis ma dać dokładnie 20
        var s = base().toggleDose(today, "a|morning", today, now)
        val m = s.meds[0].withStockLeft(s, 20.0, today)
        s = s.copy(meds = listOf(m))
        assertEquals(20.0, s.stockLeft(s.meds[0], today))
        assertEquals(22.0, m.stock)
        // wieczorna dawka odejmuje od 20
        s = s.toggleDose(today, "a|dinner", today, now)
        assertEquals(19.0, s.stockLeft(s.meds[0], today))
        // następnego dnia odejmują się dawki z tamtego dnia
        val tomorrow = today.plusDays(1)
        val s2 = s.toggleDose(tomorrow, "a|morning", today, now)
        assertEquals(17.0, s2.stockLeft(s2.meds[0], tomorrow))
    }

    @Test fun stockForecastEdgeCases() {
        // brak zapasu: brak prognozy
        assertNull(base().stockForecast(base().meds[0], today))
        // zapas niewystarczający na dzisiejsze dawki
        val low = base(stockLeft = 2.0)
        assertEquals(0, low.stockForecast(low.meds[0], today)!!.daysLeft)
        // zero
        val zero = base(stockLeft = 0.0)
        assertEquals(0, zero.stockForecast(zero.meds[0], today)!!.daysLeft)
        // lek zakończony: nic nie zużywa, więc nie ma daty końca
        val ended = base(stockLeft = 5.0)
        val e = ended.copy(meds = listOf(ended.meds[0].copy(end = today.minusDays(1))))
        assertNull(e.stockForecast(e.meds[0], today)!!.daysLeft)
        // dawki pominięte dziś nie liczą się do zużycia
        var sk = base(stockLeft = 3.0)
        sk = sk.skipDose(today, "a|morning", "Brak", "")
        assertEquals(3.0, sk.stockLeft(sk.meds[0], today))
        assertEquals(1, sk.stockForecast(sk.meds[0], today)!!.daysLeft)   // wieczorem 1 tabl., jutro rano 2 (zostaje 0), potem brak: wieczorem jutro
    }

    @Test fun lowStockListsMedsUnderTheWarningThreshold() {
        val s = base(stockLeft = 15.0)       // starczy na 5 dni
        assertEquals(1, s.lowStock(today, warnDays = 7).size)
        assertTrue(s.lowStock(today, warnDays = 4).isEmpty())
        assertTrue(s.lowStock(today, warnDays = 0).isEmpty())
        assertTrue(stockText(s.stockForecast(s.meds[0], today)!!, "tabl.").contains("starczy na około 5 dni"))
    }

    @Test fun stockSurvivesBackupRoundTripAndOldFilesStayLoadable() {
        val s = base(stockLeft = 12.0).copy(settings = Settings(stockWarnDays = 5, backupRemindDays = 14))
        val back = Backup.parseState(Backup.serialize(s))
        assertEquals(s.meds[0].stock, back.meds[0].stock)
        assertEquals(today, back.meds[0].stockDate)
        assertEquals(5, back.settings.stockWarnDays); assertEquals(14, back.settings.backupRemindDays)
        // plik z wcześniejszej wersji (bez zapasu i bez nowych ustawień): domyślne wartości
        val old = Fixtures.state
        assertTrue(old.meds.all { it.stock == null })
        assertEquals(7, old.settings.stockWarnDays); assertEquals(30, old.settings.backupRemindDays)
        // uszkodzone pola zapasu nie psują wczytywania
        val broken = """{"v":3,"days":{},"meds":[{"id":"a","name":"A","phases":[{"from":"2026-01-01","doses":{"morning":1},"dow":[0,1,2,3,4,5,6]}],"stock":-5,"stockDate":"zle"}]}"""
        val b = Backup.parseState(broken)
        assertNull(b.meds[0].stock); assertNull(b.meds[0].stockDate)
    }

    // ---------- przypomnienia systemowe ----------

    @Test fun lowStockReminderIsScheduledForTheDayItBecomesDue() {
        val nowMs = ms(today, LocalTime.of(7, 0))
        // starczy na 12 dni, ostrzeżenie przy 7: przypomnienie za 5 dni o 9:00
        val s = base(stockLeft = 36.0).copy(settings = Settings(stockWarnDays = 7, backupRemindDays = 0))
        val r = s.housekeepingReminders(zone, nowMs, today).single()
        assertEquals(REMINDER_STOCK, r.id)
        assertEquals(ms(today.plusDays(5), LocalTime.of(9, 0)), r.atMillis)
        assertTrue(r.body.contains("Lek A") && r.body.contains("starczy na około 7 dni"), r.body)
        // już mało: dziś o 9:00, a po 9:00 jutro (codzienne przypominanie)
        val low = base(stockLeft = 6.0).copy(settings = Settings(stockWarnDays = 7, backupRemindDays = 0))
        assertEquals(ms(today, LocalTime.of(9, 0)), low.housekeepingReminders(zone, nowMs, today).single().atMillis)
        assertEquals(ms(today.plusDays(1), LocalTime.of(9, 0)), low.housekeepingReminders(zone, ms(today, LocalTime.of(10, 0)), today).single().atMillis)
        // wyłączone ostrzeżenie
        assertTrue(low.copy(settings = Settings(stockWarnDays = 0, backupRemindDays = 0)).housekeepingReminders(zone, nowMs, today).isEmpty())
    }

    @Test fun backupReminderAppearsWhenTheLastBackupIsOldAndNeverForAnEmptyApp() {
        val nowMs = ms(today, LocalTime.of(7, 0))
        val s = base().copy(created = today.minusDays(100), lastBackup = today.minusDays(40), settings = Settings(backupRemindDays = 30, stockWarnDays = 0))
        val r = s.housekeepingReminders(zone, nowMs, today).single()
        assertEquals(REMINDER_BACKUP, r.id)
        assertEquals(ms(today, LocalTime.of(10, 0)), r.atMillis)
        // jeszcze nie czas: zaplanowane na dzień, w którym minie próg
        val fresh = s.copy(lastBackup = today.minusDays(10))
        assertEquals(ms(today.plusDays(20), LocalTime.of(10, 0)), fresh.housekeepingReminders(zone, nowMs, today).single().atMillis)
        // brak kopii od zawsze: liczy się od założenia danych
        assertEquals(ms(today.plusDays(25), LocalTime.of(10, 0)), base().copy(settings = Settings(backupRemindDays = 30, stockWarnDays = 0)).housekeepingReminders(zone, nowMs, today).single().atMillis)
        // pusta aplikacja nie przypomina o kopii
        assertTrue(AppState(created = today.minusDays(100), settings = Settings(backupRemindDays = 30)).housekeepingReminders(zone, nowMs, today).isEmpty())
        // wyłączone
        assertTrue(s.copy(settings = Settings(backupRemindDays = 0, stockWarnDays = 0)).housekeepingReminders(zone, nowMs, today).isEmpty())
    }

    @Test fun housekeepingRemindersWorkWithoutDoseReminders() {
        val nowMs = ms(today, LocalTime.of(7, 0))
        val s = base(stockLeft = 6.0).copy(settings = Settings(remindOn = false, stockWarnDays = 7, backupRemindDays = 0))
        assertEquals(listOf(REMINDER_STOCK), s.reminders(zone, nowMs, today).map { it.id })
        val on = s.copy(settings = s.settings.copy(remindOn = true))
        assertTrue(on.reminders(zone, nowMs, today).size > 1)
        assertTrue(on.reminders(zone, nowMs, today).any { it.id == REMINDER_STOCK })
    }

    // ---------- CSV ----------

    @Test fun csvFieldsAreQuotedAndFormulasNeutralised() {
        assertEquals("abc", Csv.field("abc"))
        assertEquals("\"a;b\"", Csv.field("a;b"))
        assertEquals("\"a\"\"b\"", Csv.field("a\"b"))
        assertEquals("'=SUM(A1)", Csv.field("=SUM(A1)"))
        assertEquals("'-1+2", Csv.field("-1+2"))
        assertEquals("a b", Csv.field("a\nb"))
    }

    @Test fun csvExportsBpLabsDosesAndEcg() {
        val s = Fixtures.state
        val bp = Csv.bp(s)
        assertTrue(bp.startsWith("﻿Data;Godzina;Skurczowe;Rozkurczowe;Tętno;Okoliczności;Notatka\r\n"))
        assertEquals(s.bp.size + 1, bp.trim().split("\r\n").size)
        val first = s.bp.sortedWith(compareBy<Bp> { it.date }.thenBy { it.time }).first()
        assertTrue(bp.contains("${first.date};${first.time.hhmm()};${first.sys};${first.dia};"))
        val labs = Csv.labs(s)
        assertEquals(s.labs.size + 1, labs.trim().split("\r\n").size)
        assertTrue(labs.contains(","), "wartości z przecinkiem dziesiętnym") // np. 4,3
        val doses = Csv.doses(s, Fixtures.today.minusDays(6), Fixtures.today, Fixtures.today)
        assertTrue(doses.contains("przyjęto") || doses.contains("brak odhaczenia"))
        assertTrue(doses.startsWith("﻿Data;Pora;Lek;"))
        // okres ogranicza wiersze
        val one = Csv.bp(s, first.date, first.date)
        assertTrue(one.trim().split("\r\n").size in 2..s.bp.size + 1)
        // EKG: pusta lista to sam nagłówek
        assertEquals(1, Csv.ecg(s).trim().split("\r\n").size)
    }

    // ---------- trend EKG i blokada ----------

    @Test fun ecgTrendIsSortedAndSummarised() {
        fun e(id: String, d: LocalDate, hr: Int) = Ecg(id, d, LocalTime.of(8, 0), watchHr = hr, fs = 125, cmv = List(125 * 2) { 0 })   // za krótki do analizy: tętno z zegarka
        val pts = EcgTrend.points(listOf(e("b", today, 80), e("a", today.minusDays(5), 62), e("c", today.minusDays(1), 71)))
        assertEquals(listOf("a", "c", "b"), pts.map { it.id })
        assertEquals(listOf(62.0, 71.0, 80.0), pts.map { it.hr })
        assertTrue(EcgTrend.summary(pts).startsWith("Tętno 62–80/min w 3 zapisach"))
        assertEquals("", EcgTrend.summary(pts.take(1)))
    }

    @Test fun appLockPolicy() {
        assertFalse(AppLock.shouldLock(false, 0, 10_000, 0))
        assertTrue(AppLock.shouldLock(true, null, 10_000, 1))
        assertTrue(AppLock.shouldLock(true, 9_900, 10_000, 0))              // „zaraz”
        assertFalse(AppLock.shouldLock(true, 9_900, 10_000, 1))             // 30 s: minęło 0,1 s
        assertTrue(AppLock.shouldLock(true, 0, 31_000, 1))
        assertFalse(AppLock.shouldLock(true, 0, 299_000, 2))
        assertTrue(AppLock.shouldLock(true, 0, 300_000, 2))
        assertTrue(AppLock.shouldLock(true, 5_000, 1_000, 2), "zegar cofnięty (np. restart): blokujemy")
        assertEquals(0, AppLock.graceSeconds(99))
    }

    @Test fun bpChartMarksReadingsAtOrAboveTheThreshold() {
        fun bp(i: Int, sys: Int, dia: Int) = Bp("b$i", today.minusDays(3L - i), LocalTime.of(8, 0), sys, dia)
        val list = listOf(bp(0, 120, 80), bp(1, 135, 80), bp(2, 128, 85), bp(3, 130, 79))
        val g = Trend.build(list, today.minusDays(3), today, 360f, 135, 85)!!
        assertEquals(listOf(false, true, true, false), g.readings.map { it.high })   // 135 skurczowe i 85 rozkurczowe to próg: podwyższone
        // dłuższy okres: średnie dzienne też są oznaczone
        val long = (0 until 60).map { Bp("l$it", today.minusDays(it.toLong()), LocalTime.of(8, 0), if (it < 10) 150 else 118, 80) }
        val gl = Trend.build(long, today.minusDays(59), today, 360f, 135, 85)!!
        assertFalse(gl.detailed)
        assertEquals(10, gl.days.count { it.high })
    }

    @Test fun stockOverviewListsTrackedMedsSoonestFirst() {
        val a = base(stockLeft = 60.0)
        val b = Med("b", "Lek B", unit = "tabl.", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 1.0), Defaults.allDow)))
        val s = a.copy(meds = a.meds + b.withStockLeft(a, 5.0, today) + Med("c", "Lek C", phases = listOf(Phase(today.minusDays(5), mapOf("morning" to 1.0), Defaults.allDow))))
        val rows = s.stockOverview(today)
        assertEquals(listOf("b", "a"), rows.map { it.med.id })          // C nie ma zapasu; B (5 tabl., 1 dziennie) kończy się przed A (60 tabl., 3 dziennie)
        assertEquals(5, rows[0].forecast.daysLeft); assertEquals(20, rows[1].forecast.daysLeft)
        // zakończony lek nie jest na liście
        val ended = s.copy(meds = s.meds.map { if (it.id == "b") it.copy(end = today.minusDays(1)) else it })
        assertEquals(listOf("a"), ended.stockOverview(today).map { it.med.id })
    }

    @Test fun stockLevelColoursByThreshold() {
        fun f(days: Int?) = StockForecast(10.0, days, null)
        assertEquals(StockLevel.Ok, stockLevel(f(30), 7))
        assertEquals(StockLevel.Ok, stockLevel(f(8), 7))
        assertEquals(StockLevel.Low, stockLevel(f(7), 7))
        assertEquals(StockLevel.Low, stockLevel(f(4), 7))
        assertEquals(StockLevel.Critical, stockLevel(f(3), 7))
        assertEquals(StockLevel.Critical, stockLevel(f(0), 7))
        // małe progi: krytyczne co najmniej od 1 dnia, nie więcej niż 3 dni
        assertEquals(StockLevel.Critical, stockLevel(f(1), 2)); assertEquals(StockLevel.Low, stockLevel(f(2), 2))
        assertEquals(StockLevel.Critical, stockLevel(f(3), 30)); assertEquals(StockLevel.Low, stockLevel(f(20), 30))
        // wyłączone ostrzeganie i brak prognozy: zielony
        assertEquals(StockLevel.Ok, stockLevel(f(0), 0)); assertEquals(StockLevel.Ok, stockLevel(f(null), 7))
    }

    // ---------- historia pogrupowana w okresy ----------

    private fun bpAt(d: LocalDate, sys: Int, dia: Int, id: String = "$d$sys") = Bp(id, d, LocalTime.of(8, 0), sys, dia, pulse = 60 + (sys % 10))

    @Test fun groupsByWeekAndMonthNewestFirst() {
        val list = listOf(bpAt(LocalDate.of(2026, 10, 21), 150, 95), bpAt(LocalDate.of(2026, 10, 20), 120, 80), bpAt(LocalDate.of(2026, 10, 14), 130, 82), bpAt(LocalDate.of(2026, 9, 30), 118, 76), bpAt(LocalDate.of(2026, 9, 2), 125, 80))
        val weeks = groupByPeriod(list, HistoryView.Weeks) { it.date }
        assertEquals(listOf("2026-10-19", "2026-10-12", "2026-09-28", "2026-08-31"), weeks.map { it.key })   // tygodnie od poniedziałku
        assertEquals(listOf(2, 1, 1, 1), weeks.map { it.items.size })
        val months = groupByPeriod(list, HistoryView.Months) { it.date }
        assertEquals(listOf("2026-10-01", "2026-09-01"), months.map { it.key })
        assertEquals(listOf(3, 2), months.map { it.items.size })
        assertEquals(5, groupByPeriod(list, HistoryView.Days) { it.date }.size)
        assertTrue(groupByPeriod(emptyList<Bp>(), HistoryView.Weeks) { it.date }.isEmpty())
    }

    @Test fun periodLabelsArePolish() {
        assertEquals("Październik 2026", periodLabel(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), HistoryView.Months))
        assertEquals("Styczeń 2027", periodLabel(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31), HistoryView.Months))
        assertEquals("19–25 października 2026", periodLabel(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), HistoryView.Weeks))
        assertEquals("28 wrz – 4 paź 2026", periodLabel(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4), HistoryView.Weeks))
        assertEquals("28 gru 2026 – 3 sty 2027", periodLabel(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3), HistoryView.Weeks))
        assertEquals("Środa 21 października", periodLabel(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21), HistoryView.Days))
    }

    @Test fun summariesCountThresholdsAndEcgFlags() {
        val c = Settings(hiSys = 135, hiDia = 85)
        val (text, warn) = bpGroupSummary(listOf(bpAt(LocalDate.of(2026, 10, 21), 150, 95), bpAt(LocalDate.of(2026, 10, 20), 120, 80)), c)
        assertEquals("2 pomiary · średnio 135/88 · tętno 60 · powyżej progu: 1", text)
        assertTrue(warn)
        val (ok, noWarn) = bpGroupSummary(listOf(bpAt(LocalDate.of(2026, 10, 20), 120, 80)), c)
        assertEquals("1 pomiar · średnio 120/80 · tętno 60", ok); assertFalse(noWarn)
        assertEquals("brak pomiarów", bpGroupSummary(emptyList(), c).first)
        fun e(id: String, hr: Int) = Ecg(id, LocalDate.of(2026, 10, 1), LocalTime.of(8, 0), watchHr = hr, fs = 125, cmv = List(125 * 2) { 0 })
        val ecgs = listOf(e("a", 62), e("b", 80))
        val (es, ew) = ecgGroupSummary(ecgs)
        val flagged = EcgTrend.points(ecgs).count { it.attention > 0 }
        assertTrue(es.startsWith("2 zapisy"), es)
        assertEquals(flagged > 0, ew)
        assertEquals(flagged > 0, es.contains("do omówienia: $flagged"), es)
        assertEquals("brak zapisów", ecgGroupSummary(emptyList()).first)
    }

    @Test fun labSummaryCountsRangesAndDays() {
        val k = Marker("k", "Potas", "mmol/l", lo = 3.5, hi = 5.5)
        val na = Marker("na", "Sód", "mmol/l", lo = 136.0, hi = 146.0)
        fun lab(id: String, d: Int, m: String, v: Double) = Lab(id, LocalDate.of(2026, 10, d), m, v)
        val (multi, warn) = labGroupSummary(listOf(lab("1", 18, "k", 4.3), lab("2", 18, "na", 134.0), lab("3", 20, "k", 4.1)), listOf(k, na))
        assertEquals("3 wyniki · dni badań: 2 · poza zakresem: 1", multi); assertTrue(warn)
        val (one, w1) = labGroupSummary(listOf(lab("1", 18, "k", 4.3), lab("2", 20, "k", 4.1)), listOf(k, na))
        assertEquals("2 wyniki · 4,1–4,3 mmol/l", one); assertFalse(w1)
        assertEquals("1 wynik", labGroupSummary(listOf(lab("1", 18, "k", 4.3)), listOf(k)).first)
        assertEquals("brak wyników", labGroupSummary(emptyList(), listOf(k)).first)
    }
}

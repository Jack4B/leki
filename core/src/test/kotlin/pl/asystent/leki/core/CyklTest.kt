package pl.asystent.leki.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Moduł „Cykl”: walidacja bazy zapisywanej przez stronę modułu i sekcja `cykl` w kopii zapasowej. */
class CyklTest {
    private val demo = javaClass.getResource("/fixtures/cykl-przyklad.json")!!.readText()
    private val empty = """{"entries":[],"cycles":{},"settings":{}}"""

    @Test fun acceptsOnlyRealDatabases() {
        assertTrue(Cykl.isDb(demo)); assertTrue(Cykl.isDb(empty))
        assertFalse(Cykl.isDb("")); assertFalse(Cykl.isDb("nie json")); assertFalse(Cykl.isDb("[]"))
        assertFalse(Cykl.isDb("""{"entries":"x"}""")); assertFalse(Cykl.isDb("""{"days":{}}"""))
        assertFalse(Cykl.isDb("[".repeat(100_000)))      // bardzo głębokie zagnieżdżenie nie wywala aplikacji
    }

    @Test fun storeRejectsForeignKeysAndBadValues() {
        val c = CyklData()
        assertEquals(demo, c.put(Cykl.KEY_DB, demo)!!.get(Cykl.KEY_DB))
        assertNull(c.put("inny_klucz", demo)); assertNull(c.put(Cykl.KEY_THEME, "dark"))
        assertNull(c.put(Cykl.KEY_DB, "{}")); assertNull(c.put(Cykl.KEY_DB, "x".repeat(Cykl.MAX_CHARS + 1)))
        assertNull(c.get(Cykl.KEY_DB)); assertNull(c.get("inny_klucz"))
        val both = c.put(Cykl.KEY_DB, demo)!!.put(Cykl.KEY_PREV, empty)!!
        assertEquals(empty, both.get(Cykl.KEY_PREV))
        assertEquals("", both.remove(Cykl.KEY_PREV)!!.prev); assertEquals(demo, both.remove(Cykl.KEY_PREV)!!.db)
        assertNull(both.remove("inny_klucz")); assertTrue(CyklData().isEmpty); assertFalse(both.isEmpty)
    }

    @Test fun summaryCountsEntriesAndPeriods() {
        val s = Cykl.summary(demo)!!
        assertEquals(10, s.periods); assertEquals(91, s.entries)
        assertEquals("2024-01-08", s.first); assertTrue(s.last!! > "2024-10-01")
        assertEquals(CyklSummary(0, 0, null, null), Cykl.summary(empty))
        assertNull(Cykl.summary("{}"))
        // daty spoza kalendarza (np. wpis z pliku z błędem) nie mogą wywalić ekranu Ustawień: są pomijane w podsumowaniu
        val bad = """{"entries":[{"type":"period_start","date":"2024-13-45"},{"type":"period_start","date":"2024-02-30"},{"type":"period_start","date":"2024-03-01"}]}"""
        assertEquals(CyklSummary(1, 1, "2024-03-01", "2024-03-01"), Cykl.summary(bad))
    }

    @Test fun recognisesModuleFilesVersusAppBackups() {
        assertTrue(Cykl.looksLikeDb(demo))
        assertFalse(Cykl.looksLikeDb(javaClass.getResource("/fixtures/state.json")!!.readText()))
        assertFalse(Cykl.looksLikeDb("""{"app":"asystent-lekow","entries":[]}"""))
    }

    @Test fun backupRoundTripKeepsWholeDatabase() {
        val st = AppState(settings = Settings(cyklOn = true), cykl = CyklData(demo, empty))
        val back = Backup.parseState(Backup.export(st))
        assertTrue(back.settings.cyklOn)
        assertEquals(Cykl.summary(demo), Cykl.summary(back.cykl.db))
        assertEquals(Cykl.summary(empty), Cykl.summary(back.cykl.prev))
        // dokładnie te same wpisy po zapisie i wczytaniu (kolejność też)
        val a = kotlinx.serialization.json.Json.parseToJsonElement(demo)
        val b = kotlinx.serialization.json.Json.parseToJsonElement(back.cykl.db)
        assertEquals(a, b)
        // zapis wewnętrzny (pamięć aplikacji) też
        assertEquals(a, kotlinx.serialization.json.Json.parseToJsonElement(Backup.parseState(Backup.serialize(st)).cykl.db))
    }

    @Test fun oldBackupsAndUnusedModuleStayUnchanged() {
        val old = Backup.parseState(javaClass.getResource("/fixtures/state.json")!!.readText())
        assertTrue(old.cykl.isEmpty); assertFalse(old.settings.cyklOn)
        val text = Backup.export(old)
        assertFalse("cykl" in text)                       // brak sekcji, gdy moduł nie ma danych
        assertFalse("cyklOn" in text)
        assertTrue(Backup.parseState(text).cykl.isEmpty)
    }

    @Test fun brokenSectionInBackupIsIgnoredNotFatal() {
        val base = Backup.serialize(AppState())
        val json = kotlinx.serialization.json.Json.parseToJsonElement(base) as kotlinx.serialization.json.JsonObject
        fun withCykl(raw: String): AppState {
            val m = LinkedHashMap(json); m["cykl"] = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            return Backup.parseState(kotlinx.serialization.json.JsonObject(m).toString())
        }
        assertTrue(withCykl("""{"db":"tekst","prev":42}""").cykl.isEmpty)
        assertTrue(withCykl("""[1,2]""").cykl.isEmpty)
        assertTrue(withCykl("""{"db":{"coś":1}}""").cykl.isEmpty)
        assertNotNull(withCykl("""{"db":{"entries":[]}}""").cykl.db.takeIf { it.isNotEmpty() })
    }

    @Test fun backupReminderCountsCykl() {
        val today = LocalDate.of(2026, 10, 24)
        val base = AppState(created = today.minusDays(30), lastBackup = null)
        assertNull(base.needBackup(today))
        assertNotNull(base.copy(cykl = CyklData(demo)).needBackup(today))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("luna_backup_2031-03-05.json", Cykl.fileName("luna_backup_2031-03-05.json"))
        assertEquals("passwd", Cykl.fileName("../../etc/passwd")); assertEquals("a_b.txt", Cykl.fileName("a b.txt"))
        assertEquals("cykl.json", Cykl.fileName("")); assertEquals("cykl.json", Cykl.fileName("...")); assertEquals(80, Cykl.fileName("x".repeat(200)).length)
    }

    @Test fun pageAssemblyKeepsModuleIntactAndHandlesDollars() {
        val html = "<!DOCTYPE html><html><head><title>Cykl</title></head><body>x \$1 \$&</body></html>"
        val out = Cykl.page(html, "a{b:c}", "var x = '\$&';")
        assertTrue(out.indexOf("Content-Security-Policy") < out.indexOf("var x = '\$&';"))
        assertTrue(out.indexOf("<title>Cykl</title>") < out.indexOf("a{b:c}") && out.indexOf("a{b:c}") < out.indexOf("</head>"))
        assertTrue(out.endsWith("<body>x \$1 \$&</body></html>")); assertTrue("connect-src blob: data:" in out)
        assertFailsWith<IllegalArgumentException> { Cykl.page("<html></html>", "", "") }
    }

    @Test fun readsFilesLikeTheModuleDoes() {
        val r = Cykl.readIncoming(demo)!!
        assertEquals(91, r.entries); assertEquals(0, r.skipped); assertEquals(Cykl.summary(demo), Cykl.summary(r.db))
        // sama tablica, BOM, data dd.mm.rrrr, wpisy bez typu lub ze złą datą pomijane
        val odd = "\uFEFF" + """[{"type":"period_start","date":"05.03.2031"},{"type":" sex ","date":"2031-03-07T10:00:00"},{"date":"2031-03-08"},{"type":"x","date":"2031-13-01"},null,{"type":"flow","date":"2031-03-05","value":2,"junk":1}]"""
        val o = Cykl.readIncoming(odd)!!
        assertEquals(3, o.entries); assertEquals(3, o.skipped)
        assertEquals(CyklSummary(3, 1, "2031-03-05", "2031-03-07"), Cykl.summary(o.db))
        assertFalse("junk" in o.db); assertTrue(""""type":"sex"""" in o.db)
        assertNull(Cykl.readIncoming("""{"entries":[{"type":"x"}]}""")); assertNull(Cykl.readIncoming("{}")); assertNull(Cykl.readIncoming("nie json"))
        assertNull(Cykl.readIncoming(javaClass.getResource("/fixtures/state.json")!!.readText()))   // kopia aplikacji to nie dziennik
    }

    @Test fun fieldsAndOddValuesBehaveLikeInJavaScript() {
        val r = Cykl.readIncoming("""[{"type":"pain","date":"2031-03-05","id":null,"value":2.0,"limits":"false"},{"type":"pain","date":"2031-03-06","id":7,"value":"3","text":"ą","result":"x","limits":0},{"type":"a","date":"2031-03-07","limits":true}]""")!!
        val e = (kotlinx.serialization.json.Json.parseToJsonElement(r.db) as kotlinx.serialization.json.JsonObject)["entries"] as kotlinx.serialization.json.JsonArray
        val first = e[0].toString(); val second = e[1].toString()
        assertFalse(""""id"""" in first)                    // id: null znika, jak w module
        assertTrue(""""limits":true""" in first)           // niepusty tekst "false" jest „prawdą” w JavaScript
        assertFalse("limits" in second); assertTrue(""""id":7""" in second && """"text":"ą"""" in second && """"result":"x"""" in second)
        assertTrue(""""limits":true""" in e[2].toString())
        assertEquals(3, r.entries)
    }

    @Test fun mergeKeysTreatEqualNumbersAsEqualAndDoNotCollapseDuplicatesInsideTheFile() {
        val cur = Cykl.readIncoming("""[{"type":"pain","date":"2031-03-05","value":2}]""")!!.db
        val inc = Cykl.readIncoming("""[{"type":"pain","date":"2031-03-05","value":2.0},{"type":"note","date":"2031-03-06","text":"a"},{"type":"note","date":"2031-03-06","text":"a"}]""")!!.db
        assertEquals(2, Cykl.merge(cur, inc)!!.added)           // 2 i 2.0 to ten sam wpis; dwa identyczne wpisy z pliku dodają się oba (jak w module)
        assertNotNull(Cykl.readIncoming("""[{"type":"a","date":"05.03.2031"}]"""))   // data dd.mm.rrrr jest przyjmowana niezależnie od języka telefonu
    }

    @Test fun mergeAddsOnlyMissingEntries() {
        val a = Cykl.readIncoming(demo)!!.db
        val m0 = Cykl.merge("", a)!!
        assertEquals(91, m0.added)
        val again = Cykl.merge(m0.db, a)!!
        assertEquals(0, again.added); assertEquals(91, Cykl.summary(again.db)!!.entries)
        val extra = Cykl.readIncoming("""[{"type":"note","date":"2031-01-02","text":"x"},{"type":"period_start","date":"2024-01-08"}]""")!!.db
        val m = Cykl.merge(a, extra)!!
        assertEquals(1, m.added); assertEquals(92, Cykl.summary(m.db)!!.entries)
        assertNull(Cykl.merge(a, "{}"))
        // ustawienia i cykle: pierwszeństwo ma obecna baza
        val cur = """{"entries":[],"cycles":{"a":1},"settings":{"x":"cur"}}"""
        val inc = """{"entries":[{"type":"note","date":"2031-01-02"}],"cycles":{"a":2,"b":3},"settings":{"x":"inc","y":"inc"}}"""
        val mm = Cykl.merge(cur, inc)!!.db
        assertTrue(""""x":"cur"""" in mm && """"y":"inc"""" in mm && """"a":1""" in mm && """"b":3""" in mm)
    }

    @Test fun exportKeepsEverythingAndIsReadable() {
        val text = Cykl.exportText(demo)
        assertTrue("\n" in text.trim()); assertEquals(Cykl.summary(demo), Cykl.summary(text))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(demo), kotlinx.serialization.json.Json.parseToJsonElement(text))
    }

    @Test fun backupOfSeveralPeopleKeepsEachOneSeparate() {
        val a = AppState(patient = "Osoba A", meds = listOf(Med(id = "m", name = "Lek A", phases = listOf(Phase(LocalDate.of(2031, 1, 1), mapOf("morning" to 1.0), Defaults.allDow)))))
        val b = AppState(patient = "Osoba B", settings = Settings(cyklOn = true), cykl = CyklData(demo))
        val text = Backup.exportAll(listOf(a, b))
        val all = Backup.parseAll(text)
        assertEquals(listOf("Osoba A", "Osoba B"), all.map { it.patient })
        assertEquals(1, all[0].meds.size); assertTrue(all[1].meds.isEmpty()); assertTrue(all[0].cykl.isEmpty)
        assertTrue(all[1].settings.cyklOn); assertEquals(Cykl.summary(demo), Cykl.summary(all[1].cykl.db))
        // starsza wersja aplikacji (parseState) widzi tylko pierwszą osobę, plik jednej osoby jest taki jak dawniej
        assertEquals("Osoba A", Backup.parseState(text).patient)
        assertFalse("profiles" in Backup.export(a))
        assertEquals(1, Backup.parseAll(Backup.export(a)).size)
        assertEquals(1, Backup.parseAll(javaClass.getResource("/fixtures/export.json")!!.readText()).size)
    }

    @Test fun brokenExtraPeopleAreSkippedAndCountIsLimited() {
        val base = Backup.export(AppState(patient = "X"))
        val root = kotlinx.serialization.json.Json.parseToJsonElement(base) as kotlinx.serialization.json.JsonObject
        val good = (kotlinx.serialization.json.Json.parseToJsonElement(Backup.export(AppState(patient = "Y"))) as kotlinx.serialization.json.JsonObject)["data"]!!
        val many = kotlinx.serialization.json.JsonArray(List(12) { kotlinx.serialization.json.JsonObject(mapOf("data" to good)) } + kotlinx.serialization.json.JsonObject(mapOf("data" to kotlinx.serialization.json.JsonPrimitive("x"))))
        val text = kotlinx.serialization.json.JsonObject(root + ("profiles" to many)).toString()
        val all = Backup.parseAll(text)
        assertEquals(Backup.MAX_PROFILES, all.size); assertEquals("X", all[0].patient)
    }

    @Test fun reportsMentionCycleSummaryOnlyWhenAskedAndNeverIntimateEntries() {
        val today = LocalDate.of(2031, 10, 22)
        val st = AppState(patient = "Osoba", settings = Settings(cyklOn = true), cykl = CyklData(demo))
        val b = Cykl.brief(demo, today)!!
        assertEquals(9, b.totalCycles)                                              // 10 początków okresu = 9 cykli
        assertEquals(LocalDate.of(2024, 10, 9), b.lastStart)
        val lines = Cykl.briefLines(b).joinToString("\n")
        assertTrue("Długości ostatnich cykli" in lines && "Mediana" in lines)
        assertFalse(Regex("sex|protected|him|masturb|stosun", RegexOption.IGNORE_CASE).containsMatchIn(lines))
        val off = AiReport.build(st, Period(today.minusDays(29), today), today, java.time.LocalDateTime.of(2031, 10, 22, 12, 0), "test")
        assertFalse("Dziennik cyklu" in off)
        val on = AiReport.build(st.copy(settings = st.settings.copy(ai = st.settings.ai.copy(cykl = true))), Period(today.minusDays(29), today), today, java.time.LocalDateTime.of(2031, 10, 22, 12, 0), "test")
        assertTrue("Dziennik cyklu" in on && "Długości ostatnich cykli" in on)
        val html = DoctorReport.html(st.copy(settings = st.settings.copy(rp = st.settings.rp.copy(cykl = true))), Period(today.minusDays(29), today), today, java.time.LocalDateTime.of(2031, 10, 22, 12, 0))
        assertTrue("Dziennik cyklu" in html)
        assertNull(Cykl.brief(empty, today)); assertNull(Cykl.brief("{}", today))
        assertEquals(0, Cykl.brief("""{"entries":[{"type":"period_start","date":"2031-10-01"}]}""", today)!!.cycles.size)
    }
}

package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logika magazynu osób (`Profiles`): odczyt z odtwarzaniem po uszkodzeniu, wczytanie kopii, dopisywanie osób z pliku, kolejność zapisu. Dane zmyślone. */
class ProfilesTest {
    private fun person(name: String, link: String = "", theme: ThemeMode = ThemeMode.Auto) =
        AppState(patient = name, settings = Settings(theme = theme, cyklFrom = link))

    private fun text(name: String) = Backup.serialize(person(name))

    /** Atrapa katalogu: nazwa pliku → treść; plik o treści null w mapie `bad` rzuca wyjątek przy odczycie. */
    private class Dir(val files: MutableMap<String, String> = LinkedHashMap(), val unreadable: Set<String> = emptySet()) {
        val quarantined = ArrayList<Pair<String, String>>()
        fun load() = Profiles.load(
            read = { n -> if (n in unreadable) throw java.io.IOException("błąd dysku") else files[n] },
            listNames = { files.keys },
            quarantine = { n, side -> quarantined.add(n to side); files[side] = files.getValue(n) },
        )
    }

    // ---------- nazwy plików ----------

    @Test fun fileNames() {
        assertEquals("state.json", Profiles.fileOf("p1"))
        assertEquals("state-p3.json", Profiles.fileOf("p3"))
        assertEquals("state.uszkodzony.json", Profiles.sideFile("p1"))
        assertEquals("state-p3.uszkodzony.json", Profiles.sideFile("p3"))
    }

    // ---------- lista osób ----------

    @Test fun indexParsesIdsAndActive() {
        val ix = Profiles.parseIndex("""{"active":"p3","ids":["p1","p3","p2"]}""")!!
        assertEquals(listOf("p1", "p3", "p2"), ix.ids)
        assertEquals("p3", ix.active)
    }

    @Test fun indexDropsBadIdsAndFallsBackToFirstActive() {
        val ix = Profiles.parseIndex("""{"active":"p9","ids":["p1","../x","p1","p2","state"]}""")!!
        assertEquals(listOf("p1", "p2"), ix.ids)
        assertEquals("p1", ix.active)
    }

    @Test fun emptyIndexMeansFirstPersonOnly() {
        val ix = Profiles.parseIndex("""{"ids":[]}""")!!
        assertEquals(listOf("p1"), ix.ids)
        assertEquals("p1", ix.active)
    }

    @Test fun corruptIndexIsNull() {
        assertNull(Profiles.parseIndex("{nie json"))
        assertNull(Profiles.parseIndex("""{"ids":[{"a":1}]}"""))
        assertNull(Profiles.parseIndex(""))
    }

    @Test fun idsFromFilesAreOrderedNumerically() {
        val ids = Profiles.idsFromFiles(listOf("state-p10.json", "state-p2.json", "state.json", "state-p3.uszkodzony.json", "profiles.json", "state-p3.json.bak", "state-p2.json"))
        assertEquals(listOf("p1", "p2", "p10"), ids)
    }

    // ---------- odczyt ----------

    @Test fun emptyDirectoryStartsWithOneEmptyPerson() {
        val l = Dir().load()
        assertEquals(listOf("p1"), l.states.map { it.first })
        assertEquals(AppState(), l.states.single().second)
        assertEquals("p1", l.active)
        assertTrue(l.recovered.isEmpty())
    }

    @Test fun readsAllPeopleInIndexOrder() {
        val d = Dir(linkedMapOf(
            "profiles.json" to """{"active":"p2","ids":["p1","p2"]}""",
            "state.json" to text("Ala"), "state-p2.json" to text("Bea"),
        ))
        val l = d.load()
        assertEquals(listOf("Ala", "Bea"), l.states.map { it.second.patient })
        assertEquals("p2", l.active)
        assertTrue(l.recovered.isEmpty())
    }

    @Test fun corruptIndexIsRebuiltFromPersonFiles() {
        val d = Dir(linkedMapOf(
            "profiles.json" to "{uszkodzone",
            "state.json" to text("Ala"), "state-p3.json" to text("Cyd"), "state-p2.json" to text("Bea"),
        ))
        val l = d.load()
        assertEquals(listOf("p1", "p2", "p3"), l.states.map { it.first })
        assertEquals(listOf("Ala", "Bea", "Cyd"), l.states.map { it.second.patient })
        assertEquals("p1", l.active)
        assertTrue(l.recovered.isEmpty(), "uszkodzona lista nie oznacza uszkodzonych osób")
    }

    @Test fun unreadableIndexIsRebuiltToo() {
        val d = Dir(linkedMapOf("profiles.json" to "{}", "state.json" to text("Ala"), "state-p2.json" to text("Bea")), unreadable = setOf("profiles.json"))
        assertEquals(listOf("p1", "p2"), d.load().states.map { it.first })
    }

    @Test fun corruptPersonFileIsQuarantinedAndStartsEmpty() {
        val d = Dir(linkedMapOf(
            "profiles.json" to """{"active":"p1","ids":["p1","p2"]}""",
            "state.json" to "to nie jest kopia", "state-p2.json" to text("Bea"),
        ))
        val l = d.load()
        assertEquals(listOf("p1" to "state.uszkodzony.json"), l.recovered)
        assertEquals(listOf("state.json" to "state.uszkodzony.json"), d.quarantined)
        assertEquals("to nie jest kopia", d.files["state.uszkodzony.json"], "oryginał zachowany obok")
        assertEquals(AppState(), l.states[0].second)
        assertEquals("Bea", l.states[1].second.patient)
    }

    @Test fun everyCorruptPersonIsListedNotOnlyTheLast() {
        val d = Dir(linkedMapOf(
            "profiles.json" to """{"ids":["p1","p2","p3"]}""",
            "state.json" to "{", "state-p2.json" to text("Bea"), "state-p3.json" to "[]",
        ))
        val l = d.load()
        assertEquals(listOf("p1" to "state.uszkodzony.json", "p3" to "state-p3.uszkodzony.json"), l.recovered)
        assertEquals("Bea", l.states[1].second.patient)
    }

    @Test fun unreadablePersonFileCountsAsRecovered() {
        val d = Dir(linkedMapOf("state.json" to text("Ala")), unreadable = setOf("state.json"))
        val l = d.load()
        assertEquals(listOf("p1"), l.recovered.map { it.first })
    }

    @Test fun missingPersonFileIsEmptyNotRecovered() {
        val d = Dir(linkedMapOf("profiles.json" to """{"ids":["p1","p2"]}""", "state.json" to text("Ala")))
        val l = d.load()
        assertEquals(AppState(), l.states[1].second)
        assertTrue(l.recovered.isEmpty())
    }

    @Test fun recoveredPersonIsNotPersistedByAutomaticChanges() {
        assertFalse(Profiles.mayPersist("p2", setOf("p2")))
        assertTrue(Profiles.mayPersist("p1", setOf("p2")))
        assertTrue(Profiles.mayPersist("p1", emptySet()))
    }

    // ---------- identyfikatory ----------

    @Test fun freeIdSkipsTakenOnes() {
        assertEquals("p2", Profiles.freeId { false })
        assertEquals("p4", Profiles.freeId { it in setOf("p2", "p3") })
    }

    // ---------- wczytanie kopii ----------

    @Test fun replaceRenumbersPeopleAndSharesFirstTheme() {
        val plan = Profiles.planReplace(listOf("p1", "p2", "p3"), listOf(person("Ala", theme = ThemeMode.Dark), person("Bea", theme = ThemeMode.Light)))
        assertEquals(listOf("p1", "p2"), plan.states.map { it.first })
        assertTrue(plan.states.all { it.second.settings.theme == ThemeMode.Dark })
        assertEquals(listOf("state-p3.json", "state-p3.json.bak", "state-p3.uszkodzony.json"), plan.gone)
    }

    @Test fun replaceResolvesHashLinksAndClearsTheRest() {
        val plan = Profiles.planReplace(listOf("p1"), listOf(person("Ala"), person("Bea", "#0"), person("Cyd", "#2"), person("Dan", "#9"), person("Eli", "p1"), person("Fan", "#x")))
        assertEquals(listOf("", "p1", "", "", "", ""), plan.states.map { it.second.settings.cyklFrom })
    }

    @Test fun replaceKeepsAtMostMaxPeopleAndDoesNotDeleteSurvivors() {
        val many = (1..Backup.MAX_PROFILES + 3).map { person("Osoba $it") }
        val plan = Profiles.planReplace((1..Backup.MAX_PROFILES).map { "p$it" }, many)
        assertEquals(Backup.MAX_PROFILES, plan.states.size)
        assertTrue(plan.gone.isEmpty(), "wszystkie stare identyfikatory są ponownie użyte")
    }

    @Test fun replaceNeverDeletesTheBackupBeforePeople() {
        val plan = Profiles.planReplace(listOf("p1", "p2"), listOf(person("Ala")))
        assertTrue(Profiles.BEFORE_PEOPLE !in plan.gone)
        assertTrue("state.json" !in plan.gone)
    }

    // ---------- kolejność zapisu ----------

    @Test fun commitWritesDataThenIndexThenDeletes() {
        val log = ArrayList<String>()
        val ok = Profiles.commitReplace({ log.add("dane"); true }, { log.add("lista"); true }, { log.add("kasowanie") })
        assertTrue(ok)
        assertEquals(listOf("dane", "lista", "kasowanie"), log)
    }

    @Test fun commitDeletesNothingWhenDataWriteFails() {
        val log = ArrayList<String>()
        assertFalse(Profiles.commitReplace({ log.add("dane"); false }, { log.add("lista"); true }, { log.add("kasowanie") }))
        assertEquals(listOf("dane"), log)
    }

    @Test fun commitDeletesNothingWhenIndexWriteFails() {
        val log = ArrayList<String>()
        assertFalse(Profiles.commitReplace({ log.add("dane"); true }, { log.add("lista"); false }, { log.add("kasowanie") }))
        assertEquals(listOf("dane", "lista"), log)
    }

    // ---------- dopisywanie osób z pliku ----------

    @Test fun addUsesFreeIdsAndSkipsIdsWithFilesOnDisk() {
        val added = Profiles.planAdd(listOf("p1"), listOf("Ala"), ThemeMode.Dark, listOf(person("Bea"), person("Cyd"))) { it == "state-p2.json" }
        assertEquals(listOf("p3", "p4"), added.map { it.first })
        assertTrue(added.all { it.second.settings.theme == ThemeMode.Dark })
    }

    @Test fun addRenamesDuplicatesIgnoringCase() {
        val added = Profiles.planAdd(listOf("p1"), listOf("ala"), ThemeMode.Auto, listOf(person("Ala"), person("ALA"), person("Bea"))) { false }
        assertEquals(listOf("Ala (z pliku)", "ALA (z pliku 3)", "Bea"), added.map { it.second.patient })
    }

    @Test fun addResolvesHashLinksToNewIdsOnly() {
        val added = Profiles.planAdd(listOf("p1", "p2"), listOf("Ala", "Bea"), ThemeMode.Auto,
            listOf(person("Cyd"), person("Dan", "#0"), person("Eli", "#1"), person("Fan", "#7"), person("Gal", "p1"))) { false }
        assertEquals(listOf("p3", "p4", "p5", "p6", "p7"), added.map { it.first })
        // #0 wskazuje pierwszą dopisaną (p3), a nie osobę, która była na telefonie
        assertEquals(listOf("", "p3", "p4", "", ""), added.map { it.second.settings.cyklFrom })
    }

    @Test fun addDoesNotLinkToItself() {
        val added = Profiles.planAdd(listOf("p1"), listOf("Ala"), ThemeMode.Auto, listOf(person("Bea", "#0"))) { false }
        assertEquals("", added.single().second.settings.cyklFrom)
    }

    @Test fun addStopsAtMaxPeopleAndKeepsLinksInsideAddedPart() {
        val existing = (1..Backup.MAX_PROFILES - 2).map { "p$it" }
        val added = Profiles.planAdd(existing, existing.map { "Osoba $it" }, ThemeMode.Auto, listOf(person("X"), person("Y", "#0"), person("Z", "#1"))) { false }
        assertEquals(2, added.size)
        assertEquals(listOf("", added[0].first), added.map { it.second.settings.cyklFrom })
    }

    @Test fun addToFullAppAddsNothing() {
        val existing = (1..Backup.MAX_PROFILES).map { "p$it" }
        assertTrue(Profiles.planAdd(existing, emptyList(), ThemeMode.Auto, listOf(person("X"))) { false }.isEmpty())
    }
}

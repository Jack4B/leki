package pl.asystent.leki.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Logika magazynu osób bez dysku i Androida (testowana w `core`): nazwy plików, odczyt listy osób i danych z odtwarzaniem po uszkodzeniu,
 * plany wczytania kopii i dopisania osób z pliku oraz kolejność zapisu przy zastępowaniu danych. Zapis na dysk robi `Store` w aplikacji.
 */
object Profiles {
    const val FILE_NAME = "state.json"
    const val INDEX = "profiles.json"
    /** Zapas pliku pierwszej osoby sprzed wprowadzenia wielu osób. */
    const val BEFORE_PEOPLE = "state.przed-osobami.json"

    private val ID = Regex("p\\d{1,2}")
    private val FILE_ID = Regex("state-(p\\d{1,2})\\.json")

    /** Plik danych osoby: pierwsza osoba zostaje w dotychczasowym `state.json`. */
    fun fileOf(id: String): String = if (id == "p1") FILE_NAME else "state-$id.json"

    /** Kopia uszkodzonego pliku danych osoby. */
    fun sideFile(id: String): String = if (id == "p1") "state.uszkodzony.json" else "state-$id.uszkodzony.json"

    /** Lista osób i aktywna osoba. */
    class Index(val ids: List<String>, val active: String)

    /** Czyta plik listy osób; zwraca null, gdy tekst nie jest poprawną listą. Pusta lista daje samą osobę `p1`. */
    fun parseIndex(text: String): Index? = runCatching {
        val j = Json.parseToJsonElement(text).jsonObject
        val l = j["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { ID.matches(it) }?.distinct().orEmpty()
        val ids = if (l.isNotEmpty()) l.take(Backup.MAX_PROFILES) else listOf("p1")
        Index(ids, j["active"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ids } ?: ids.first())
    }.getOrNull()

    /** Odtworzenie listy osób z nazw plików w katalogu (gdy plik listy jest uszkodzony): `p1` i każda osoba, której plik leży na dysku. */
    fun idsFromFiles(names: Collection<String>): List<String> {
        val found = names.mapNotNull { FILE_ID.matchEntire(it)?.groupValues?.get(1) }.sortedBy { it.drop(1).toInt() }
        return (listOf("p1") + found).distinct().take(Backup.MAX_PROFILES)
    }

    /** [recovered]: pary (identyfikator osoby, nazwa kopii uszkodzonego pliku) dla wszystkich osób odtworzonych po uszkodzeniu. */
    class Loaded(val states: List<Pair<String, AppState>>, val active: String, val recovered: List<Pair<String, String>>)

    /**
     * Wczytuje wszystkie osoby. [read] zwraca treść pliku o danej nazwie albo null, gdy go nie ma (może rzucić wyjątek przy błędzie dysku);
     * [listNames] podaje nazwy plików w katalogu; [quarantine] odkłada uszkodzony plik pod drugą nazwą. Uszkodzony plik osoby daje pusty stan
     * tej osoby i wpis w [Loaded.recovered] (nic nie ginie po cichu); uszkodzona lista osób jest odtwarzana z plików.
     */
    fun load(read: (String) -> String?, listNames: () -> Collection<String>, quarantine: (name: String, sideName: String) -> Unit): Loaded {
        var ids = listOf("p1")
        var active = "p1"
        runCatching {
            val text = read(INDEX)
            if (text != null) { val ix = parseIndex(text) ?: error("uszkodzona lista osób"); ids = ix.ids; active = ix.active }
        }.onFailure {
            ids = idsFromFiles(listNames())
            active = "p1"
        }
        val recovered = ArrayList<Pair<String, String>>()
        val list = ids.map { id ->
            val name = fileOf(id)
            val st = try {
                read(name)?.let { Backup.parseState(it) } ?: AppState()
            } catch (e: Exception) {
                val side = sideFile(id)
                runCatching { quarantine(name, side) }
                recovered.add(id to side)
                AppState()
            }
            id to st
        }
        return Loaded(list, active, recovered)
    }

    /** Pierwszy wolny identyfikator osoby (`p2`, `p3`…); [taken] mówi, czy identyfikator jest zajęty (na liście albo przez plik na dysku). */
    fun freeId(taken: (String) -> Boolean): String = generateSequence(2) { it + 1 }.map { "p$it" }.first { !taken(it) }

    /** Czy zmianę osoby [id] wolno zapisać na dysk: nie, gdy osoba jest w stanie odzyskiwania (pusty stan nie może nadpisać uszkodzonego oryginału). */
    fun mayPersist(id: String, recovered: Set<String>): Boolean = id !in recovered

    /** Wynik planu wczytania kopii: nowe stany osób (`p1`…) i pliki starych osób do skasowania po zapisie. */
    class ReplacePlan(val states: List<Pair<String, AppState>>, val gone: List<String>)

    /**
     * Plan wczytania kopii „Zastąp wszystkie dane”: osoby dostają identyfikatory `p1`… w kolejności z kopii (najwyżej [Backup.MAX_PROFILES]),
     * wspólny wygląd pierwszej osoby, a powiązanie dziennika cyklu „#numer” wskazuje osobę o tym numerze (inne wartości są czyszczone).
     * [oldIds]: osoby obecne przed wczytaniem; ich pliki (dane, `.bak`, kopia uszkodzonego) są do skasowania, jeśli osoba nie występuje w nowych danych.
     */
    fun planReplace(oldIds: Collection<String>, list: List<AppState>): ReplacePlan {
        require(list.isNotEmpty())
        val theme = list.first().settings.theme
        val n = minOf(list.size, Backup.MAX_PROFILES)
        val states = list.take(n).mapIndexed { i, s ->
            val link = s.settings.cyklFrom.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()?.takeIf { it in 0 until n && it != i }?.let { "p${it + 1}" } ?: ""
            "p${i + 1}" to s.copy(settings = s.settings.copy(theme = theme, cyklFrom = link))
        }
        val keep = states.map { it.first }.toSet()
        val gone = oldIds.filter { it !in keep }.flatMap { listOf(fileOf(it), fileOf(it) + ".bak", sideFile(it)) }
        return ReplacePlan(states, gone)
    }

    /**
     * Plan dopisania osób z pliku do tych, które są na telefonie (nic nie jest zastępowane): wolne identyfikatory (także bez pliku na dysku),
     * powtórzone imię z dopiskiem „(z pliku)”, wygląd [theme] osoby aktywnej, wspólny dziennik cyklu „#numer” wskazuje osobę dopisaną
     * z tego samego pliku (numer to miejsce w pliku). Kończy na limicie [Backup.MAX_PROFILES] osób.
     */
    fun planAdd(existingIds: Collection<String>, existingNames: Collection<String>, theme: ThemeMode, list: List<AppState>, fileExists: (String) -> Boolean): List<Pair<String, AppState>> {
        val ids = existingIds.toMutableSet()
        val names = existingNames.map { it.trim().lowercase() }.toMutableSet()
        val added = ArrayList<Pair<String, AppState>>()
        for (s in list) {
            if (ids.size >= Backup.MAX_PROFILES) break
            val id = freeId { it in ids || fileExists(fileOf(it)) }
            ids.add(id)
            val name = Backup.uniqueName(s.patient, names)
            names.add(name.lowercase())
            added.add(id to s.copy(patient = name, settings = s.settings.copy(theme = theme)))
        }
        return added.map { (id, st) ->
            val l = st.settings.cyklFrom
            val target = if (l.startsWith("#")) l.drop(1).toIntOrNull()?.takeIf { it in added.indices }?.let { added[it].first } else null
            id to st.copy(settings = st.settings.copy(cyklFrom = if (target != null && target != id) target else ""))
        }
    }

    /**
     * Kolejność zapisu przy zastępowaniu danych, odporna na zabicie procesu w dowolnym momencie: najpierw nowe pliki osób ([writeData]),
     * potem lista osób ([writeIndex]), dopiero na końcu kasowanie starych plików ([deleteOld]). Gdy któryś zapis się nie uda, nic nie jest kasowane.
     * Zwraca prawdę, gdy zapisano wszystko i skasowano stare pliki.
     */
    fun commitReplace(writeData: () -> Boolean, writeIndex: () -> Boolean, deleteOld: () -> Unit): Boolean {
        if (!writeData()) return false
        if (!writeIndex()) return false
        deleteOld()
        return true
    }
}

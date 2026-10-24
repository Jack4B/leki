package pl.asystent.leki

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import java.io.File

/** Osoba w aplikacji: identyfikator i nazwa do pokazania (imię wpisane w Ustawieniach albo „Osoba N”). */
data class ProfileInfo(val id: String, val name: String)

/**
 * Jedyne miejsce, w którym żyją dane aplikacji: dla każdej osoby jeden dokument JSON (format kopii wersji 3) w pamięci aplikacji.
 * Pierwsza osoba (`p1`) zostaje w dotychczasowym pliku `state.json`, więc po aktualizacji wszystko jest na miejscu; kolejne mają
 * pliki `state-<id>.json`, a lista osób i aktywna osoba są w `profiles.json`. Cały interfejs pracuje na aktywnej osobie ([state], [update]).
 *
 * Zmiany są zapisywane z krótkim opóźnieniem przez [android.util.AtomicFile]: nowa treść trafia do osobnego pliku, jest synchronizowana
 * z dyskiem (fsync) i dopiero wtedy zastępuje poprzednią, więc utrata zasilania w trakcie zapisu nie psuje ostatniego dobrego pliku.
 */
class Store(private val dir: File, private val onSaved: (Store) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.e("Store", "Błąd w zadaniu zapisu", e) })
    private var saveJob: Job? = null
    private val lock = Any()
    /** Osobna blokada zapisu listy osób, żeby przełączanie osoby z interfejsu nie czekało na zapis wszystkich plików danych. */
    private val indexLock = Any()

    /** Gdy któryś plik danych był uszkodzony: nazwa kopii, do której go przeniesiono. */
    val recoveredFrom: String?

    private val states = LinkedHashMap<String, AppState>()
    private val dirty = HashSet<String>()
    private var active: String

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState> get() = _state.asStateFlow()

    private val _profiles: MutableStateFlow<List<ProfileInfo>>
    /** Lista osób (w kolejności dodania) z nazwami. Zmienia się, gdy ktoś zmieni imię albo doda/usunie osobę. */
    val profiles: StateFlow<List<ProfileInfo>> get() = _profiles.asStateFlow()

    private val _activeId: MutableStateFlow<String>
    /** Identyfikator aktywnej osoby. */
    val activeId: StateFlow<String> get() = _activeId.asStateFlow()

    private val _saveFailed = MutableStateFlow(false)
    private val _rev = MutableStateFlow(0)
    /** Zmienia się po każdej zmianie danych któregokolwiek profilu (także nieaktywnego), żeby ekrany pokazujące cudze dane mogły się odświeżyć. */
    val revision: StateFlow<Int> get() = _rev.asStateFlow()

    /** Prawda, gdy ostatni zapis na dysk się nie udał (np. brak miejsca). Interfejs pokazuje wtedy ostrzeżenie. */
    val saveFailed: StateFlow<Boolean> get() = _saveFailed.asStateFlow()

    init {
        val loaded = readAll(dir)
        loaded.states.forEach { states[it.first] = it.second }
        active = loaded.active
        recoveredFrom = loaded.recovered
        _state = MutableStateFlow(states.getValue(active))
        _profiles = MutableStateFlow(infos())
        _activeId = MutableStateFlow(active)
        // pierwsze uruchomienie z obsługą wielu osób: zapas dotychczasowego pliku (nic nie jest kasowane ani przenoszone)
        if (!File(dir, INDEX).exists() && File(dir, FILE_NAME).exists()) {
            runCatching { File(dir, FILE_NAME).copyTo(File(dir, "state.przed-osobami.json"), overwrite = false) }
            writeIndex()
        }
    }

    private fun publishProfiles() { synchronized(lock) { _profiles.value = infos() }; _rev.update { it + 1 } }

    private fun infos(): List<ProfileInfo> = synchronized(lock) {
        states.entries.mapIndexed { i, e -> ProfileInfo(e.key, e.value.patient.trim().ifEmpty { "Osoba ${i + 1}" }) }
    }

    // ---------- Zmiany danych ----------

    /** Zmiana danych aktywnej osoby. */
    fun update(f: (AppState) -> AppState) {
        val id: String
        synchronized(lock) {
            id = active
            _state.update(f)
            states[id] = _state.value
            dirty.add(id)
        }
        publishProfiles()
        scheduleSave()
    }

    fun replace(s: AppState) = update { s }

    /** Zmiana danych wskazanej osoby (np. z powiadomienia albo z modułu Cykl, który działał, zanim ktoś przełączył osobę). */
    fun updateProfile(id: String, f: (AppState) -> AppState) {
        synchronized(lock) {
            if (id == active) { update(f); return }
            val cur = states[id] ?: return
            states[id] = f(cur)
            dirty.add(id)
        }
        publishProfiles()
        scheduleSave()
    }

    /** Zmiana wspólna dla wszystkich osób (np. motyw wyglądu). */
    fun updateAll(f: (AppState) -> AppState) {
        synchronized(lock) {
            states.keys.toList().forEach { id -> states[id] = f(states.getValue(id)); dirty.add(id) }
            _state.value = states.getValue(active)
        }
        publishProfiles()
        scheduleSave()
    }

    fun profileState(id: String): AppState? = synchronized(lock) { states[id] }

    /** Osoba, od której osoba [id] bierze dziennik cyklu: ona sama albo wskazana (gdy istnieje i sama nie korzysta z cudzego dziennika). */
    fun cyklOwner(id: String): String = synchronized(lock) {
        val link = states[id]?.settings?.cyklFrom.orEmpty()
        if (link.isNotEmpty() && link != id && states[link]?.settings?.cyklFrom.isNullOrEmpty() && link in states) link else id
    }

    /** Dziennik cyklu osoby [id] (własny albo wspólny z inną osobą). */
    fun cyklOf(id: String): pl.asystent.leki.core.CyklData = synchronized(lock) { states[cyklOwner(id)]?.cykl ?: pl.asystent.leki.core.CyklData() }

    /** Zmiana dziennika cyklu widzianego przez osobę [id]: trafia do osoby, której dziennik to jest. */
    fun updateCykl(id: String, f: (pl.asystent.leki.core.CyklData) -> pl.asystent.leki.core.CyklData) {
        updateProfile(cyklOwner(id)) { it.copy(cykl = f(it.cykl)) }
    }

    /** Stan osoby z jej widocznym dziennikiem cyklu (do raportów). */
    fun withSharedCykl(s: AppState, id: String): AppState = s.copy(cykl = cyklOf(id))

    /**
     * Stany osób do zapisu w kopii zapasowej, z datą ostatniej kopii. [only] = null: wszystkie osoby; inaczej tylko wybrane (w kolejności aplikacji).
     * Wspólny dziennik cyklu jest zapisany jako „#numer osoby” (numer w kopii), bo identyfikatory po wczytaniu są nadawane od nowa;
     * gdy właściciel wspólnego dziennika nie wchodzi do kopii, jego dziennik jest dołączony do osoby, która z niego korzysta (kopia ma być kompletna).
     */
    fun exportStates(today: java.time.LocalDate = java.time.LocalDate.now(), only: Set<String>? = null): List<AppState> = synchronized(lock) {
        Backup.selectForExport(states.map { it.key to it.value }, only, ::cyklOwner, today)
    }

    /** Kopia wybranych osób ([only] = null: wszystkich) jako tekst; starsze wersje aplikacji wczytają z niej pierwszą osobę. */
    fun backupText(today: java.time.LocalDate = java.time.LocalDate.now(), only: Set<String>? = null): String = Backup.exportAll(exportStates(today, only))

    /** Oznacza, że kopia została zrobiona: dla wybranych osób ([only]) albo wszystkich. */
    fun markBackup(day: java.time.LocalDate, only: Set<String>? = null) {
        if (only == null) updateAll { it.copy(lastBackup = day) }
        else only.forEach { id -> updateProfile(id) { it.copy(lastBackup = day) } }
    }

    /** Stan wszystkich osób (pierwsza to `p1`), np. do kopii zapasowej i przypomnień. */
    fun allStates(): List<Pair<ProfileInfo, AppState>> = synchronized(lock) {
        val names = infos().associateBy { it.id }
        states.map { names.getValue(it.key) to it.value }
    }

    // ---------- Osoby ----------

    fun switchTo(id: String) {
        synchronized(lock) {
            if (id == active || id !in states) return
            active = id
            _state.value = states.getValue(id)
        }
        _activeId.value = id
        writeIndex()
    }

    /** Dodaje osobę z podaną nazwą; zwraca jej identyfikator albo null, gdy osiągnięto limit. Wygląd (motyw) przejmuje po aktywnej osobie. */
    fun addProfile(name: String): String? {
        val id: String
        synchronized(lock) {
            if (states.size >= Backup.MAX_PROFILES) return null
            // wolny numer: także taki, którego plik nie leży już na dysku (uszkodzona lista osób nie może prowadzić do nadpisania cudzych danych)
            id = generateSequence(2) { it + 1 }.map { "p$it" }.first { it !in states && !File(dir, fileOf(it)).exists() }
            val theme = states.getValue(active).settings.theme
            states[id] = AppState(patient = name.trim(), settings = pl.asystent.leki.core.Settings(theme = theme))
            dirty.add(id)
        }
        publishProfiles()
        writeIndex()
        scheduleSave()
        return id
    }

    /** Usuwa osobę z jej danymi. Nie da się usunąć ostatniej. Zwraca prawdę, gdy usunięto. */
    fun removeProfile(id: String): Boolean {
        val gone: List<String>
        synchronized(lock) {
            if (states.size <= 1 || id !in states) return false
            states.remove(id); dirty.remove(id)
            gone = listOf(fileOf(id), fileOf(id) + ".bak", sideFile(id))
            if (active == id) { active = states.keys.first(); _state.value = states.getValue(active) }
        }
        // pliki kasujemy pod blokadą zapisu (nie zagnieżdżoną w `lock`), żeby trwający zapis nie odtworzył pliku usuniętej osoby
        synchronized(this) { gone.forEach { runCatching { File(dir, it).delete() } } }
        publishProfiles()
        _activeId.value = active
        writeIndex()
        scheduleSave()   // alarmy usuniętej osoby mają zniknąć od razu
        return true
    }

    /** Wczytanie kopii zapasowej: cała zawartość aplikacji (wszystkie osoby) zostaje zastąpiona; aktywna jest pierwsza osoba. */
    fun replaceAll(list: List<AppState>) {
        require(list.isNotEmpty())
        val gone = ArrayList<String>()
        synchronized(lock) {
            val old = states.keys.toList()
            states.clear(); dirty.clear()
            // wygląd (motyw) jest wspólny: wszystkie osoby z kopii dostają motyw pierwszej
            val theme = list.first().settings.theme
            val n = minOf(list.size, Backup.MAX_PROFILES)
            list.take(n).forEachIndexed { i, s ->
                val id = "p${i + 1}"
                // „#numer” z kopii wskazuje osobę o tym numerze; wszystko inne czyści powiązanie
                val link = s.settings.cyklFrom.takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()?.takeIf { it in 0 until n && it != i }?.let { "p${it + 1}" } ?: ""
                states[id] = s.copy(settings = s.settings.copy(theme = theme, cyklFrom = link)); dirty.add(id)
            }
            old.filter { it !in states }.forEach { gone.add(fileOf(it)); gone.add(fileOf(it) + ".bak"); gone.add(sideFile(it)) }
            active = "p1"
            _state.value = states.getValue(active)
        }
        synchronized(this) { gone.forEach { runCatching { File(dir, it).delete() } } }
        publishProfiles()
        _activeId.value = active
        writeIndex()
        writeDirty()     // od razu: zabicie procesu w oknie opóźnionego zapisu nie może zostawić nowego indeksu przy starych danych
        scheduleSave()
    }

    /**
     * Dodaje osoby z pliku kopii do tych, które są na telefonie (nic nie jest zastępowane). Zwraca identyfikatory dodanych osób.
     * Wspólny dziennik cyklu („#numer”) wskazuje osobę z tego samego pliku; powtórzone imię dostaje dopisek „(z pliku)”.
     */
    fun addProfilesFrom(list: List<AppState>): List<String> {
        val added = ArrayList<String>()
        synchronized(lock) {
            val theme = states.getValue(active).settings.theme
            val names = states.values.map { it.patient.trim().lowercase() }.toMutableSet()
            for (s in list) {
                if (states.size >= Backup.MAX_PROFILES) break
                val id = generateSequence(2) { it + 1 }.map { "p$it" }.first { it !in states && !File(dir, fileOf(it)).exists() }
                val name = Backup.uniqueName(s.patient, names)
                names.add(name.lowercase())
                states[id] = s.copy(patient = name, settings = s.settings.copy(theme = theme)); dirty.add(id); added.add(id)
            }
            added.forEach { id ->
                val st = states.getValue(id)
                val l = st.settings.cyklFrom
                val target = if (l.startsWith("#")) l.drop(1).toIntOrNull()?.takeIf { it in added.indices }?.let { added[it] } else null
                states[id] = st.copy(settings = st.settings.copy(cyklFrom = if (target != null && target != id) target else ""))
            }
        }
        // najpierw dane nowych osób, potem lista osób (zabicie procesu w tym oknie nie zostawi pustych osób na liście)
        writeDirty(); publishProfiles(); writeIndex()
        return added
    }

    /** „Usuń wszystkie dane”: wszystkie osoby i pliki pomocnicze (zapas sprzed osób, kopie uszkodzonych plików) znikają, zostaje jedna pusta osoba. */
    fun wipeAll() {
        replaceAll(listOf(AppState()))
        synchronized(this) {
            runCatching { File(dir, "state.przed-osobami.json").delete() }
            runCatching { dir.listFiles { f -> f.name.endsWith(".uszkodzony.json") }?.forEach { it.delete() } }
        }
    }

    // ---------- Zapis ----------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(350)
            writeDirty()
            runCatching { onSaved(this@Store) }
        }
    }

    /** Zapisuje od razu (np. przy zamykaniu aplikacji). */
    fun flush() {
        saveJob?.cancel()
        writeDirty()
        runCatching { onSaved(this) }
    }

    @Synchronized
    private fun writeDirty() {
        val todo: List<Pair<String, AppState>> = synchronized(lock) {
            val l = dirty.mapNotNull { id -> states[id]?.let { id to it } }
            dirty.clear()
            l
        }
        var ok = true
        todo.forEach { (id, s) -> if (!write(id, s)) ok = false }
        if (todo.isNotEmpty()) _saveFailed.value = !ok     // ostrzeżenie znika dopiero, gdy cała partia się zapisała
    }

    private fun write(id: String, s: AppState): Boolean {
        var out: java.io.FileOutputStream? = null
        val atomic = android.util.AtomicFile(File(dir, fileOf(id)))
        return try {
            dir.mkdirs()
            out = atomic.startWrite()
            out.write(Backup.serialize(s).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            true
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            synchronized(lock) { dirty.add(id) }
            android.util.Log.e("Store", "Nie udało się zapisać danych", e)
            false
        }
    }

    private fun writeIndex() = synchronized(indexLock) {
        val (ids, act) = synchronized(lock) { states.keys.toList() to active }
        val atomic = android.util.AtomicFile(File(dir, INDEX))
        var out: java.io.FileOutputStream? = null
        try {
            dir.mkdirs()
            out = atomic.startWrite()
            val json = JsonObject(mapOf("active" to JsonPrimitive(act), "ids" to JsonArray(ids.map { JsonPrimitive(it) })))
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            android.util.Log.e("Store", "Nie udało się zapisać listy osób", e)
        }
    }

    class Loaded(val states: List<Pair<String, AppState>>, val active: String, val recovered: String?)

    companion object {
        const val FILE_NAME = "state.json"
        const val INDEX = "profiles.json"

        fun fileOf(id: String): String = if (id == "p1") FILE_NAME else "state-$id.json"

        /** Kopia uszkodzonego pliku danych danej osoby (jeśli powstała). */
        fun sideFile(id: String): String = if (id == "p1") "state.uszkodzony.json" else "state-$id.uszkodzony.json"

        /**
         * Wczytuje dane jednej osoby; uszkodzony plik jest odkładany obok jako jedna kopia (np. state.uszkodzony.json), a osoba startuje pusta,
         * więc nic nie ginie po cichu i kolejne starty nie mnożą plików.
         */
        private fun readOne(dir: File, id: String): Pair<AppState, String?> {
            val name = fileOf(id)
            val f = File(dir, name)
            val af = android.util.AtomicFile(f)
            // AtomicFile ma kopię zapasową "<plik>.bak" (zostaje po przerwanym zapisie), więc brak głównego pliku nie oznacza jeszcze braku danych
            if (!f.exists() && !File(dir, "$name.bak").exists()) return AppState() to null
            return try {
                Backup.parseState(String(af.readFully(), Charsets.UTF_8)) to null
            } catch (e: Exception) {
                val badFile = File(dir, if (id == "p1") "state.uszkodzony.json" else "state-$id.uszkodzony.json")
                runCatching { f.copyTo(badFile, overwrite = true) }
                AppState() to badFile.name
            }
        }

        /** Lista osób i aktywna osoba z dysku; bez pliku listy jest jedna osoba (`p1`), czyli dane sprzed obsługi wielu osób. */
        fun readAll(dir: File): Loaded {
            val idx = File(dir, INDEX)
            var ids = listOf("p1")
            var act = "p1"
            if (idx.exists() || File(dir, "$INDEX.bak").exists()) runCatching {
                val j = Json.parseToJsonElement(String(android.util.AtomicFile(idx).readFully(), Charsets.UTF_8)).jsonObject
                val l = j["ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { Regex("p\\d{1,2}").matches(it) }?.distinct().orEmpty()
                if (l.isNotEmpty()) ids = l.take(Backup.MAX_PROFILES)
                act = j["active"]?.jsonPrimitive?.contentOrNull?.takeIf { it in ids } ?: ids.first()
            }.onFailure {
                // uszkodzona lista osób: odtwarzamy ją z plików danych leżących w katalogu, żeby żadna osoba nie zniknęła
                val found = dir.listFiles()?.mapNotNull { f -> Regex("state-(p\\d{1,2})\\.json").matchEntire(f.name)?.groupValues?.get(1) }.orEmpty().sortedBy { it.drop(1).toInt() }
                ids = (listOf("p1") + found).distinct().take(Backup.MAX_PROFILES)
                act = "p1"
            }
            var recovered: String? = null
            val list = ids.map { id -> val (s, bad) = readOne(dir, id); if (bad != null) recovered = bad; id to s }
            return Loaded(list, act, recovered)
        }

        /** Dane aktywnej osoby bez uruchamiania całego magazynu (widżet, odbiorniki, gdy aplikacja nie działa). */
        fun read(dir: File): Pair<AppState, String?> {
            val l = readAll(dir)
            return l.states.first { it.first == l.active }.second to l.recovered
        }
    }
}

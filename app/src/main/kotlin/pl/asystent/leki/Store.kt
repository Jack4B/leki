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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.Profiles
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

    private val _recovered: MutableStateFlow<Map<String, String>>
    /**
     * Osoby, których plik danych był uszkodzony: identyfikator osoby → nazwa kopii, do której plik przeniesiono. Osoba startuje pusta, ale
     * dopóki nie zapadnie decyzja ([acknowledgeRecovery], wczytanie kopii, usunięcie osoby), automatyczne zmiany (kopia zapasowa, motyw) nie
     * zapisują jej pustego stanu na dysk, a automatyczna kopia jest wstrzymana (pusta osoba wyparłaby rotacją dobre kopie).
     */
    val recovered: StateFlow<Map<String, String>> get() = _recovered.asStateFlow()

    /** Prawda, gdy któraś osoba została odtworzona po uszkodzonym pliku i użytkownik jeszcze nie zdecydował, co dalej. */
    val hasRecovery: Boolean get() = _recovered.value.isNotEmpty()

    /** Użytkownik świadomie zaczyna od nowa (albo ma już kopię): koniec wstrzymania zapisów automatycznych i kopii. Uszkodzone pliki zostają obok. */
    fun acknowledgeRecovery() { _recovered.value = emptyMap() }

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
        _recovered = MutableStateFlow(loaded.recovered.toMap())
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
            states.keys.toList().forEach { id -> states[id] = f(states.getValue(id)); if (Profiles.mayPersist(id, _recovered.value.keys)) dirty.add(id) }
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
        synchronized(lock) {
            (only ?: states.keys.toSet()).forEach { id ->
                val cur = states[id] ?: return@forEach
                states[id] = cur.copy(lastBackup = day)
                if (Profiles.mayPersist(id, _recovered.value.keys)) dirty.add(id)      // data kopii nie może zapisać pustego stanu osoby po uszkodzonym pliku
            }
            _state.value = states.getValue(active)
        }
        publishProfiles()
        scheduleSave()
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
            id = Profiles.freeId { it in states || File(dir, fileOf(it)).exists() }
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
            states.remove(id); dirty.remove(id); _recovered.update { it - id }
            // osoby, które korzystały z dziennika usuwanej osoby, wracają do własnego (inaczej nowa osoba o tym samym id przejęłaby ten wskaźnik)
            states.keys.toList().forEach { k ->
                val st = states.getValue(k)
                if (st.settings.cyklFrom == id) { states[k] = st.copy(settings = st.settings.copy(cyklFrom = "")); dirty.add(k) }
            }
            gone = listOf(fileOf(id), fileOf(id) + ".bak", sideFile(id))
            if (active == id) active = states.keys.first()
            _state.value = states.getValue(active)
        }
        // pliki kasujemy pod blokadą zapisu (nie zagnieżdżoną w `lock`), żeby trwający zapis nie odtworzył pliku usuniętej osoby
        // zapasowa kopia sprzed wprowadzenia osób zawiera jawne dane pierwszej osoby: znika razem z nią
        synchronized(this) { gone.forEach { runCatching { File(dir, it).delete() } }; if (id == "p1") runCatching { File(dir, "state.przed-osobami.json").delete() } }
        publishProfiles()
        _activeId.value = active
        writeIndex()
        scheduleSave()   // alarmy usuniętej osoby mają zniknąć od razu
        return true
    }

    /** Wczytanie kopii zapasowej: cała zawartość aplikacji (wszystkie osoby) zostaje zastąpiona; aktywna jest pierwsza osoba. */
    fun replaceAll(list: List<AppState>) {
        val plan: Profiles.ReplacePlan
        synchronized(lock) {
            plan = Profiles.planReplace(states.keys.toList(), list)
            states.clear(); dirty.clear(); _recovered.value = emptyMap()
            plan.states.forEach { (id, st) -> states[id] = st; dirty.add(id) }
            active = "p1"
            _state.value = states.getValue(active)
        }
        publishProfiles()
        _activeId.value = active
        // kolejność chroni dane przy zabiciu procesu w dowolnym momencie: najpierw nowe pliki, potem lista osób, dopiero na końcu
        // kasowanie starych plików osób; jeśli zapis się nie udał, nic nie jest kasowane. Zapas sprzed osób (state.przed-osobami.json)
        // zostaje: znika dopiero przy usunięciu pierwszej osoby albo w „Usuń wszystkie dane”.
        Profiles.commitReplace(
            writeData = { writeDirty() },
            writeIndex = { writeIndex() },
            deleteOld = { synchronized(this) { plan.gone.forEach { runCatching { File(dir, it).delete() } } } },
        )
        scheduleSave()
    }

    /**
     * Dodaje osoby z pliku kopii do tych, które są na telefonie (nic nie jest zastępowane). Zwraca identyfikatory dodanych osób.
     * Wspólny dziennik cyklu („#numer”) wskazuje osobę z tego samego pliku; powtórzone imię dostaje dopisek „(z pliku)”.
     */
    fun addProfilesFrom(list: List<AppState>): List<String> {
        val added: List<Pair<String, AppState>>
        synchronized(lock) {
            added = Profiles.planAdd(states.keys, states.values.map { it.patient }, states.getValue(active).settings.theme, list) { File(dir, it).exists() }
            added.forEach { (id, st) -> states[id] = st; dirty.add(id) }
        }
        // najpierw dane nowych osób, potem lista osób (zabicie procesu w tym oknie nie zostawi pustych osób na liście)
        writeDirty(); publishProfiles(); writeIndex()
        scheduleSave()   // przypomnienia i widżet mają od razu uwzględnić dodane osoby
        return added.map { it.first }
    }

    /** „Usuń wszystkie dane”: wszystkie osoby i pliki pomocnicze (zapas sprzed osób, kopie uszkodzonych plików) znikają, zostaje jedna pusta osoba. */
    fun wipeAll() {
        replaceAll(listOf(AppState()))
        synchronized(this) {
            runCatching { File(dir, "state.przed-osobami.json").delete() }
            runCatching { dir.listFiles { f -> Profiles.isLeftover(f.name) }?.forEach { it.delete() } }
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

    /** Zapisuje zmienione osoby; zwraca prawdę, gdy wszystkie zapisy się udały (albo nie było czego zapisywać). */
    @Synchronized
    private fun writeDirty(): Boolean {
        val todo: List<Pair<String, AppState>> = synchronized(lock) {
            val l = dirty.mapNotNull { id -> states[id]?.let { id to it } }
            dirty.clear()
            l
        }
        var ok = true
        todo.forEach { (id, s) -> if (!write(id, s)) ok = false }
        if (todo.isNotEmpty()) _saveFailed.value = !ok     // ostrzeżenie znika dopiero, gdy cała partia się zapisała
        return ok
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

    private fun writeIndex(): Boolean = synchronized(indexLock) {
        val (ids, act) = synchronized(lock) { states.keys.toList() to active }
        val atomic = android.util.AtomicFile(File(dir, INDEX))
        var out: java.io.FileOutputStream? = null
        try {
            dir.mkdirs()
            out = atomic.startWrite()
            val json = JsonObject(mapOf("active" to JsonPrimitive(act), "ids" to JsonArray(ids.map { JsonPrimitive(it) })))
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            true
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            android.util.Log.e("Store", "Nie udało się zapisać listy osób", e)
            false
        }
    }

    companion object {
        const val FILE_NAME = Profiles.FILE_NAME
        const val INDEX = Profiles.INDEX

        fun fileOf(id: String): String = Profiles.fileOf(id)

        /** Kopia uszkodzonego pliku danych danej osoby (jeśli powstała). */
        fun sideFile(id: String): String = Profiles.sideFile(id)

        /**
         * Lista osób, dane wszystkich osób i aktywna osoba z dysku; bez pliku listy jest jedna osoba (`p1`), czyli dane sprzed obsługi wielu osób.
         * Uszkodzony plik osoby jest odkładany obok (np. state.uszkodzony.json), osoba startuje pusta i trafia na listę odtworzonych
         * ([Profiles.Loaded.recovered]), więc nic nie ginie po cichu i kolejne starty nie mnożą plików.
         */
        fun readAll(dir: File): Profiles.Loaded = Profiles.load(
            // AtomicFile ma kopię zapasową "<plik>.bak" (zostaje po przerwanym zapisie), więc brak głównego pliku nie oznacza jeszcze braku danych
            read = { name ->
                val f = File(dir, name)
                if (!f.exists() && !File(dir, "$name.bak").exists()) null
                else String(android.util.AtomicFile(f).readFully(), Charsets.UTF_8)
            },
            listNames = { dir.list()?.toList().orEmpty() },
            quarantine = { name, side -> File(dir, name).copyTo(File(dir, side), overwrite = true) },
        )

        /** Dane aktywnej osoby bez uruchamiania całego magazynu (widżet, odbiorniki, gdy aplikacja nie działa). */
        fun read(dir: File): Pair<AppState, String?> {
            val l = readAll(dir)
            return l.states.first { it.first == l.active }.second to l.recovered.firstOrNull { it.first == l.active }?.second
        }
    }
}

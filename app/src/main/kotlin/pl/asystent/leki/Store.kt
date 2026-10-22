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
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import java.io.File

/**
 * Jedyne miejsce, w którym żyją dane aplikacji: jeden dokument JSON (format kopii wersji 3) w pamięci aplikacji.
 * Zmiany są zapisywane z krótkim opóźnieniem przez [android.util.AtomicFile]: nowa treść trafia do osobnego pliku, jest synchronizowana
 * z dyskiem (fsync) i dopiero wtedy zastępuje poprzednią, więc utrata zasilania w trakcie zapisu nie psuje ostatniego dobrego pliku.
 */
class Store(private val dir: File, private val onSaved: (AppState) -> Unit = {}) {
    private val file = File(dir, FILE_NAME)
    private val atomic = android.util.AtomicFile(file)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> android.util.Log.e("Store", "Błąd w zadaniu zapisu", e) })
    private var saveJob: Job? = null

    /** Gdy plik danych był uszkodzony: ścieżka kopii, do której go przeniesiono. */
    val recoveredFrom: String?

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState> get() = _state.asStateFlow()

    private val _saveFailed = MutableStateFlow(false)

    /** Prawda, gdy ostatni zapis na dysk się nie udał (np. brak miejsca). Interfejs pokazuje wtedy ostrzeżenie. */
    val saveFailed: StateFlow<Boolean> get() = _saveFailed.asStateFlow()

    init {
        val r = read(dir)
        _state = MutableStateFlow(r.first)
        recoveredFrom = r.second
    }

    fun update(f: (AppState) -> AppState) {
        _state.update(f)
        scheduleSave()
    }

    fun replace(s: AppState) = update { s }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(350)
            write(_state.value)
            runCatching { onSaved(_state.value) }
        }
    }

    /** Zapisuje od razu (np. przy zamykaniu aplikacji). */
    fun flush() {
        saveJob?.cancel()
        write(_state.value)
        runCatching { onSaved(_state.value) }
    }

    @Synchronized
    private fun write(s: AppState) {
        var out: java.io.FileOutputStream? = null
        try {
            dir.mkdirs()
            out = atomic.startWrite()
            out.write(Backup.serialize(s).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
            _saveFailed.value = false
        } catch (e: Exception) {
            out?.let { atomic.failWrite(it) }
            _saveFailed.value = true
            android.util.Log.e("Store", "Nie udało się zapisać danych", e)
        }
    }

    companion object {
        const val FILE_NAME = "state.json"

        /**
         * Wczytuje dane; uszkodzony plik jest odkładany obok jako jedna kopia (state.uszkodzony.json), a aplikacja startuje pusta,
         * więc nic nie ginie po cichu i kolejne starty nie mnożą plików.
         */
        fun read(dir: File): Pair<AppState, String?> {
            val f = File(dir, FILE_NAME)
            val af = android.util.AtomicFile(f)
            if (!af.exists()) return AppState() to null
            return try {
                Backup.parseState(String(af.readFully(), Charsets.UTF_8)) to null
            } catch (e: Exception) {
                val bad = File(dir, "state.uszkodzony.json")
                runCatching { f.copyTo(bad, overwrite = true) }
                AppState() to bad.name
            }
        }
    }
}

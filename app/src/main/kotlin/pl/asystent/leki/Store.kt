package pl.asystent.leki

import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Jedyne miejsce, w którym żyją dane aplikacji: jeden dokument JSON (format kopii wersji 3) w pamięci aplikacji.
 * Zmiany są zapisywane z krótkim opóźnieniem, atomowo (plik tymczasowy i zamiana).
 */
class Store(private val dir: File, private val onSaved: (AppState) -> Unit = {}) {
    private val file = File(dir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    /** Gdy plik danych był uszkodzony: ścieżka kopii, do której go przeniesiono. */
    val recoveredFrom: String?

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState> get() = _state.asStateFlow()

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
            onSaved(_state.value)
        }
    }

    /** Zapisuje od razu (np. przy zamykaniu aplikacji). */
    fun flush() {
        saveJob?.cancel()
        write(_state.value)
        onSaved(_state.value)
    }

    @Synchronized
    private fun write(s: AppState) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(Backup.serialize(s), Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            android.util.Log.e("Store", "Nie udało się zapisać danych", e)
        }
    }

    companion object {
        const val FILE_NAME = "state.json"

        /** Wczytuje dane; uszkodzony plik jest odkładany obok, a aplikacja startuje pusta (nic nie ginie po cichu). */
        fun read(dir: File): Pair<AppState, String?> {
            val f = File(dir, FILE_NAME)
            if (!f.exists()) return AppState() to null
            return try {
                Backup.parseState(f.readText(Charsets.UTF_8)) to null
            } catch (e: Exception) {
                val bad = File(dir, "state.uszkodzony-${System.currentTimeMillis()}.json")
                runCatching { f.copyTo(bad, overwrite = true) }
                AppState() to bad.name
            }
        }
    }
}

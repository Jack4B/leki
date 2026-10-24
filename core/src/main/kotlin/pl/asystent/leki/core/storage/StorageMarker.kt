package pl.asystent.leki.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Liczba całkowita zapisana jako liczba JSON; tekst „1” jest nieważny. */
private fun JsonPrimitive.num(): Int? = if (isString) null else intOrNull

enum class StorageMode(val id: String) { PLAIN("plain"), TO_ENC("to-enc"), ENC("enc"), TO_PLAIN("to-plain") }

/** Wynik oceny znacznika przy odczycie (plan, rozdział 3). */
sealed class MarkerState {
    /** Znacznik ważny. */
    class Valid(val marker: StorageMarker) : MarkerState()
    /** Brak znacznika i brak `state*.enc` oraz `keys.bin`: instalacja bez szyfrowania. */
    object FreshPlain : MarkerState()
    /** Znacznik brakujący lub nieważny, a istnieją `.enc` lub `keys.bin`: tryb `enc` tylko do odczytu z ostrzeżeniem. */
    object ReadOnlyEnc : MarkerState()
}

/**
 * Atomowy znacznik trybu `storage.json`: `{"v":1,"mode":...,"dekId":n,"attempts":0..3}`.
 * `attempts` to jeden licznik dla obu kierunków migracji: rośnie o 1 przy porażce (najwyżej do 3), zeruje się po sukcesie i ręcznej próbie.
 */
data class StorageMarker(val mode: StorageMode, val dekId: Int, val attempts: Int = 0) {
    init { require(dekId >= 1 && attempts in 0..MAX_ATTEMPTS) }

    fun toJson(): String = JsonObject(mapOf(
        "v" to JsonPrimitive(1), "mode" to JsonPrimitive(mode.id), "dekId" to JsonPrimitive(dekId), "attempts" to JsonPrimitive(attempts),
    )).toString()

    fun afterFailure(): StorageMarker = copy(attempts = minOf(attempts + 1, MAX_ATTEMPTS))
    fun afterSuccess(): StorageMarker = copy(attempts = 0)
    fun afterManualRetry(): StorageMarker = copy(attempts = 0)
    val mayAutoRetry: Boolean get() = attempts < MAX_ATTEMPTS

    /** Tryb wiążący po przerwanej migracji: `to-enc` → `plain`, `to-plain` → `enc`; pozostałe bez zmian. */
    fun rolledBack(): StorageMarker = when (mode) {
        StorageMode.TO_ENC -> copy(mode = StorageMode.PLAIN, attempts = minOf(attempts + 1, MAX_ATTEMPTS))
        StorageMode.TO_PLAIN -> copy(mode = StorageMode.ENC, attempts = minOf(attempts + 1, MAX_ATTEMPTS))
        else -> this
    }

    companion object {
        const val MAX_ATTEMPTS = 3

        /** Parsuje i waliduje strukturę: `v == 1`, `mode` z dozwolonego zbioru, `dekId >= 1`, `attempts` 0..3. Null = nieważny. */
        fun parse(text: String): StorageMarker? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            if (o["v"]?.jsonPrimitive?.num() != 1) return null
            val mode = StorageMode.values().firstOrNull { it.id == o["mode"]?.jsonPrimitive?.contentOrNull } ?: return null
            val dekId = o["dekId"]?.jsonPrimitive?.num() ?: return null
            val attempts = o["attempts"]?.jsonPrimitive?.num() ?: return null
            if (dekId < 1 || attempts !in 0..MAX_ATTEMPTS) return null
            StorageMarker(mode, dekId, attempts)
        }.getOrNull()

        /**
         * Ocena znacznika. [keyDekIds]: `dekId` odczytanych kopii `keys.bin` i `keys.bin.2`. `dekId` ze znacznika musi zgadzać się z
         * **którąkolwiek** kopią (niezgodna ze znacznikiem jest tylko diagnostyką, ale nie unieważnia znacznika, gdy druga się zgadza);
         * gdy nie ma żadnej odczytanej kopii, a istnieją pliki szyfrowane, znacznika nie da się zweryfikować (to sprawa klasy F, patrz [StorageProblem]).
         */
        fun evaluate(text: String?, hasEncOrKeys: Boolean, keyDekIds: Collection<Int>): MarkerState {
            val m = text?.let { parse(it) }
            if (m == null) return if (hasEncOrKeys) MarkerState.ReadOnlyEnc else MarkerState.FreshPlain
            if (keyDekIds.isNotEmpty() && m.dekId !in keyDekIds) return MarkerState.ReadOnlyEnc
            return MarkerState.Valid(m)
        }
    }
}

/** Klasy problemów z danymi, od najwyższego pierwszeństwa (plan, rozdział 4). */
enum class StorageProblem { KEYS_FILE_BROKEN, MARKER_INVALID, KEY_ALIAS_MISSING, KEY_UNAVAILABLE, NEWER_VERSION, FILE_CORRUPT;
    companion object { fun first(found: Collection<StorageProblem>): StorageProblem? = found.minByOrNull { it.ordinal } }
}

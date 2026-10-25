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
        /** `storage.json` ma ok. 70 znaków; większy tekst jest nieważny. */
        const val MAX_CHARS = 4096

        /** Parsuje i waliduje strukturę: `v == 1`, `mode` z dozwolonego zbioru, `dekId >= 1`, `attempts` 0..3. Null = nieważny. */
        fun parse(text: String): StorageMarker? = if (text.length > MAX_CHARS) null else runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            if (o["v"]?.jsonPrimitive?.num() != 1) return null
            val mode = StorageMode.values().firstOrNull { it.id == o["mode"]?.jsonPrimitive?.contentOrNull } ?: return null
            val dekId = o["dekId"]?.jsonPrimitive?.num() ?: return null
            val attempts = o["attempts"]?.jsonPrimitive?.num() ?: return null
            if (dekId < 1 || attempts !in 0..MAX_ATTEMPTS) return null
            StorageMarker(mode, dekId, attempts)
        }.getOrNull()

        /**
         * Ocena znacznika. [keyDekIds]: `dekId` odczytanych kopii `keys.bin` i `keys.bin.2`. `dekId` ze znacznika (w trybach `enc` i `to-plain`) musi zgadzać się z
         * **którąkolwiek** kopią (niezgodna ze znacznikiem jest tylko diagnostyką, ale nie unieważnia znacznika, gdy druga się zgadza);
         * gdy nie ma żadnej odczytanej kopii, a istnieją pliki szyfrowane, znacznika nie da się zweryfikować (to sprawa klasy F, patrz [StorageProblem]).
         */
        fun evaluate(text: String?, hasEncOrKeys: Boolean, keyDekIds: Collection<Int>): MarkerState {
            val m = text?.let { parse(it) }
            if (m == null) return if (hasEncOrKeys) MarkerState.ReadOnlyEnc else MarkerState.FreshPlain
            // w trybie plain (i podczas włączania) klucze nie są wiążące: pozostałość po wcześniejszej próbie nie unieważnia znacznika
            val keysBinding = m.mode == StorageMode.ENC || m.mode == StorageMode.TO_PLAIN
            if (keysBinding && keyDekIds.isNotEmpty() && m.dekId !in keyDekIds) return MarkerState.ReadOnlyEnc
            return MarkerState.Valid(m)
        }
    }
}

/**
 * Klasy problemów z danymi, od najwyższego pierwszeństwa (plan, rozdział 4), każda z własnym komunikatem (neutralnym płciowo).
 * C (uszkodzony plik) i E (błąd uwierzytelnienia treści przy działającym kluczu) są rozróżniane w miejscu odczytu ([StorageReader]).
 */
enum class StorageProblem(val message: String) {
    KEYS_FILE_BROKEN("Plik z kluczami szyfrowania jest uszkodzony, a jego zapasowa kopia też. Pliki z danymi zostały zachowane, ale bez tego pliku nie da się ich odczytać. Wczytaj ostatnią kopię zapasową z hasłem w Ustawieniach albo zacznij od nowa."),
    MARKER_INVALID("Znacznik trybu przechowywania jest uszkodzony. Aplikacja działa tylko do odczytu. Zrób kopię zapasową z hasłem i odtwórz znacznik."),
    KEY_ALIAS_MISSING("Te dane wyglądają na przeniesione z innego telefonu: w tym telefonie nie ma klucza, którym je zaszyfrowano, i nie da się ich odczytać. Wczytaj kopię zapasową z hasłem zrobioną na poprzednim telefonie."),
    KEY_UNAVAILABLE("Nie można odczytać zaszyfrowanych danych. Klucz zabezpieczający w telefonie jest niedostępny, ale pliki z danymi zostały zachowane. Wczytaj ostatnią kopię zapasową w Ustawieniach albo zacznij od nowa."),
    NEWER_VERSION("Dane zapisano w nowszej wersji aplikacji. Zaktualizuj aplikację."),
    DEK_ID_MISMATCH("Plik z danymi pochodzi z innego zestawu kluczy niż ten, który jest w telefonie. Pliki zostały zachowane. Wczytaj ostatnią kopię zapasową w Ustawieniach albo zacznij od nowa."),
    FILE_CORRUPT("Plik danych był uszkodzony. Uszkodzony plik zachowano w pamięci aplikacji. Wczytaj ostatnią kopię w Ustawieniach."),
    CONTENT_AUTH_FAILED("Treść pliku z danymi nie przeszła sprawdzenia (plik zmieniono albo uszkodzono). Plik zachowano w pamięci aplikacji. Wczytaj ostatnią kopię w Ustawieniach.");

    /** Klasy, po których zapisy danych są wyłączone, dopóki użytkownik nie zdecyduje (uszkodzone pliki osób C i E nie blokują reszty). */
    val blocksWrites: Boolean get() = this != FILE_CORRUPT && this != CONTENT_AUTH_FAILED

    companion object { fun first(found: Collection<StorageProblem>): StorageProblem? = found.minByOrNull { it.ordinal } }
}

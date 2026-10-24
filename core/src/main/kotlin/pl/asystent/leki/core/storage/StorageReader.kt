package pl.asystent.leki.core.storage

import pl.asystent.leki.core.storage.StorageNames.encFile
import pl.asystent.leki.core.storage.StorageNames.plainFile

/**
 * Reguła odczytu danych (plan, rozdział 3) **bez żadnego zapisu**: tak samo czytają proces z interfejsem i procesy w tle (widżet, alarmy).
 * Wiążący format wskazuje znacznik; przy braku lub nieważności znacznika przy istniejących `.enc` / `keys.bin` odczyt jest tylko do odczytu.
 */
object StorageReader {
    class PersonRead(val plain: ByteArray?, val problem: StorageProblem?, val fromPlainFallback: Boolean = false)
    class Result(val marker: MarkerState, val persons: Map<String, PersonRead>, val problem: StorageProblem?, val readOnly: Boolean)

    private fun readLimited(ops: FileOps, name: String, max: Int): ByteArray? = ops.read(name)?.takeIf { it.size <= max }

    fun readKeyHeaders(ops: FileOps): List<KeyHeader?> =
        listOf(StorageNames.KEYS, StorageNames.KEYS2).map { n -> ops.read(n)?.let { KeyFile.decode(it) } }

    fun read(ops: FileOps, wrap: KeyWrap, cipherOf: (ByteArray) -> DataCipher, ids: List<String>): Result {
        val names = ops.list()
        val hasKeys = StorageNames.KEYS in names || StorageNames.KEYS2 in names
        val hasEnc = names.any { StorageNames.isEncFile(it) }
        val headers = readKeyHeaders(ops)
        val markerText = ops.read(StorageNames.MARKER)?.let { if (it.size > StorageMarker.MAX_CHARS * 4) null else String(it, Charsets.UTF_8) }
        val state = StorageMarker.evaluate(markerText, hasEnc || hasKeys, headers.filterNotNull().map { it.dekId })

        val plainMode = state is MarkerState.FreshPlain ||
            (state is MarkerState.Valid && (state.marker.mode == StorageMode.PLAIN || state.marker.mode == StorageMode.TO_ENC))
        if (plainMode) {
            val persons = ids.associateWith { id ->
                val b = readLimited(ops, plainFile(id), DataFile.MAX_FILE_BYTES)
                if (b == null) PersonRead(null, null) else if (DataFile.classify(b) == DataFile.Format.PLAIN_JSON) PersonRead(b, null) else PersonRead(null, StorageProblem.FILE_CORRUPT)
            }
            return Result(state, persons, StorageProblem.first(persons.values.mapNotNull { it.problem }), false)
        }

        // tryb enc (wiążący albo tylko do odczytu przy nieważnym znaczniku)
        val readOnly = state is MarkerState.ReadOnlyEnc
        val markerDek = (state as? MarkerState.Valid)?.marker?.dekId ?: markerText?.let { StorageMarker.parse(it)?.dekId } ?: headers.filterNotNull().firstOrNull()?.dekId
        var keyProblem: StorageProblem? = null
        var cipher: DataCipher? = null
        if (markerDek == null || headers.all { it == null }) keyProblem = StorageProblem.KEYS_FILE_BROKEN
        else try { cipher = cipherOf(KeyFile.unwrapBinding(wrap, headers, markerDek).dek) }
        catch (e: KeyUnavailableException) { keyProblem = if (e.aliasMissing) StorageProblem.KEY_ALIAS_MISSING else StorageProblem.KEY_UNAVAILABLE }
        catch (e: KeyFile.KeysBrokenException) { keyProblem = StorageProblem.KEYS_FILE_BROKEN }
        val kp = keyProblem

        val persons = ids.associateWith { id ->
            val blob = readLimited(ops, encFile(id), DataFile.MAX_FILE_BYTES)
            val plain = readLimited(ops, plainFile(id), DataFile.MAX_FILE_BYTES)
            when {
                blob == null && plain != null -> PersonRead(plain, null, fromPlainFallback = true)   // stan wyjątkowy: czytaj jawny, nic nie kasuj
                blob == null -> PersonRead(null, null)
                kp != null -> PersonRead(null, kp)
                else -> try { PersonRead(DataFile.open(cipher!!, blob, DataFile.Kind.STATE, id, markerDek).plain, null) }
                catch (e: NewerVersionException) { PersonRead(null, StorageProblem.NEWER_VERSION) }
                catch (e: DekMismatchException) { PersonRead(null, StorageProblem.DEK_ID_MISMATCH) }
                catch (e: FormatException) { PersonRead(null, StorageProblem.FILE_CORRUPT) }            // klasa C
                catch (e: AuthFailedException) { PersonRead(null, StorageProblem.CONTENT_AUTH_FAILED) } // klasa E
                catch (e: KeyUnavailableException) { PersonRead(null, if (e.aliasMissing) StorageProblem.KEY_ALIAS_MISSING else StorageProblem.KEY_UNAVAILABLE) }
            }
        }
        val all = persons.values.mapNotNull { it.problem } + listOfNotNull(if (readOnly) StorageProblem.MARKER_INVALID else null, kp)
        return Result(state, persons, StorageProblem.first(all), readOnly)
    }
}

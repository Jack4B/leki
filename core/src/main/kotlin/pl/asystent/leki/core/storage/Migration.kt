package pl.asystent.leki.core.storage

import pl.asystent.leki.core.storage.StorageNames.encFile
import pl.asystent.leki.core.storage.StorageNames.plainFile

/**
 * Automat migracji jawne ⇄ zaszyfrowane (plan, rozdział 3), czysta logika nad [FileOps]. Odporna na zabicie procesu po każdej operacji
 * zapisu lub usunięcia: wiążący format wskazuje atomowy znacznik `storage.json`, a pliki kasowane są dopiero po zatwierdzeniu.
 * Wywoływana wyłącznie na pierwszym planie (nigdy z procesów w tle); zapisy danych są wtedy zablokowane przez wywołującego.
 */
object Migration {
    enum class Outcome { DONE, ROLLED_BACK }

    fun readMarker(ops: FileOps): StorageMarker? = ops.read(StorageNames.MARKER)?.let { if (it.size > StorageMarker.MAX_CHARS * 4) null else StorageMarker.parse(String(it, Charsets.UTF_8)) }

    /** Zapis znacznika z odczytem i ponowną walidacją; false, gdy odczyt nie potwierdził zapisu. */
    fun writeMarker(ops: FileOps, m: StorageMarker): Boolean {
        ops.write(StorageNames.MARKER, m.toJson().toByteArray(Charsets.UTF_8))
        return readMarker(ops) == m
    }

    private fun seal(c: DataCipher, dekId: Int, id: String, plain: ByteArray) = DataFile.seal(c, dekId, 1, DataFile.Kind.STATE, id, plain)

    private fun deletePlain(ops: FileOps, ids: List<String>) = ids.forEach { ops.delete(plainFile(it)); ops.delete(plainFile(it) + ".bak") }

    /** `plain` → `enc`. [start]: znacznik początkowy (`plain`, z licznikiem prób). */
    fun enable(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, start: StorageMarker): Outcome {
        if (!writeMarker(ops, StorageMarker(StorageMode.TO_ENC, dekId, start.attempts))) return Outcome.ROLLED_BACK
        for (id in ids) {
            val plain = ops.read(plainFile(id)) ?: continue
            if (DataFile.classify(plain) != DataFile.Format.PLAIN_JSON) return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.PLAIN)
            ops.write(encFile(id), seal(cipher, dekId, id, plain))
        }
        for (id in ids) {
            val plain = ops.read(plainFile(id)) ?: continue
            val ok = runCatching { DataFile.open(cipher, ops.read(encFile(id)) ?: error("brak"), DataFile.Kind.STATE, id, dekId).plain.contentEquals(plain) }.getOrDefault(false)
            if (!ok) { ops.delete(encFile(id)); return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.PLAIN) }
        }
        if (!writeMarker(ops, StorageMarker(StorageMode.ENC, dekId, 0))) return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.PLAIN)
        deletePlain(ops, ids)                    // sprzątanie po zatwierdzeniu
        ops.delete(pl.asystent.leki.core.Profiles.BEFORE_PEOPLE)
        return Outcome.DONE
    }

    /** `enc` → `plain` (lustrzanie). */
    fun disable(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, start: StorageMarker): Outcome {
        if (!writeMarker(ops, StorageMarker(StorageMode.TO_PLAIN, dekId, start.attempts))) return Outcome.ROLLED_BACK
        for (id in ids) {
            val blob = ops.read(encFile(id)) ?: continue
            val plain = runCatching { DataFile.open(cipher, blob, DataFile.Kind.STATE, id, dekId).plain }.getOrNull() ?: return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.ENC)
            ops.write(plainFile(id), plain)
        }
        for (id in ids) {
            val blob = ops.read(encFile(id)) ?: continue
            val expected = DataFile.open(cipher, blob, DataFile.Kind.STATE, id, dekId).plain
            if (!(ops.read(plainFile(id))?.contentEquals(expected) ?: false)) { ops.delete(plainFile(id)); return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.ENC) }
        }
        if (!writeMarker(ops, StorageMarker(StorageMode.PLAIN, dekId, 0))) return rollBack(ops, cipher, dekId, ids, start.afterFailure(), StorageMode.ENC)
        ids.forEach { ops.delete(encFile(it)); ops.delete(encFile(it) + ".bak") }
        return Outcome.DONE
    }

    private fun rollBack(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, m: StorageMarker, mode: StorageMode): Outcome {
        writeMarker(ops, StorageMarker(mode, dekId, m.attempts))
        deleteCandidates(ops, cipher, ids, mode)
        return Outcome.ROLLED_BACK
    }

    /** Kasuje niezatwierdzonych kandydatów po cofnięciu; plik, którego nie da się zaklasyfikować przez błąd klucza, zostaje. */
    private fun deleteCandidates(ops: FileOps, cipher: DataCipher, ids: List<String>, binding: StorageMode) {
        for (id in ids) {
            if (binding == StorageMode.PLAIN) {
                val blob = ops.read(encFile(id)) ?: continue
                val keyError = try { DataFile.open(cipher, blob, DataFile.Kind.STATE, id); false } catch (e: KeyUnavailableException) { true } catch (e: Exception) { false }
                if (!keyError) { ops.delete(encFile(id)); ops.delete(encFile(id) + ".bak") }
            } else { ops.delete(plainFile(id)); ops.delete(plainFile(id) + ".bak") }
        }
    }

    /**
     * Po restarcie: znacznik `to-*` oznacza przerwaną migrację. Atomowo wraca do trybu wiążącego (`attempts = min(attempts + 1, 3)`),
     * usuwa kandydatów i dopiero potem wolno odblokować zapisy. Zwraca nowy znacznik albo null, gdy nie było czego cofać.
     */
    fun recoverAfterRestart(ops: FileOps, cipher: DataCipher, ids: List<String>): StorageMarker? {
        val m = readMarker(ops) ?: return null
        if (m.mode != StorageMode.TO_ENC && m.mode != StorageMode.TO_PLAIN) return null
        val back = m.rolledBack()
        writeMarker(ops, back)
        deleteCandidates(ops, cipher, ids, back.mode)
        return back
    }
}


package pl.asystent.leki.core.storage

import pl.asystent.leki.core.storage.StorageNames.encFile
import pl.asystent.leki.core.storage.StorageNames.plainFile

/**
 * Automat migracji jawne ⇄ zaszyfrowane (plan, rozdział 3), czysta logika nad [FileOps]. Odporna na zabicie procesu po każdej operacji
 * zapisu lub usunięcia: wiążący format wskazuje atomowy znacznik `storage.json`, a pliki kasowane są dopiero po zatwierdzeniu.
 * Wywoływana wyłącznie na pierwszym planie (nigdy z procesów w tle); zapisy danych są wtedy zablokowane przez wywołującego.
 */
object Migration {
    enum class Outcome {
        DONE,
        /** Cofnięto do trybu wiążącego, zapisy można odblokować. */
        ROLLED_BACK,
        /** Nie udało się zapisać znacznika przy cofaniu: błąd do zgłoszenia, **zapisy zostają zablokowane**, nic nie skasowano. */
        ROLLBACK_FAILED,
        /** Włączanie niedozwolone: znacznik inny niż `plain` (lub nieważny) albo istnieją pliki `.enc`. Nic nie zmieniono. */
        NOT_ALLOWED,
        /** Nie udało się utworzyć i sprawdzić kluczy (krok 0). Migracji nie zaczęto. */
        KEYS_FAILED,
    }

    enum class Prepared { OK, NOT_ALLOWED, VERIFY_FAILED }

    /** Wynik [recoverAfterRestart]: [writesMayResume] = false oznacza błąd zapisu znacznika (zapisy zostają zablokowane). */
    class Recovery(val marker: StorageMarker?, val writesMayResume: Boolean)

    fun readMarker(ops: FileOps): StorageMarker? = ops.read(StorageNames.MARKER)?.let { if (it.size > StorageMarker.MAX_CHARS * 4) null else StorageMarker.parse(String(it, Charsets.UTF_8)) }

    /** Zapis znacznika z odczytem i ponowną walidacją; false przy błędzie dysku albo gdy odczyt nie potwierdził zapisu. */
    fun writeMarker(ops: FileOps, m: StorageMarker): Boolean = try {
        ops.write(StorageNames.MARKER, m.toJson().toByteArray(Charsets.UTF_8))
        readMarker(ops) == m
    } catch (e: java.io.IOException) { false }

    private fun seal(c: DataCipher, dekId: Int, id: String, plain: ByteArray) = DataFile.seal(c, dekId, 1, DataFile.Kind.STATE, id, plain)

    private fun openEnc(ops: FileOps, cipher: DataCipher, dekId: Int?, id: String): ByteArray? =
        ops.read(encFile(id))?.let { runCatching { DataFile.open(cipher, it, DataFile.Kind.STATE, id, dekId).plain }.getOrNull() }

    private fun deletePlain(ops: FileOps, ids: List<String>) = ids.forEach { ops.delete(plainFile(it)); ops.delete(plainFile(it) + ".bak") }

    /**
     * Krok 0 włączania: **najpierw znacznik `plain` z nowym `dekId`, dopiero potem `keys.bin` i `keys.bin.2`**, każdy zapisany i sprawdzony
     * (`unwrap` po zapisie). W trybie `plain` klucze nie są wiążące, więc przerwanie w dowolnym miejscu (także przy pozostałości
     * po wcześniejszej próbie z innym `dekId`) nie unieważnia znacznika. False = weryfikacja nie przeszła, migracji nie zaczynamy.
     */
    fun prepareKeys(ops: FileOps, wrap: KeyWrap, alias: String, dekId: Int, dek: ByteArray, attempts: Int): Prepared {
        // guard: tylko przy znaczniku plain albo jego braku i bez plików .enc (inaczej nadpisalibyśmy klucze istniejących danych)
        val raw = ops.read(StorageNames.MARKER)
        val marker = readMarker(ops)
        if (raw != null && (marker == null || marker.mode != StorageMode.PLAIN)) return Prepared.NOT_ALLOWED
        if (ops.list().any { StorageNames.isEncFile(it) }) return Prepared.NOT_ALLOWED
        if (!writeMarker(ops, StorageMarker(StorageMode.PLAIN, dekId, attempts))) return Prepared.VERIFY_FAILED
        for (name in listOf(StorageNames.KEYS, StorageNames.KEYS2)) {
            ops.write(name, KeyFile.encode(wrap, alias, dekId, dek))
            val h = ops.read(name)?.let { KeyFile.decode(it) } ?: return Prepared.VERIFY_FAILED
            if (!runCatching { KeyFile.unwrap(wrap, h).contentEquals(dek) }.getOrDefault(false)) return Prepared.VERIFY_FAILED
        }
        return Prepared.OK
    }

    /**
     * Całe włączanie: krok 0 ([prepareKeys]) i migracja. **Zawsze od kroku 0 z nowo wygenerowanym DEK** ([dek]): po restarcie między
     * [prepareKeys] a [enable] klucze zostały skasowane przy starcie ([recoverAfterRestart]), więc wywołujący nie może używać DEK
     * z pamięci z poprzedniego przebiegu; każde wywołanie przygotowuje klucze od nowa i szyfruje tym, który właśnie zapisało.
     */
    fun enableWithKeys(ops: FileOps, wrap: KeyWrap, cipherOf: (ByteArray) -> DataCipher, alias: String, dekId: Int, dek: ByteArray, ids: List<String>): Outcome {
        val attempts = readMarker(ops)?.takeIf { it.mode == StorageMode.PLAIN }?.attempts ?: 0
        when (prepareKeys(ops, wrap, alias, dekId, dek, attempts)) {
            Prepared.OK -> {}
            Prepared.NOT_ALLOWED -> return Outcome.NOT_ALLOWED
            Prepared.VERIFY_FAILED -> return Outcome.KEYS_FAILED
        }
        return enable(ops, cipherOf(dek), dekId, ids, StorageMarker(StorageMode.PLAIN, dekId, attempts))
    }

    /** `plain` → `enc`. [start]: znacznik początkowy (`plain`, z licznikiem prób). */
    fun enable(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, start: StorageMarker): Outcome {
        if (!writeMarker(ops, StorageMarker(StorageMode.TO_ENC, dekId, start.attempts))) return Outcome.ROLLBACK_FAILED
        val failed = start.afterFailure()
        for (id in ids) {
            val plain = ops.read(plainFile(id)) ?: continue
            if (DataFile.classify(plain) != DataFile.Format.PLAIN_JSON) return rollBack(ops, cipher, dekId, ids, failed, StorageMode.PLAIN)
            ops.write(encFile(id), seal(cipher, dekId, id, plain))
        }
        for (id in ids) {
            val plain = ops.read(plainFile(id)) ?: continue
            if (openEnc(ops, cipher, dekId, id)?.contentEquals(plain) != true) { ops.delete(encFile(id)); return rollBack(ops, cipher, dekId, ids, failed, StorageMode.PLAIN) }
        }
        if (!writeMarker(ops, StorageMarker(StorageMode.ENC, dekId, 0))) return rollBack(ops, cipher, dekId, ids, failed, StorageMode.PLAIN)
        deletePlain(ops, ids)                    // sprzątanie po zatwierdzeniu (po przerwaniu dokończy [cleanupPlainLeftovers])
        ops.delete(pl.asystent.leki.core.Profiles.BEFORE_PEOPLE)
        return Outcome.DONE
    }

    /** `enc` → `plain` (lustrzanie). Na końcu kasuje `.enc` oraz `keys.bin` i `keys.bin.2`. */
    fun disable(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, start: StorageMarker): Outcome {
        if (!writeMarker(ops, StorageMarker(StorageMode.TO_PLAIN, dekId, start.attempts))) return Outcome.ROLLBACK_FAILED
        val failed = start.afterFailure()
        for (id in ids) {
            if (ops.read(encFile(id)) == null) continue
            val plain = openEnc(ops, cipher, dekId, id) ?: return rollBack(ops, cipher, dekId, ids, failed, StorageMode.ENC)
            ops.write(plainFile(id), plain)
        }
        for (id in ids) {
            if (ops.read(encFile(id)) == null) continue
            val expected = openEnc(ops, cipher, dekId, id)
            if (expected == null || ops.read(plainFile(id))?.contentEquals(expected) != true) {
                if (expected != null) ops.delete(plainFile(id))
                return rollBack(ops, cipher, dekId, ids, failed, StorageMode.ENC)
            }
        }
        if (!writeMarker(ops, StorageMarker(StorageMode.PLAIN, dekId, 0))) return rollBack(ops, cipher, dekId, ids, failed, StorageMode.ENC)
        ids.forEach { ops.delete(encFile(it)); ops.delete(encFile(it) + ".bak") }
        ops.delete(StorageNames.KEYS); ops.delete(StorageNames.KEYS2)   // klucze są niepotrzebne w trybie plain
        return Outcome.DONE
    }

    private fun rollBack(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, m: StorageMarker, mode: StorageMode): Outcome {
        if (!writeMarker(ops, StorageMarker(mode, dekId, m.attempts))) return Outcome.ROLLBACK_FAILED   // nic nie kasujemy, zapisy zostają zablokowane
        deleteCandidates(ops, cipher, dekId, ids, mode)
        return Outcome.ROLLED_BACK
    }

    /**
     * Kasuje niezatwierdzonych kandydatów po cofnięciu. Do `plain`: `.enc` poza plikiem, którego nie da się zaklasyfikować przez błąd klucza.
     * Do `enc`: jawny `state*.json` **tylko osoby, której `.enc` jest czytelny** (osoba bez `.enc` zostaje przy jawnym pliku).
     */
    private fun deleteCandidates(ops: FileOps, cipher: DataCipher, dekId: Int, ids: List<String>, binding: StorageMode) {
        for (id in ids) {
            if (binding == StorageMode.PLAIN) {
                val blob = ops.read(encFile(id)) ?: continue
                val keyError = try { DataFile.open(cipher, blob, DataFile.Kind.STATE, id); false } catch (e: KeyUnavailableException) { true } catch (e: Exception) { false }
                if (!keyError) { ops.delete(encFile(id)); ops.delete(encFile(id) + ".bak") }
            } else if (openEnc(ops, cipher, dekId, id) != null) { ops.delete(plainFile(id)); ops.delete(plainFile(id) + ".bak") }
        }
    }

    /**
     * Po restarcie: znacznik `to-*` oznacza przerwaną migrację. Atomowo wraca do trybu wiążącego (`attempts = min(attempts + 1, 3)`),
     * usuwa kandydatów i dopiero potem wolno odblokować zapisy ([Recovery.writesMayResume]). Gdy zapis znacznika się nie uda, zapisy
     * zostają zablokowane i nic nie jest kasowane. [Recovery.marker] = null, gdy nie było czego cofać.
     */
    fun recoverAfterRestart(ops: FileOps, cipher: DataCipher, ids: List<String>): Recovery {
        val m = readMarker(ops) ?: return Recovery(null, true)
        if (m.mode == StorageMode.PLAIN) {                     // dokończenie sprzątania po cofnięciu albo po wyłączeniu (powtarzalne)
            deleteCandidates(ops, cipher, m.dekId, ids, StorageMode.PLAIN)
            if (ids.none { ops.read(encFile(it)) != null }) { ops.delete(StorageNames.KEYS); ops.delete(StorageNames.KEYS2) }   // klucze niepotrzebne w trybie plain
            return Recovery(m, true)
        }
        if (m.mode != StorageMode.TO_ENC && m.mode != StorageMode.TO_PLAIN) return Recovery(null, true)
        val back = m.rolledBack()
        if (!writeMarker(ops, back)) return Recovery(m, false)
        deleteCandidates(ops, cipher, back.dekId, ids, back.mode)
        if (back.mode == StorageMode.PLAIN && ids.none { ops.read(encFile(it)) != null }) { ops.delete(StorageNames.KEYS); ops.delete(StorageNames.KEYS2) }
        return Recovery(back, true)
    }

    /**
     * Sprzątanie na pierwszym planie w trybie `enc` (po przerwaniu między znacznikiem `enc` a [deletePlain]): usuwa `state*.json` (i `.bak`) osoby
     * tylko wtedy, gdy jej `.enc` odszyfrowuje się i przechodzi uwierzytelnienie z oczekiwanym `dekId`; zapas sprzed osób znika razem z nimi.
     * Plik osoby bez czytelnego `.enc` (brak, błąd klucza) zostaje nietknięty. Zwraca liczbę usuniętych jawnych plików.
     */
    fun cleanupPlainLeftovers(ops: FileOps, cipher: DataCipher, ids: List<String>): Int {
        val marker = readMarker(ops)?.takeIf { it.mode == StorageMode.ENC } ?: return 0
        val dekId = marker.dekId          // świeżo odczytany znacznik, nie parametr wywołującego
        var n = 0
        for (id in ids) {
            if (ops.read(plainFile(id)) == null || openEnc(ops, cipher, dekId, id) == null) continue
            deletePlain(ops, listOf(id)); n++
        }
        if (ids.all { ops.read(plainFile(it)) == null }) ops.delete(pl.asystent.leki.core.Profiles.BEFORE_PEOPLE)
        return n
    }
}

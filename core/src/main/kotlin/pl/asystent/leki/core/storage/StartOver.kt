package pl.asystent.leki.core.storage

/**
 * „Zaczynam od nowa” po utracie klucza (plan, rozdział 1): zawsze nowy `dekId` (n+1). Kolejność odporna na zabicie procesu:
 * (1) RENAME zmiana nazw `state*.enc` na `*.nieczytelny.enc`, (2) KEYS zapis i weryfikacja `keys.bin`, (3) MARKER zapis znacznika
 * z nowym `dekId`, (4) KEYS2 zapis i weryfikacja `keys.bin.2`. Postęp jest zapisywany atomowo w pliku [PENDING_FILE]; jego obecność oznacza
 * operację w toku. **Po zabiciu procesu po kroku (1) zapisy są zablokowane do ukończenia kroków (2) i (3)**, nawet po restarcie.
 */
object StartOver {
    const val PENDING_FILE = "startover.pending"
    enum class Step { RENAME, KEYS, MARKER, KEYS2 }

    fun encode(done: Set<Step>): String = done.sortedBy { it.ordinal }.joinToString(",") { it.name }
    fun decode(text: String): Set<Step>? {
        if (text.isBlank()) return emptySet()
        val steps = text.trim().split(',').map { n -> Step.values().firstOrNull { it.name == n } ?: return null }
        return steps.toSet().takeIf { d -> steps.size == d.size && d.all { s -> Step.values().take(s.ordinal).all { it in d } } }
    }

    /**
     * Zapisy danych są zablokowane, **gdy istnieje plik [PENDING_FILE]**, niezależnie od jego treści: także pusty (proces zabity w trakcie
     * kroku 1, zanim zapisano postęp) i nieczytelny. [pendingText] = treść pliku albo null, gdy go nie ma. Blokadę zdejmuje dopiero usunięcie
     * pliku po ukończeniu wszystkich kroków (pierwszy plan dokańcza operację do przodu).
     */
    fun writesBlocked(pendingText: String?): Boolean = pendingText != null

    /** Pozostałe kroki dla pliku postępu; nieczytelny postęp (null) = zaczynamy od kroku (1) jeszcze raz (zmiana nazw jest powtarzalna). */
    fun remainingFor(pendingText: String): List<Step> = remaining(decode(pendingText) ?: emptySet())

    /** Pozostałe kroki do wykonania (dokończenie do przodu na pierwszym planie po restarcie). */
    fun remaining(done: Set<Step>): List<Step> = Step.values().filter { it !in done }

    class Result(val dek: ByteArray, val dekId: Int)

    /**
     * Wykonuje albo **dokańcza** „Zaczynam od nowa” (plan, rozdział 1). Postęp w [PENDING_FILE] zapisywany atomowo po każdym kroku;
     * plik powstaje przed krokiem 1 (pusty = nic jeszcze nie zrobiono, ale zapisy już są zablokowane). Nowy `dekId` = n+1 ([currentDekId] + 1),
     * nowy alias z [KeyFile.nextAlias]; [createKey] tworzy klucz w Keystore o podanym aliasie. Przy wznowieniu kroki wykonane są pomijane,
     * a `dekId` i DEK bierze się z już zapisanego `keys.bin`. Zwraca wynik albo null, gdy weryfikacja kluczy nie przeszła (plik postępu zostaje).
     */
    fun execute(ops: FileOps, wrap: KeyWrap, createKey: (String) -> Unit, keystoreAliases: Collection<String>, newDek: ByteArray, currentDekId: Int, ids: List<String>): Result? {
        val pending = ops.read(PENDING_FILE)?.let { String(it, Charsets.UTF_8) }
        var done = (pending?.let { decode(it) }) ?: emptySet()
        if (pending == null) ops.write(PENDING_FILE, ByteArray(0))
        fun mark(step: Step) { done = done + step; ops.write(PENDING_FILE, encode(done).toByteArray(Charsets.UTF_8)) }

        if (Step.RENAME !in done) {
            for (id in ids) {
                val src = StorageNames.encFile(id); val dst = StorageNames.unreadableFile(id)
                val blob = ops.read(src) ?: continue
                if (ops.read(dst) == null) ops.write(dst, blob)
                ops.delete(src); ops.delete("$src.bak")
            }
            mark(Step.RENAME)
        }
        if (Step.KEYS !in done) {
            val alias = KeyFile.nextAlias(keystoreAliases, listOf(StorageNames.KEYS, StorageNames.KEYS2).mapNotNull { n -> ops.read(n)?.let { KeyFile.decode(it)?.alias } })
            createKey(alias)
            ops.write(StorageNames.KEYS, KeyFile.encode(wrap, alias, currentDekId + 1, newDek))
            val h = ops.read(StorageNames.KEYS)?.let { KeyFile.decode(it) } ?: return null
            if (!runCatching { KeyFile.unwrap(wrap, h).contentEquals(newDek) }.getOrDefault(false)) return null
            mark(Step.KEYS)
        }
        val header = ops.read(StorageNames.KEYS)?.let { KeyFile.decode(it) } ?: return null
        if (Step.MARKER !in done) {
            if (!Migration.writeMarker(ops, StorageMarker(StorageMode.ENC, header.dekId, 0))) return null
            mark(Step.MARKER)
        }
        if (Step.KEYS2 !in done) {
            ops.write(StorageNames.KEYS2, ops.read(StorageNames.KEYS) ?: return null)
            val h2 = ops.read(StorageNames.KEYS2)?.let { KeyFile.decode(it) } ?: return null
            runCatching { KeyFile.unwrap(wrap, h2) }.getOrNull() ?: return null
            mark(Step.KEYS2)
        }
        val dek = runCatching { KeyFile.unwrap(wrap, header) }.getOrNull() ?: return null
        ops.delete(PENDING_FILE)
        return Result(dek, header.dekId)
    }
}

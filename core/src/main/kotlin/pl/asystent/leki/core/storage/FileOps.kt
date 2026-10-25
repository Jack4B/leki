package pl.asystent.leki.core.storage

/**
 * Dostęp do katalogu danych bez Androida. W aplikacji: `AtomicFile` w `filesDir`; w testach pamięć z wstrzykiwaniem przerwania.
 * [write] jest atomowe (albo cały nowy plik, albo poprzedni). [read] zwraca null, gdy pliku nie ma.
 */
interface FileOps {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun list(): Collection<String>
}

object StorageNames {
    const val MARKER = "storage.json"
    const val KEYS = "keys.bin"
    const val KEYS2 = "keys.bin.2"
    private val ENC = Regex("state(-p\\d{1,2})?\\.enc")
    fun encFile(id: String): String = if (id == "p1") "state.enc" else "state-$id.enc"
    fun plainFile(id: String): String = if (id == "p1") "state.json" else "state-$id.json"
    fun isEncFile(name: String): Boolean = ENC.matches(name)

    const val PENDING = StartOver.PENDING_FILE
    private val AUX = Regex(".*\\.(uszkodzony|odrzucony)\\.(json|enc)|.*\\.nieczytelny\\.enc|.*\\.(bak|new)")

    /** Nazwa pliku `.nieczytelny.enc` osoby (po „Zaczynam od nowa”). */
    fun unreadableFile(id: String): String = if (id == "p1") "state.nieczytelny.enc" else "state-$id.nieczytelny.enc"

    /** Wszystko, co szyfrowanie zostawia w katalogu danych i co „Usuń wszystkie dane” kasuje: `.enc`, klucze, znacznik, postęp, pliki pomocnicze i pozostałości `AtomicFile`. */
    fun isEncryptionRelated(name: String): Boolean =
        isEncFile(name) || name == KEYS || name == KEYS2 || name == MARKER || name == PENDING || name == pl.asystent.leki.core.Profiles.BEFORE_PEOPLE || AUX.matches(name)
}

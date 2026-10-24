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
}

package pl.asystent.leki.core.storage

/** Zapis pliku osoby w trybie `enc` (używa go `Store`; wydzielony, żeby ścieżkę zapisu z odbiornika dało się testować na JVM). */
object StorageWriter {
    /** Generacja = sekundy od epoki jako uint32 (rośnie z czasem; tylko diagnostyka, patrz plan). */
    fun generation(nowMillis: Long): Long = (nowMillis / 1000) and 0xFFFFFFFFL

    fun writePerson(ops: FileOps, cipher: DataCipher, dekId: Int, id: String, plain: ByteArray, nowMillis: Long = System.currentTimeMillis()) {
        ops.write(StorageNames.encFile(id), DataFile.seal(cipher, dekId, generation(nowMillis), DataFile.Kind.STATE, id, plain))
    }
}

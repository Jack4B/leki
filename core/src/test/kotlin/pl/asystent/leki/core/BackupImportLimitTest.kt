package pl.asystent.leki.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupImportLimitTest {
    @Test fun acceptsFileAtLimit() {
        val bytes = ByteArray(Backup.MAX_IMPORT_BYTES) { 'a'.code.toByte() }
        assertEquals(Backup.MAX_IMPORT_BYTES, Backup.decodeImport(bytes).length)
    }

    @Test fun rejectsFileOverLimit() {
        val e = assertFailsWith<Backup.TooLargeException> { Backup.decodeImport(ByteArray(Backup.MAX_IMPORT_BYTES + 1)) }
        assertEquals(true, e.message!!.contains("20 MB"))
    }

    @Test fun smallBackupStillParses() {
        val text = Backup.decodeImport(Fixtures::class.java.getResource("/fixtures/export.json")!!.readBytes())
        Backup.parseState(text)
    }
}

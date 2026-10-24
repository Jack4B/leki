package pl.asystent.leki

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.AutoBackupPlan
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.BackupCrypto
import java.security.KeyStore
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Automatyczna kopia zapasowa do folderu wybranego przez użytkownika (systemowy wybór folderu, bez uprawnień do pamięci i bez internetu):
 * po zapisie danych, gdy minął wybrany odstęp (dzień albo tydzień), w tle powstaje plik z datą w nazwie; zostaje [KEEP] ostatnich.
 * Kopia może być szyfrowana hasłem (to samo szyfrowanie co ręczna zaszyfrowana kopia); hasło jest przechowywane zaszyfrowane kluczem z Android Keystore
 * i nigdy nie trafia do kopii zapasowej ani do pliku. Ustawienia tej funkcji są tylko na tym telefonie.
 */
object AutoBackup {
    private const val PREFS = "autobackup"
    private const val KEY_ALIAS = "leki_autobackup_key"
    private val running = AtomicBoolean(false)
    /** Kopie robione po jednej naraz (automatyczna i ręczna „Zrób teraz” nie mogą pisać równolegle do tego samego folderu). */
    private val lock = Any()

    data class Config(val folder: Uri?, val intervalDays: Int, val encrypted: Boolean, val hasPassword: Boolean, val lastDay: Long, val status: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(
            p.getString("uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }, p.getInt("interval", 1).coerceIn(1, 30),
            p.getBoolean("enc", false), p.getString("pw", null) != null, p.getLong("last", -1L), p.getString("status", "").orEmpty(),
        )
    }

    /** Czytelna nazwa folderu (np. „Documents/Kopie” z identyfikatora drzewa dokumentów); dla dostawców bez takiego zapisu (chmura) ogólna. */
    fun folderLabel(uri: Uri): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        if (':' in id) id.substringAfter(':').ifEmpty { "pamięć telefonu" } else "wybrany folder"
    }.getOrDefault("wybrany folder")

    fun setFolder(ctx: Context, uri: Uri) {
        // poprzedni folder oddaje uprawnienie (system ogranicza liczbę trwałych uprawnień)
        config(ctx).folder?.takeIf { it != uri }?.let { old -> runCatching { ctx.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs(ctx).edit().putString("uri", uri.toString()).putLong("last", -1L).putString("status", "").apply()
    }

    fun setInterval(ctx: Context, days: Int) { prefs(ctx).edit().putInt("interval", days).apply() }

    /** Szyfrowanie włączone hasłem albo wyłączone ([password] = null). */
    fun setEncryption(ctx: Context, password: String?) {
        val e = prefs(ctx).edit()
        if (password == null) e.putBoolean("enc", false).remove("pw") else e.putBoolean("enc", true).putString("pw", seal(password))
        e.apply()
    }

    fun disable(ctx: Context) {
        val c = config(ctx)
        c.folder?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(ctx).edit().clear().apply()
    }

    fun due(ctx: Context, today: LocalDate): Boolean {
        val c = config(ctx)
        return c.folder != null && AutoBackupPlan.due(c.lastDay, today, c.intervalDays, prefs(ctx).getLong("attempt", 0L), System.currentTimeMillis())
    }

    /** Wywoływane po każdym zapisie danych: gdy kopia jest należna, robi ją w osobnym wątku (szyfrowanie trwa chwilę), a po sukcesie woła [onDone] z dzisiejszą datą. */
    fun maybeRun(ctx: Context, s: AppState, onDone: (LocalDate) -> Unit) {
        val today = LocalDate.now()
        if (!due(ctx, today) || !running.compareAndSet(false, true)) return
        Thread {
            try { if (runNow(ctx, s).isSuccess) onDone(today) } finally { running.set(false) }
        }.start()
    }

    /** Robi kopię teraz (wywołuj poza głównym wątkiem). Zwraca nazwę pliku albo błąd z komunikatem po polsku. */
    fun runNow(ctx: Context, s: AppState): Result<String> = synchronized(lock) {
        prefs(ctx).edit().putLong("attempt", System.currentTimeMillis()).apply()
        val r = runCatching {
            val c = config(ctx)
            val tree = c.folder ?: error("Nie wybrano folderu.")
            val today = LocalDate.now()
            var content = Backup.export(s.copy(lastBackup = today))
            if (c.encrypted) {
                val pw = prefs(ctx).getString("pw", null)?.let { open(it) } ?: error("Brak hasła do szyfrowania. Ustaw je ponownie.")
                content = BackupCrypto.encrypt(pw, content)
            }
            val name = AutoBackupPlan.fileName(today, c.encrypted)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // najpierw zapis pod nazwą roboczą, dopiero potem podmiana: nieudany zapis nie kasuje dzisiejszej, dobrej kopii
            val tmpName = name + ".tmp"
            children(ctx, tree).filter { it.second == tmpName }.forEach { delete(ctx, tree, it.first) }
            val tmp = DocumentsContract.createDocument(ctx.contentResolver, root, "application/octet-stream", tmpName) ?: error("Nie udało się utworzyć pliku w wybranym folderze.")
            try {
                ctx.contentResolver.openOutputStream(tmp, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Nie udało się zapisać pliku.")
                children(ctx, tree).filter { it.second == name }.forEach { delete(ctx, tree, it.first) }
                // nie każdy dostawca plików pozwala zmieniać nazwy: wtedy tworzymy plik docelowy od nowa, a roboczy usuwamy
                val renamed = runCatching { DocumentsContract.renameDocument(ctx.contentResolver, tmp, name) }.getOrNull()
                if (renamed == null) {
                    val target = DocumentsContract.createDocument(ctx.contentResolver, root, "application/json", name) ?: error("Nie udało się utworzyć pliku w wybranym folderze.")
                    ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Nie udało się zapisać pliku.")
                    runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, tmp) }
                }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, tmp) }   // po udanej podmianie dokument roboczy już nie istnieje
                throw e
            }
            val names = children(ctx, tree)
            AutoBackupPlan.toDelete(names.map { it.second }).forEach { n -> names.firstOrNull { it.second == n }?.let { delete(ctx, tree, it.first) } }
            prefs(ctx).edit().putLong("last", today.toEpochDay()).putString("status", "").apply()
            name
        }
        r.onFailure { e ->
            android.util.Log.e("AutoBackup", "Automatyczna kopia nie powiodła się", e)
            prefs(ctx).edit().putString("status", "Ostatnia próba nie powiodła się: " + (e.message ?: "nieznany błąd")).apply()
        }
        r
    }

    private fun children(ctx: Context, tree: Uri): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<String, String>>()
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    private fun delete(ctx: Context, tree: Uri, docId: String) {
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId)) }
    }

    // ---------- hasło chronione kluczem z Android Keystore ----------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build(),
        )
        return gen.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(ct)
    }

    private fun open(sealed: String): String? = runCatching {
        val (iv, ct) = sealed.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        String(c.doFinal(Base64.getDecoder().decode(ct)), Charsets.UTF_8)
    }.getOrNull()
}

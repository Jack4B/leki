package pl.asystent.leki

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.File

/** Drobne operacje systemowe: schowek, udostępnianie plików i druk. */
object Platform {
    /** Kopiuje tekst do schowka; false, gdy się nie udało (np. dane przekraczają limit schowka, około 1 MB). */
    fun copyText(ctx: Context, label: String, text: String): Boolean = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        // dane zdrowotne: Android 13+ nie pokazuje treści w podglądzie schowka
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        true
    } catch (e: Exception) { false }

    /** Usuwa pliki tymczasowe udostępnienia (mogą zawierać dane zdrowotne); wołane przy starcie aplikacji. */
    fun clearShareCache(ctx: Context) {
        runCatching { File(ctx.cacheDir, "share").listFiles()?.forEach { it.delete() } }
    }

    /** Tekst ze schowka (pusty, gdy schowek jest pusty albo nie zawiera tekstu). */
    fun pasteText(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    }

    /** Udostępnia tekst jako plik przez systemowe „Udostępnij” (Dysk, Filen, e-mail, komunikatory). */
    fun shareFile(ctx: Context, fileName: String, content: String, mime: String, title: String) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000) it.delete() }
        val f = File(dir, fileName)
        f.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun writeToUri(ctx: Context, uri: Uri, content: String) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: error("Nie można zapisać pliku")
    }

    /** Czyta plik kopii zapasowej strumieniowo z limitem [pl.asystent.leki.core.Backup.MAX_IMPORT_BYTES]; za duży plik: [pl.asystent.leki.core.Backup.TooLargeException]. */
    fun readFromUri(ctx: Context, uri: Uri): String =
        pl.asystent.leki.core.Backup.decodeImport(readBytesLimited(ctx, uri, pl.asystent.leki.core.Backup.MAX_IMPORT_BYTES))

    /** Czyta plik z limitem rozmiaru: zwraca najwyżej [max] + 1 bajtów, więc za duży plik da się rozpoznać bez wczytywania go w całości do pamięci. */
    fun readBytesLimited(ctx: Context, uri: Uri, max: Int): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (out.size() <= max) {
                val n = input.read(buf, 0, minOf(buf.size, max + 1 - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: error("Nie można otworzyć pliku")

    /** Nazwa pliku z systemowego okna wyboru (może się nie udać, wtedy null). */
    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Drukuje dokument HTML lub zapisuje go jako PDF (okno druku Androida). */
    // WebView musi żyć do końca zadania druku, więc trzymamy referencję (kontekst aplikacji, żeby nie wyciekała aktywność)
    private var printWeb: WebView? = null

    fun printHtml(activity: Activity, title: String, html: String, landscape: Boolean = false) {
        printWeb?.destroy()
        val web = WebView(activity.applicationContext)
        printWeb = web
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().setMediaSize(if (landscape) PrintAttributes.MediaSize.ISO_A4.asLandscape() else PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

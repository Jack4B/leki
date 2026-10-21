package pl.asystent.leki

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import java.io.File

/** Drobne operacje systemowe: schowek, udostępnianie plików i druk. */
object Platform {
    fun copyText(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
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

    fun readFromUri(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("Nie można otworzyć pliku")

    /** Drukuje dokument HTML lub zapisuje go jako PDF (okno druku Androida). */
    fun printHtml(activity: Activity, title: String, html: String) {
        val web = WebView(activity)
        web.settings.javaScriptEnabled = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(title, view.createPrintDocumentAdapter(title), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
            }
        }
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

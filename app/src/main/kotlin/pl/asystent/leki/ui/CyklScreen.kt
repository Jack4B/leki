package pl.asystent.leki.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.Cykl
import pl.asystent.leki.core.CyklData
import java.io.ByteArrayInputStream

/** Plik do zapisania z modułu: nazwa, typ i treść (moduł zapisuje tylko tekst: kopię JSON i raport). */
private class PendingFile(val name: String, val mime: String, val text: String)

/**
 * Most między stroną modułu „Cykl” (WebView) a aplikacją. Metody wołane są z wątku WebView, więc wszystko, co dotyka
 * interfejsu, przechodzi przez wątek główny. Strona dostaje tylko trzy klucze danych i nic więcej (patrz [CyklData]).
 */
private class CyklBridge(
    private val store: Store,
    /** Osoba, której dziennik pokazuje ta strona: zapisy trafiają do niej, nawet gdy ktoś w międzyczasie przełączy osobę. */
    private val pid: String,
    private val dark: Boolean,
    private val onSave: (PendingFile) -> Unit,
    private val onSheet: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastError = 0L

    @JavascriptInterface fun theme(): String = if (dark) "dark" else "light"

    @JavascriptInterface fun get(key: String): String = store.cyklOf(pid).get(key) ?: ""

    @JavascriptInterface fun set(key: String, value: String): Boolean {
        if (CyklData().put(key, value) == null) return false
        store.updateCykl(pid) { c -> c.put(key, value) ?: c }
        return true
    }

    @JavascriptInterface fun remove(key: String): Boolean {
        if (CyklData().remove(key) == null) return false
        store.updateCykl(pid) { c -> c.remove(key) ?: c }
        return true
    }

    @JavascriptInterface fun save(name: String, mime: String, base64: String): Boolean {
        if (base64.length > MAX_FILE_CHARS) return false
        val text = runCatching { String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return false
        val type = if (mime == "text/plain") "text/plain" else "application/json"
        main.post { onSave(PendingFile(Cykl.fileName(name), type, text)) }
        return true
    }

    @JavascriptInterface fun copy(text: String) {
        main.post {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Cykl", text.take(200_000))
            // dane zdrowotne: system nie pokazuje podglądu skopiowanego tekstu
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            cm.setPrimaryClip(clip)
        }
    }

    @JavascriptInterface fun sheet(open: Boolean) { main.post { onSheet(open) } }

    /** Błąd skryptu strony: pokazujemy go w aplikacji, żeby awaria modułu nie była po cichu niewidoczna. */
    @JavascriptInterface fun error(message: String) {
        // tylko miejsce błędu (bez treści, bo komunikaty przeglądarki potrafią zawierać fragmenty danych) i najwyżej raz na 5 s
        val now = System.currentTimeMillis()
        if (now - lastError < 5_000) return
        lastError = now
        main.post { onError(message.take(40)) }
    }

    companion object { const val MAX_FILE_CHARS = 6_000_000 }
}

private fun readAsset(ctx: Context, name: String): String = ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * Zakładka „Cykl”: strona modułu (assets/cykl) w WebView bez dostępu do sieci. Dane trzyma aplikacja (kopie zapasowe),
 * wygląd i motyw pochodzą z aplikacji, a pliki wybiera się systemowymi oknami.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CyklScreen(store: Store, modifier: Modifier = Modifier) {
    val pid by store.activeId.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val dark = pal.dark
    val fontScale = LocalConfiguration.current.fontScale
    val bg = pal.bg.toArgb()
    var web by remember { mutableStateOf<WebView?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var broken by remember { mutableStateOf(0) }
    var pendingFile by remember { mutableStateOf<PendingFile?>(null) }
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = pendingFile; pendingFile = null
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null && f != null) {
            val ok = runCatching { Platform.writeToUri(ctx, uri, f.text) }.isSuccess
            toast(if (ok) "Zapisano plik" else "Nie udało się zapisać pliku.")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        chooser = null
    }

    val page = remember { Cykl.page(readAsset(ctx, "cykl/cykl.html"), readAsset(ctx, "cykl/host.css"), readAsset(ctx, "cykl/host.js")) }

    // nowy WebView (zmiana motywu, awaria procesu strony) zaczyna bez otwartego arkusza
    LaunchedEffect(dark, broken, pid) { sheetOpen = false }
    // przycisk Wstecz zamyka najpierw otwarty arkusz szczegółów modułu, dopiero potem zakładkę
    BackHandler(enabled = sheetOpen) { web?.evaluateJavascript("document.getElementById('mask').click()", null) }

    key(dark, broken, pid) {
        Box(modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false          // dane są w pamięci aplikacji, nie w danych przeglądarki
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.setSupportZoom(false)
                        // skala czcionki systemu jawnie (nie zależy od wersji WebView), z górnym ograniczeniem, żeby układ modułu się mieścił
                        settings.textZoom = (fontScale * 100).toInt().coerceIn(100, 150)
                        settings.setGeolocationEnabled(false)
                        setBackgroundColor(bg)
                        addJavascriptInterface(
                            CyklBridge(store, pid, dark, onSave = { f ->
                                pendingFile = f
                                saver.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(f.mime).putExtra(Intent.EXTRA_TITLE, f.name))
                            }, onSheet = { sheetOpen = it }, onError = { toast("Moduł Cykl napotkał błąd. Zamknij zakładkę i otwórz ją ponownie.") }, context = c.applicationContext),
                            "LekiHost",
                        )
                        webViewClient = object : WebViewClient() {
                            // strona nie wychodzi nigdzie poza siebie: żadnych odnośników, żadnej sieci
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (request.url.scheme == "http" || request.url.scheme == "https") WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { broken++; return true }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                chooser?.onReceiveValue(null)
                                chooser = callback
                                return runCatching { picker.launch(params.createIntent()); true }.getOrElse { chooser = null; callback.onReceiveValue(null); toast("Nie można otworzyć wyboru pliku."); true }
                            }
                        }
                        loadDataWithBaseURL("https://cykl.leki.invalid/", page, "text/html", "UTF-8", null)
                        web = this
                    }
                },
                // skala czcionki systemu zmieniona w trakcie pracy: bez przeładowania strony (otwarty dzień i niezapisana notatka zostają)
                update = { it.settings.textZoom = (fontScale * 100).toInt().coerceIn(100, 150) },
                onRelease = { it.destroy(); if (web === it) web = null },
            )
        }
    }
}

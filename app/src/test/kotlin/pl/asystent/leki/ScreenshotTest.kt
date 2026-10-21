package pl.asystent.leki

import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.ThemeMode
import pl.asystent.leki.ui.BpScreen
import pl.asystent.leki.ui.LabsScreen
import pl.asystent.leki.ui.LekiTheme
import pl.asystent.leki.ui.LocalToast
import pl.asystent.leki.ui.MedsScreen
import pl.asystent.leki.ui.Nav
import pl.asystent.leki.ui.ReportScreen
import pl.asystent.leki.ui.SettingsScreen
import pl.asystent.leki.ui.pal
import java.io.File

/**
 * Zrzuty ekranów głównych widoków na danych wzorcowych (render na JVM, bez telefonu).
 * Służą do sprawdzenia układu; pliki PNG trafiają do wydania "zrzuty-ekranow" w GitHubie.
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4300, softButtons = false),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val state: AppState = Backup.parseState(javaClass.getResourceAsStream("/state.json")!!.readBytes().toString(Charsets.UTF_8))
    private val store = Store(File(System.getProperty("java.io.tmpdir"), "leki-screens-" + System.nanoTime()))
    private val nav = Nav({}, {})

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
        }
    }

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Light, content: @Composable () -> Unit) {
        paparazzi.snapshot(name) {
            CompositionLocalProvider(LocalToast provides {}, LocalActivityResultRegistryOwner provides registryOwner) {
                LekiTheme(mode) { Box(Modifier.fillMaxSize().background(pal.bg)) { content() } }
            }
        }
    }

    @Test fun leki() = shot("leki") { MedsScreen(state, store, nav, onGoData = {}, recoveredFrom = null) }
    @Test fun lekiCiemny() = shot("leki-ciemny", ThemeMode.Dark) { MedsScreen(state, store, nav, onGoData = {}, recoveredFrom = null) }
    @Test fun lekiPuste() = shot("leki-puste") { MedsScreen(AppState(), store, nav, onGoData = {}, recoveredFrom = null) }
    @Test fun cisnienie() = shot("cisnienie") { BpScreen(state, store) }
    @Test fun badania() = shot("badania") { LabsScreen(state, store, onImport = {}) }
    @Test fun raport() = shot("raport") { ReportScreen(state, store) }
    @Test fun ustawienia() = shot("ustawienia") { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}) }
}

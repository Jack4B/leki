package pl.asystent.leki

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.ThemeMode
import pl.asystent.leki.ui.BpScreen
import pl.asystent.leki.ui.LabsScreen
import pl.asystent.leki.ui.LekiTheme
import pl.asystent.leki.ui.LocalToast
import pl.asystent.leki.ui.MedsScreen
import pl.asystent.leki.ui.Nav
import pl.asystent.leki.ui.PickBody
import pl.asystent.leki.ui.ReportScreen
import pl.asystent.leki.ui.ReviewBody
import pl.asystent.leki.ui.ReviewFooter
import pl.asystent.leki.ui.ReviewState
import pl.asystent.leki.ui.SettingsScreen
import pl.asystent.leki.ui.pal
import java.io.File
import java.time.LocalDate

/**
 * Zrzuty ekranów widoków na danych wzorcowych (render na JVM, bez telefonu): szerokość telefonu (393 dp), przewijane ekrany
 * w kilku "plasterkach" po ok. 667 dp. Pliki PNG trafiają do wydania "zrzuty-ekranow" w GitHubie.
 */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 590, screenHeight = 1000, xdpi = 240, ydpi = 240, density = Density.HIGH, softButtons = false),
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

    /** Ekran na liście: kolejne "plasterki" przewinięcia (w pikselach). */
    private fun slices(name: String, n: Int, mode: ThemeMode = ThemeMode.Light, content: @Composable (LazyListState) -> Unit) {
        for (k in 0 until n) shot("$name-${k + 1}", mode) { content(remember { LazyListState(0, k * 950) }) }
    }

    @Test fun leki() = slices("leki", 3) { MedsScreen(state, store, nav, onGoData = {}, recoveredFrom = null, listState = it) }
    @Test fun lekiCiemny() = slices("leki-ciemny", 1, ThemeMode.Dark) { MedsScreen(state, store, nav, onGoData = {}, recoveredFrom = null, listState = it) }
    @Test fun lekiPuste() = slices("leki-puste", 1) { MedsScreen(AppState(), store, nav, onGoData = {}, recoveredFrom = null, listState = it) }
    @Test fun cisnienie() = slices("cisnienie", 3) { BpScreen(state, store, listState = it) }
    @Test fun badania() = slices("badania", 1) { LabsScreen(state, store, onImport = {}, listState = it) }
    @Test fun badaniaPuste() = slices("badania-puste", 1) { LabsScreen(AppState(), store, onImport = {}, listState = it) }
    @Test fun raportLekarz() = slices("raport-lekarz", 1) { ReportScreen(state, store, listState = it, initialMode = 0) }
    @Test fun raportAi() = slices("raport-ai", 2) { ReportScreen(state, store, listState = it, initialMode = 1) }
    @Test fun ustawienia() = slices("ustawienia", 1) { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}, listState = it) }
    @Test fun ustawieniaLeki() = slices("ustawienia-leki", 1) { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}, listState = it, initialPage = "Meds") }
    @Test fun ustawieniaOgolne() = slices("ustawienia-ogolne", 2) { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}, listState = it, initialPage = "General") }
    @Test fun ustawieniaDane() = slices("ustawienia-dane", 1) { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}, listState = it, initialPage = "Data") }

    @Test fun skanWybor() = shot("skan-wybor") { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) { PickBody({}, {}, {}) } }

    private fun review(): ReviewState {
        val lines = listOf(
            "Data pobrania: 20.10.2026 07:45",
            "Potas  4,2  mmol/l  3,5 - 5,1", "Sód  138  mmol/l  136 - 145", "Kreatynina  92  umol/l  60 - 104", "Glukoza  5,2  mmol/l  3,9 - 5,5",
            "TSH  2,15  µIU/ml  0,27 - 4,20", "CRP  <0,5  mg/l  do 5,0", "Hemoglobina  148  g/dl  135 - 175",
        )
        val parsed = LabScan.parseRows(lines)
        val s = state.copy(labs = state.labs + pl.asystent.leki.core.Lab("x", LocalDate.of(2026, 10, 20), "sod", 140.0))
        return ReviewState(s, LabScan.parseDate(lines, LocalDate.of(2026, 10, 21)), LabScan.candidates(parsed, s), lines.joinToString("\n"), 1)
    }

    @Test fun skanPrzeglad() {
        for (k in 0 until 2) shot("skan-przeglad-${k + 1}") {
            val rv = remember { review() }
            val s = state.copy(labs = state.labs + pl.asystent.leki.core.Lab("x", LocalDate.of(2026, 10, 20), "sod", 140.0))
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState(k * 950)).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) { ReviewBody(s, rv) }
                Box(Modifier.background(pal.surface).padding(16.dp)) { ReviewFooter(rv, {}, {}) }
            }
        }
    }
}

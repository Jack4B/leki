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
import pl.asystent.leki.core.Analysis
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Bp
import pl.asystent.leki.core.Ecg
import pl.asystent.leki.core.EcgAnalysis
import pl.asystent.leki.core.Backup
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.ThemeMode
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import pl.asystent.leki.ui.BpScreen
import pl.asystent.leki.ui.CtlShape
import pl.asystent.leki.ui.EcgEditor
import pl.asystent.leki.ui.EcgFindings
import pl.asystent.leki.ui.EcgZoomView
import pl.asystent.leki.ui.LocalOverlayHost
import pl.asystent.leki.ui.OverlayHost
import pl.asystent.leki.ui.OverlayLayer
import pl.asystent.leki.ui.EcgWaveView
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
import java.time.LocalTime

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
    /** Zmyślone pomiary z całego roku, liczone od dzisiaj: sprawdza przewijany wykres z zakresem min–max i podpisami miesięcy. */
    private val yearState: AppState = run {
        val today = LocalDate.now()
        state.copy(bp = (0 until 365).flatMap { i ->
            val d = today.minusDays((364 - i).toLong())
            listOf(
                Bp("m$i", d, LocalTime.of(8, 0), 122 + (i * 7) % 28, 78 + (i * 3) % 14, 64, "rano"),
                Bp("e$i", d, LocalTime.of(20, 30), 126 + (i * 5) % 26, 80 + (i * 2) % 12, 70, "wieczór"),
            )
        })
    }
    @Test fun cisnienieRok() = slices("cisnienie-rok", 2) { BpScreen(yearState, store, listState = it, initialRange = 365) }

    private val analysisState: AppState = state.copy(analyses = listOf(Analysis("a1", LocalDate.of(2026, 1, 15), "Analiza AI", "# Analiza\n\n## Podsumowanie\nPrzykładowy wniosek.\n\n## Szczegóły\nDługi opis.\n\n## Pytania do lekarza\n1. Przykładowe pytanie?", listOf("Szczegóły"))))
    @Test fun raportAnaliza() = slices("raport-analiza", 1) { ReportScreen(analysisState, store, listState = it, initialMode = 0) }

    /** Komunikat (snackbar) na jasnym pasku w ciemnym motywie: tekst ma być ciemny i czytelny (regresja: biała belka bez tekstu). */
    @Test fun komunikatCiemny() = shot("komunikat-ciemny", ThemeMode.Dark) {
        Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            Snackbar(containerColor = pal.ink, contentColor = pal.bg, shape = CtlShape) { Text("Wczytano kopię zapasową") }
            Text("Tekst na tle ekranu", color = pal.ink)
        }
    }
    @Test fun komunikatJasny() = shot("komunikat-jasny", ThemeMode.Light) {
        Column(Modifier.padding(16.dp)) { Snackbar(containerColor = pal.ink, contentColor = pal.bg, shape = CtlShape) { Text("Wczytano kopię zapasową") } }
    }

    /** Zmyślony zapis EKG (syntetyczny, ok. 72/min, jedno przedwczesne uderzenie i krótki artefakt) do sprawdzenia widoku. */
    private val ecgState: AppState = run {
        val fs = 125; val n = fs * 30
        val beats = ArrayList<Double>(); var t = 0.5; var k = 0
        while (t < 29.5) { beats.add(t); t += if (k == 12) 0.5 else if (k == 13) 1.1 else 0.83; k++ }
        fun g(x: Double, c: Double, a: Double, sg: Double) = a * Math.exp(-((x - c) * (x - c)) / (2 * sg * sg))
        val mv = DoubleArray(n) { i ->
            val x = i / fs.toDouble()
            var v = 0.15 * Math.sin(2 * Math.PI * 0.3 * x)
            for (b in beats) if (Math.abs(x - b) < 0.6) v += g(x, b - 0.16, 0.15, 0.025) + g(x, b - 0.025, -0.15, 0.010) + g(x, b, 1.2, 0.012) + g(x, b + 0.027, -0.25, 0.010) + g(x, b + 0.28, 0.3, 0.045)
            if (x in 20.0..20.25) v -= 2.6 * Math.sin((x - 20.0) / 0.25 * Math.PI)
            v
        }
        state.copy(ecgs = listOf(Ecg("e1", LocalDate.of(2026, 10, 13), LocalTime.of(19, 8), watchHr = 73, watchNote = "Rytm zatokowy", fs = fs, cmv = EcgAnalysis.toRecord(mv, fs.toDouble(), fs))))
    }
    @Test fun badaniaEkg() = slices("badania-ekg", 1) { LabsScreen(ecgState, store, onImport = {}, listState = it, initialMode = 2) }
    @Test fun ekgPowiekszenie() = shot("ekg-powiekszenie") {
        val e = ecgState.ecgs.single()
        Column(Modifier.padding(16.dp)) { EcgZoomView(e, EcgAnalysis.analyze(e)) }
    }
    @Test fun oAplikacji() = slices("o-aplikacji", 1) { SettingsScreen(state, store, importer = {}, newMedRequest = false, onNewMedHandled = {}, listState = it, initialPage = "About") }

    @Test fun ekgSzczegoly() = shot("ekg-szczegoly") {
        val e = ecgState.ecgs.single(); val r = EcgAnalysis.analyze(e)
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            EcgWaveView(e, r); EcgFindings(r)
        }
    }

    /** Pełnoekranowy edytor rysowany w głównym oknie (warstwa nakładek): stopka z przyciskami ma być widoczna. */
    @Test fun ekgEdytor() = shot("ekg-edytor") {
        val host = remember { OverlayHost() }
        CompositionLocalProvider(LocalOverlayHost provides host) {
            EcgEditor(ecgState.ecgs.single(), true, store) {}
            OverlayLayer(host)
        }
    }

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

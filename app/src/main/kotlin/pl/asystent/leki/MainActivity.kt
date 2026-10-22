package pl.asystent.leki

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.asystent.leki.core.clearTimer
import pl.asystent.leki.ui.BodyText
import pl.asystent.leki.ui.BpScreen
import pl.asystent.leki.ui.CardShape
import pl.asystent.leki.ui.CtlShape
import pl.asystent.leki.ui.Divider
import pl.asystent.leki.ui.IconAction
import pl.asystent.leki.ui.LabsScreen
import pl.asystent.leki.ui.LekiButton
import pl.asystent.leki.ui.LekiTheme
import pl.asystent.leki.ui.LocalToast
import pl.asystent.leki.ui.OverlayLayer
import pl.asystent.leki.ui.OverlayHost
import pl.asystent.leki.ui.LocalOverlayHost
import pl.asystent.leki.ui.MedsScreen
import pl.asystent.leki.ui.Mono
import pl.asystent.leki.ui.Nav
import pl.asystent.leki.ui.Ph
import pl.asystent.leki.ui.PhIcon
import pl.asystent.leki.ui.ReportScreen
import pl.asystent.leki.ui.SettingsScreen
import pl.asystent.leki.ui.pal
import pl.asystent.leki.ui.rememberImporter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Platform.clearShareCache(this)
        enableEdgeToEdge()
        val store = (application as LekiApp).store
        setContent { App(store) }
    }

    // zapis przy każdym opuszczeniu ekranu, a nie dopiero w onStop: system może zabić proces między onPause a onStop
    override fun onPause() {
        super.onPause()
        (application as LekiApp).store.flush()
    }

    override fun onStop() {
        super.onStop()
        (application as LekiApp).store.flush()
    }
}

private val tabs = listOf("Leki", "Ciśnienie", "Badania", "Raport", "Ustawienia")

@Composable
fun App(store: Store) {
    val s by store.state.collectAsStateWithLifecycle()
    LekiTheme(s.settings.theme) {
        val view = LocalView.current
        val dark = pal.dark
        SideEffect {
            val w = (view.context as? android.app.Activity)?.window
            if (w != null) {
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val toast: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }
        val saveFailed by store.saveFailed.collectAsStateWithLifecycle()
        LaunchedEffect(saveFailed) {
            if (saveFailed) toast("Nie udało się zapisać danych. Sprawdź wolne miejsce w telefonie i zrób kopię w Ustawieniach.")
        }
        CompositionLocalProvider(LocalToast provides toast) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            var newMed by remember { mutableStateOf(false) }
            var rules by remember { mutableStateOf(false) }
            val importer = rememberImporter(s, store)
            BackHandler(enabled = tab != 0) { tab = 0 }

            val overlays = remember { OverlayHost() }
            CompositionLocalProvider(LocalOverlayHost provides overlays) {
            Box(Modifier.fillMaxSize()) {
            Scaffold(
                // pod pełnoekranowym edytorem czytnik ekranu nie ma czytać zasłoniętej aplikacji
                modifier = if (overlays.entries.isNotEmpty()) Modifier.clearAndSetSemantics {} else Modifier,
                containerColor = pal.bg,
                snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = pal.ink, contentColor = pal.bg, actionColor = pal.accent, shape = CtlShape) } },
                topBar = {
                    Column(Modifier.fillMaxWidth().background(pal.bg).statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Asystent leków", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                                if (s.patient.isNotEmpty()) Text(s.patient, style = TextStyle(fontSize = 12.5.sp, color = pal.ink3), maxLines = 1)
                            }
                            IconAction(Ph.Notebook, "Zasady i notatki", { rules = true })
                        }
                        Divider()
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = pal.surface) {
                        val icons = listOf(Ph.Pill, Ph.Heartbeat, Ph.Flask, Ph.FileText, Ph.GearSix)
                        tabs.forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(icons[i], contentDescription = null, modifier = Modifier.size(24.dp)) },
                                label = { Text(label, style = TextStyle(fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = pal.accent, selectedTextColor = pal.accent, indicatorColor = pal.accentSoft,
                                    unselectedIconColor = pal.ink3, unselectedTextColor = pal.ink3,
                                ),
                            )
                        }
                    }
                },
            ) { padding ->
                // imePadding: od Androida 15 okno nie zmniejsza się samo przy klawiaturze, a pole wpisywania nie może zostać zasłonięte
                Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                    val nav = Nav(goSettings = { tab = 4 }, newMed = { tab = 4; newMed = true })
                    when (tab) {
                        0 -> MedsScreen(s, store, nav, onGoData = { tab = 4 }, recoveredFrom = store.recoveredFrom)
                        1 -> BpScreen(s, store)
                        2 -> LabsScreen(s, store, onImport = importer)
                        3 -> ReportScreen(s, store)
                        else -> SettingsScreen(s, store, importer, newMed, onNewMedHandled = { newMed = false })
                    }
                    TimerBanner(s.timerEnd, s.timerMed, s.timerNote, onHide = { store.update { it.clearTimer() } }, onDone = { name -> store.update { it.clearTimer() }; toast("Odliczanie po $name zakończone.") },
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            OverlayLayer(overlays)
            // komunikaty muszą być widoczne także nad pełnoekranowym edytorem, który zasłania pasek główny
            if (overlays.entries.isNotEmpty()) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 88.dp)) { data ->
                Snackbar(data, containerColor = pal.ink, contentColor = pal.bg, actionColor = pal.accent, shape = CtlShape)
            }
            }
            }
            if (rules) AlertDialog(
                onDismissRequest = { rules = false },
                title = { Text("Zasady i notatki", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (s.rules.isNotBlank()) BodyText(s.rules) else BodyText("Nie ma jeszcze notatek. Dopisz je w Ustawieniach, w sekcji Ogólne.")
                    }
                },
                confirmButton = { TextButton(onClick = { rules = false }) { Text("Zamknij", color = pal.accent, fontWeight = FontWeight.SemiBold) } },
                containerColor = pal.surface,
            )
        }
    }
}

/** Odliczanie po dawce (np. nie kłaść się przez 30 minut). */
@Composable
private fun TimerBanner(timerEnd: Long, med: String, note: String, onHide: () -> Unit, onDone: (String) -> Unit, modifier: Modifier = Modifier) {
    if (timerEnd <= 0) return
    val left by produceState(timerEnd - System.currentTimeMillis(), timerEnd) {
        while (true) {
            val l = timerEnd - System.currentTimeMillis()
            value = l
            if (l <= 0) break
            delay(1000)
        }
    }
    if (left <= 0) {
        androidx.compose.runtime.LaunchedEffect(timerEnd) { onDone(med) }
        return
    }
    val sec = ((left + 999) / 1000).toInt()
    Row(
        modifier.fillMaxWidth().padding(12.dp).shadow(6.dp, CardShape).background(pal.warnBg, CardShape).border(1.dp, pal.warnLine, CardShape).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Po przyjęciu: $med", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(note.ifEmpty { "Odliczanie od przyjęcia dawki." }, style = TextStyle(fontSize = 13.sp, color = pal.ink2))
        }
        Text("%02d:%02d".format(sec / 60, sec % 60), style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = pal.warn))
        TextButton(onClick = onHide) { Text("Ukryj", color = pal.ink2) }
    }
}

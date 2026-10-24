package pl.asystent.leki

import android.os.Bundle
import android.content.Intent
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import pl.asystent.leki.ui.AnalysisEditor
import pl.asystent.leki.ui.BodyText
import pl.asystent.leki.ui.IncomingAi
import pl.asystent.leki.ui.BpScreen
import pl.asystent.leki.ui.CardShape
import pl.asystent.leki.ui.CtlShape
import pl.asystent.leki.ui.CyklScreen
import pl.asystent.leki.ui.PersonSwitcher
import pl.asystent.leki.ui.LocalPersonName
import pl.asystent.leki.ui.Divider
import pl.asystent.leki.ui.IconAction
import pl.asystent.leki.ui.LabsScreen
import pl.asystent.leki.ui.LekiButton
import pl.asystent.leki.ui.LekiTheme
import pl.asystent.leki.ui.LocalToast
import pl.asystent.leki.ui.LocalUndo
import pl.asystent.leki.ui.OverlayLayer
import pl.asystent.leki.ui.OverlayHost
import pl.asystent.leki.ui.LocalOverlayHost
import pl.asystent.leki.ui.LockScreen
import pl.asystent.leki.ui.MedsScreen
import pl.asystent.leki.ui.Mono
import pl.asystent.leki.ui.Nav
import pl.asystent.leki.ui.Ph
import pl.asystent.leki.ui.PhIcon
import pl.asystent.leki.ui.ReportScreen
import pl.asystent.leki.ui.SettingsScreen
import pl.asystent.leki.ui.pal
import pl.asystent.leki.ui.rememberImporter

class MainActivity : FragmentActivity() {
    private lateinit var lock: LockController
    /** Zakładka, którą ma otworzyć skrót lub widżet (null = bez prośby). */
    private var requestedTab by mutableStateOf<Int?>(null)
    /** Odpowiedź AI udostępniona z innej aplikacji albo otwarty plik .md (czeka na wyświetlenie w edytorze analizy). */
    private var incoming by mutableStateOf<IncomingAi?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Platform.clearShareCache(this)
        enableEdgeToEdge()
        val store = (application as LekiApp).store
        lock = LockController(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { App(store, lock, requestedTab, { requestedTab = null }, incoming) { incoming = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            ACTION_ADD_BP -> requestedTab = 1
            Intent.ACTION_SEND -> {
                // EXTRA_TEXT bywa Spanned (aplikacje AI, przeglądarki), więc czytamy jako CharSequence; plik tylko jako content://
                val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val uri = androidx.core.content.IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, android.net.Uri::class.java)?.takeIf { it.scheme == "content" }
                if (!text.isNullOrBlank()) { incoming = IncomingAi(text, null); requestedTab = 3 }
                else if (uri != null) { incoming = IncomingAi(null, uri); requestedTab = 3 }
            }
            Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "content" }?.let { incoming = IncomingAi(null, it); requestedTab = 3 }
        }
    }

    override fun onStart() {
        super.onStart()
        lock.onStart()
    }

    // zapis przy każdym opuszczeniu ekranu, a nie dopiero w onStop: system może zabić proces między onPause a onStop
    override fun onPause() {
        super.onPause()
        (application as LekiApp).store.flush()
    }

    override fun onStop() {
        super.onStop()
        lock.onStop()
        (application as LekiApp).store.flush()
    }

    companion object {
        /** Skrót na ikonie aplikacji i widżet: otwiera ekran dodawania pomiaru ciśnienia. */
        const val ACTION_ADD_BP = "pl.asystent.leki.ADD_BP"
    }
}

private val tabs = listOf("Leki", "Ciśnienie", "Badania", "Raport", "Ustawienia", "Cykl")
/**
 * Kolejność zakładek na dolnym pasku. Leki (0) i Raport (3) są zawsze; Ciśnienie (1), Badania (2) i „Cykl” (5, przed Raportem) każda osoba
 * może ukryć w Ustawieniach → Moduły (dane zostają). Ustawienia (numer 4) są pod ikoną koła zębatego w nagłówku. Numery zakładek się nie zmieniają.
 */
private fun visibleTabs(c: pl.asystent.leki.core.Settings): List<Int> = listOfNotNull(0, 1.takeIf { c.bpOn }, 2.takeIf { c.labsOn }, 5.takeIf { c.cyklOn }, 3)
private const val TAB_CYKL = 5

/** Kolor przycisku „Cofnij” na ciemnym (jasny motyw) lub jasnym (ciemny motyw) pasku komunikatu: kontrast co najmniej 5,5:1. */
@Composable
private fun snackAction(): androidx.compose.ui.graphics.Color = if (pal.dark) androidx.compose.ui.graphics.Color(0xFF0B6B58) else androidx.compose.ui.graphics.Color(0xFF7FD9C0)

@Composable
fun App(store: Store, lock: LockController? = null, requestedTab: Int? = null, onTabHandled: () -> Unit = {}, incoming: IncomingAi? = null, onIncomingHandled: () -> Unit = {}) {
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
        // usunięcie pojedynczej pozycji: bez okna potwierdzenia, za to z komunikatem i przyciskiem „Cofnij” (długo widocznym)
        val undo: (String, () -> Unit) -> Unit = { msg, restore ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                if (snackbar.showSnackbar(msg, actionLabel = "Cofnij", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) restore()
            }
        }
        CompositionLocalProvider(LocalToast provides toast, LocalUndo provides undo, LocalLock provides lock) {
            var rawTab by rememberSaveable { mutableIntStateOf(0) }
            // moduł ukryty w Ustawieniach (albo po zmianie osoby), a zakładka była otwarta: wracamy do pierwszej
            val tab = if (rawTab in 0..5 && rawTab != 4 && rawTab !in visibleTabs(s.settings)) 0 else rawTab
            val setTab: (Int) -> Unit = { rawTab = it }
            val saveable = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            LaunchedEffect(requestedTab) { requestedTab?.let { setTab(it); onTabHandled() } }
            var newMed by remember { mutableStateOf(false) }
            var showMeds by remember { mutableStateOf(false) }
            var showEvents by remember { mutableStateOf(false) }
            var showPeople by remember { mutableStateOf(false) }
            val profiles by store.profiles.collectAsStateWithLifecycle()
            val activeId by store.activeId.collectAsStateWithLifecycle()
            var rules by remember { mutableStateOf(false) }
            val importer = rememberImporter(s, store)
            BackHandler(enabled = tab != 0) { setTab(0) }

            val overlays = remember { OverlayHost() }
            val whoName = profiles.firstOrNull { it.id == activeId }?.name?.takeIf { profiles.size > 1 }
            CompositionLocalProvider(LocalOverlayHost provides overlays, LocalPersonName provides whoName) {
            Box(Modifier.fillMaxSize()) {
            Scaffold(
                // pod pełnoekranowym edytorem czytnik ekranu nie ma czytać zasłoniętej aplikacji
                modifier = if (overlays.entries.isNotEmpty() || lock?.locked == true) Modifier.clearAndSetSemantics {} else Modifier,
                containerColor = pal.bg,
                snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data, containerColor = pal.ink, contentColor = pal.bg, actionColor = snackAction(), shape = CtlShape) } },
                topBar = {
                    Column(Modifier.fillMaxWidth().background(pal.bg).statusBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Asystent leków", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                                PersonSwitcher(store, profiles, activeId, onManage = { setTab(4); showPeople = true })
                            }
                            IconAction(Ph.Notebook, "Zasady i notatki", { rules = true })
                            IconAction(Ph.GearSix, "Ustawienia", { setTab(4) }, selected = tab == 4)
                        }
                        Divider()
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = pal.surface) {
                        val icons = listOf(Ph.Pill, Ph.Heartbeat, Ph.Flask, Ph.FileText, Ph.GearSix, Ph.CalendarBlank)
                        val shown = visibleTabs(s.settings)
                        shown.forEach { i ->
                            val label = tabs[i]
                            NavigationBarItem(
                                selected = tab == i, onClick = { setTab(i) },
                                icon = { Icon(icons[i], contentDescription = null, modifier = Modifier.size(24.dp)) },
                                label = { Text(label, maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium)) },
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
                    val nav = Nav(goSettings = { setTab(4) }, newMed = { setTab(4); newMed = true }, showMeds = { setTab(4); showMeds = true }, showEvents = { setTab(4); showEvents = true })
                    // stan każdej zakładki (wybrany dzień, zakres, widok, przewinięcie) przeżywa przełączanie zakładek, a nie tylko obrót ekranu
                    // po zmianie osoby ekrany startują od nowa (formularze i wersje robocze nie mogą przenieść danych jednej osoby do drugiej)
                    androidx.compose.runtime.key(activeId) { saveable.SaveableStateProvider("$activeId:$tab") {
                        when (tab) {
                            0 -> MedsScreen(s, store, nav, onGoData = { setTab(4) }, recoveredFrom = store.recoveredFrom)
                            1 -> BpScreen(s, store)
                            2 -> LabsScreen(s, store, onImport = importer)
                            3 -> ReportScreen(s, store)
                            TAB_CYKL -> CyklScreen(store)
                            else -> SettingsScreen(s, store, importer, newMed, onNewMedHandled = { newMed = false }, showMedsRequest = showMeds, onShowMedsHandled = { showMeds = false }, showEventsRequest = showEvents, onShowEventsHandled = { showEvents = false }, onOpenCykl = { setTab(TAB_CYKL) }, showPeopleRequest = showPeople, onShowPeopleHandled = { showPeople = false }, onOpenCyklReport = { store.update { it.copy(settings = it.settings.copy(rp = it.settings.rp.copy(cykl = true))) }; setTab(3) })
                        }
                    } }
                    TimerBanner(s.timerEnd, s.timerMed, s.timerNote, onHide = { store.update { it.clearTimer() } }, onDone = { name -> store.update { it.clearTimer() }; toast("Odliczanie po $name zakończone.") },
                        modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            // odpowiedź AI udostępniona z innej aplikacji: edytor analizy otwiera się od razu z wczytanym tekstem
            if (incoming != null) AnalysisEditor(null, store, java.time.LocalDate.now(), onClose = onIncomingHandled, prefill = incoming)
            OverlayLayer(overlays)
            // komunikaty muszą być widoczne także nad pełnoekranowym edytorem, który zasłania pasek główny
            if (overlays.entries.isNotEmpty()) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = 88.dp)) { data ->
                Snackbar(data, containerColor = pal.ink, contentColor = pal.bg, actionColor = snackAction(), shape = CtlShape)
            }
            // ekran blokady jest ostatni, więc zasłania wszystko, także edytory i komunikaty
            if (lock != null && lock.locked) LockScreen(lock)
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

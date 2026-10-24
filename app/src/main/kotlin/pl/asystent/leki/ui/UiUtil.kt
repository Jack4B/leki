package pl.asystent.leki.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

/** Aktualny czas odświeżany co kilkanaście sekund (zmiana dnia, spóźnione dawki). */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        delay(15_000)
        value = LocalDateTime.now()
    }
}

fun slotIcon(t: LocalTime): ImageVector {
    val h = t.hour
    return when {
        h in 5..10 -> Ph.SunHorizon
        h in 11..16 -> Ph.Sun
        h in 17..20 -> Ph.CloudMoon
        else -> Ph.MoonStars
    }
}

/** Działania wymagające przełączenia widoku, zgłaszane z ekranów do MainActivity. */
class Nav(val goSettings: () -> Unit, val newMed: () -> Unit, val showMeds: () -> Unit = {}, val showEvents: () -> Unit = {})

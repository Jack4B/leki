package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import pl.asystent.leki.LockController

/** Ekran blokady zasłaniający całą aplikację; przy każdym wznowieniu sam wywołuje okno odblokowania systemu. */
@Composable
fun LockScreen(lock: LockController) {
    LifecycleResumeEffect(Unit) { lock.unlock(); onPauseOrDispose {} }
    Box(Modifier.fillMaxSize().background(pal.bg).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PhIcon(Ph.LockKey, size = 48.dp, tint = pal.accent)
            Text("Aplikacja zablokowana", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            BodyText(lock.message.ifEmpty { "Odblokuj odciskiem palca, twarzą albo blokadą ekranu telefonu." })
            LekiButton("Odblokuj", { lock.unlock() }, primary = true)
        }
    }
}

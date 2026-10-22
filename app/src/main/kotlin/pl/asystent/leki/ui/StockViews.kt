package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import pl.asystent.leki.core.StockForecast
import pl.asystent.leki.core.StockLevel
import pl.asystent.leki.core.num
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.stockLevel
import pl.asystent.leki.core.stockText

/** Opis zapasu jednej linijką: ile zostało i do kiedy starczy (według planu). */
fun stockLine(f: StockForecast, unit: String): String {
    val days = f.daysLeft
    val until = f.runsOutOn
    return when {
        days == null -> "zostało ${num(f.left)} $unit; według planu lek nie jest brany"
        days == 0 -> "zostało ${num(f.left)} $unit: nie starczy na dzisiejsze dawki"
        until != null -> "zostało ${num(f.left)} $unit, starczy do ${plDate(until.minusDays(1))} (około $days dni)"
        else -> stockText(f, unit)
    }
}

@Composable
fun stockColor(level: StockLevel): Color = when (level) { StockLevel.Ok -> pal.accent; StockLevel.Low -> pal.warn; StockLevel.Critical -> pal.danger }

/** Pasek zapasu w skali do 30 dni: zielony, pomarańczowy poniżej progu ostrzeżenia, czerwony przy krytycznie małym zapasie. */
@Composable
fun StockBar(f: StockForecast, warnDays: Int, modifier: Modifier = Modifier) {
    val level = stockLevel(f, warnDays)
    val frac = ((f.daysLeft ?: 30).coerceIn(0, 30) / 30f).coerceAtLeast(0.03f)
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.line)) {
        Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(stockColor(level)))
    }
}

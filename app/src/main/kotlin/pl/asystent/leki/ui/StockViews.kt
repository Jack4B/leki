package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.num
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.stockOverview
import pl.asystent.leki.core.stockText
import java.time.LocalDate

/**
 * Stan zapasów leków, którym zapas jest śledzony: ile zostało, na ile dni starczy (według planu) i do kiedy. Wiersz dotknięty otwiera edycję leku,
 * gdzie można poprawić liczbę po przeliczeniu opakowań. Pasek pokazuje zapas w skali do 30 dni; pomarańczowy oznacza próg ostrzeżenia z Ustawień.
 */
@Composable
fun StockOverviewCard(s: AppState, today: LocalDate, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null) {
    val rows = s.stockOverview(today)
    if (rows.isEmpty()) return
    LekiCard(modifier) {
        rows.forEachIndexed { i, r ->
            if (i > 0) Divider()
            val days = r.forecast.daysLeft
            val warn = s.settings.stockWarnDays
            val low = days != null && warn > 0 && days <= warn
            val until = r.forecast.runsOutOn
            val line = when {
                days == null -> "zostało ${num(r.forecast.left)} ${r.med.unit}; według planu lek nie jest brany"
                days == 0 -> "zostało ${num(r.forecast.left)} ${r.med.unit}: nie starczy na dzisiejsze dawki"
                until != null -> "zostało ${num(r.forecast.left)} ${r.med.unit}, starczy do ${plDate(until.minusDays(1))} (około $days dni)"
                else -> stockText(r.forecast, r.med.unit)
            }
            Column(
                Modifier.fillMaxWidth().let { m -> if (onOpen != null) m.clickable(role = Role.Button) { onOpen(r.med.id) } else m }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.med.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.weight(1f))
                    if (low) Text("kończy się", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                }
                Text(line, style = TextStyle(fontSize = 13.sp, color = if (low) pal.warn else pal.ink2))
                val frac = ((days ?: 30).coerceIn(0, 30) / 30f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.line)) {
                    Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.02f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (low) pal.warn else pal.accent))
                }
            }
        }
    }
}

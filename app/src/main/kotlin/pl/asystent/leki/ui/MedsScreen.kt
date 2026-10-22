package pl.asystent.leki.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Item
import pl.asystent.leki.core.Pl
import pl.asystent.leki.core.Skipped
import pl.asystent.leki.core.clearSkip
import pl.asystent.leki.core.dayStats
import pl.asystent.leki.core.daysBetween
import pl.asystent.leki.core.firstDate
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.jsDow
import pl.asystent.leki.core.longDay
import pl.asystent.leki.core.markAll
import pl.asystent.leki.core.minutes
import pl.asystent.leki.core.needBackup
import pl.asystent.leki.core.num
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.resetDay
import pl.asystent.leki.core.setDayNote
import pl.asystent.leki.core.setDoseTime
import pl.asystent.leki.core.setEventResult
import pl.asystent.leki.core.setWater
import pl.asystent.leki.core.shortDate
import pl.asystent.leki.core.skipDose
import pl.asystent.leki.core.lowStock
import pl.asystent.leki.core.stockForecast
import pl.asystent.leki.core.stockOverview
import pl.asystent.leki.core.stockText
import pl.asystent.leki.core.toggleDose
import java.time.LocalDate
import java.time.LocalTime

private val SKIP_REASONS = listOf("Zapomniano o dawce", "Złe samopoczucie", "Brak leku", "Zalecenie lekarza", "Inny powód")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedsScreen(s: AppState, store: Store, nav: Nav, onGoData: () -> Unit, recoveredFrom: String?, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    var selStr by rememberSaveable { mutableStateOf(today.toString()) }
    val sel = LocalDate.parse(selStr)
    fun select(d: LocalDate) { selStr = d.toString() }

    var skipFor by remember { mutableStateOf<Item?>(null) }
    var timeFor by remember { mutableStateOf<Item?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val st = s.dayStats(sel)
    val rec = s.day(sel)
    val strip = run {
        var from = maxOf(today.minusDays(30), s.firstDate(today))
        var to = today.plusDays(7)
        if (sel < from) from = sel
        if (sel > to) to = sel
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
    }

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        if (recoveredFrom != null) item {
            AlertBox(
                "Plik danych był uszkodzony",
                body = "Aplikacja wystartowała pusta, a uszkodzony plik zachowano jako $recoveredFrom w pamięci aplikacji. Wczytaj ostatnią kopię w Ustawieniach.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        val low = s.lowStock(today)
        if (low.isNotEmpty()) item {
            AlertBox(
                "Kończy się zapas leków",
                body = low.joinToString("\n") { (m, f) -> m.name + ": " + stockText(f, m.unit) } + "\nPora na receptę lub zakup.",
                modifier = Modifier.padding(top = 8.dp),
            ) { LinkButton("Sprawdź zapasy i uzupełnij ›", nav.showMeds) }
        }
        item {
            val stripState = rememberLazyListState()
            LaunchedEffect(selStr, strip.size) {
                val i = strip.indexOf(sel)
                if (i >= 0) stripState.scrollToItem(maxOf(0, i - 2))
            }
            LazyRow(state = stripState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(strip, key = { it.toEpochDay() }) { d ->
                    val ds = s.dayStats(d)
                    DayChip(d, today, selected = d == sel, stats = ds, hasEvent = s.events.any { it.date == d }) { select(d) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                LekiButton("Dziś", { select(today) }, small = true, tonal = true, icon = Ph.CalendarCheck)
                DatePickerButton(sel, { select(it) })
            }
        }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text(longDay(sel), style = TextStyle(fontSize = 26.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                if (sel == today || sel > today) {
                    Box(Modifier.padding(top = 8.dp).clip(CircleShape).background(pal.accentSoft).padding(horizontal = 9.dp, vertical = 2.dp)) {
                        Text(if (sel == today) "dziś" else "plan", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = pal.accent))
                    }
                }
                if (st.tot > 0) {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("${st.n}/${st.tot}", "leki przyjęte", Modifier.weight(1f))
                        if (st.hasMmol) Stat("${num(st.mmolTaken)}/${num(st.mmolPlan)}", "potas z leków, mmol", Modifier.weight(1f))
                        if (s.settings.waterGoal > 0) Stat(num(rec.water * s.settings.glassMl / 1000.0) + " l", "woda", Modifier.weight(1f), mono = false)
                    }
                    Spacer(Modifier.height(12.dp))
                    ProgressBar(st.n.toFloat() / st.tot)
                    LinkButton("Zaznacz wszystkie jako przyjęte", {
                        if (sel > today) toast("Nie można odhaczać dawek z przyszłości.") else store.update { it.markAll(sel, today, now) }
                    }, Modifier.padding(top = 4.dp))
                }
            }
        }
        // kontrole w tym dniu i nadchodzące
        items(s.events.filter { it.date == sel }, key = { "ev" + it.id }) { e ->
            var text by remember(e.id, selStr) { mutableStateOf(rec.results[e.id].orEmpty()) }
            AlertBox(e.title, body = e.note.ifEmpty { null }, modifier = Modifier.padding(top = 16.dp)) {
                if (e.field.isNotEmpty()) LekiField(
                    e.field, text, { text = it; store.update { st2 -> st2.setEventResult(e.id, it) } },
                    modifier = Modifier.padding(top = 10.dp), keyboard = KeyboardType.Decimal, mono = true,
                )
            }
        }
        val upcoming = s.events.filter { val d = daysBetween(sel, it.date); d in 1..3 }.sortedBy { it.date }
        if (upcoming.isNotEmpty()) item {
            Column(Modifier.padding(top = 10.dp)) {
                upcoming.forEach { e ->
                    val d = daysBetween(sel, e.date)
                    Hint("Za $d ${if (d == 1) "dzień" else "dni"}: ${e.title}")
                }
            }
        }
        val nb = s.needBackup(today)
        if (nb != null) item {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).clip(CardShape).border(1.dp, pal.line, CardShape).background(pal.surface).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BodyText("Ostatnia kopia danych: $nb. Dane są tylko na tym urządzeniu.", Modifier.weight(1f))
                LinkButton("Zrób kopię", onGoData, Modifier.padding(start = 12.dp))
            }
        }

        if (s.meds.isEmpty()) item {
            EmptyBox("Nie dodano jeszcze żadnych leków.", Modifier.padding(top = 24.dp)) {
                LekiButton("Dodaj lek", nav.newMed, primary = true, icon = Ph.Plus)
            }
        } else if (st.tot == 0) item {
            EmptyBox("Na ten dzień nie zaplanowano żadnych leków.", Modifier.padding(top = 24.dp))
        } else {
            s.sortedSlots().forEach { slot ->
                val items = st.items.filter { it.slot.id == slot.id }
                if (items.isEmpty()) return@forEach
                val late = lateState(sel, slot.time, today, now.toLocalTime().minutes, s.settings.lateMin)
                val lateN = if (late == "late") items.count { it.key !in rec.taken && it.key !in rec.skipped } else 0
                item(key = "slot" + slot.id) {
                    Column(Modifier.padding(top = 22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PhIcon(slotIcon(slot.time), size = 18.dp)
                                Text(slot.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), modifier = Modifier.padding(start = 8.dp))
                            }
                            Row {
                                if (lateN > 0) Text("spóźnione: $lateN  ", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                                Text(slot.time.hhmm(), style = TextStyle(fontSize = 12.sp, fontFamily = Mono, color = pal.ink3))
                            }
                        }
                        LekiCard {
                            items.forEachIndexed { i, x ->
                                if (i > 0) Divider()
                                DoseRow(
                                    x, sel, today, s, on = x.key in rec.taken, time = rec.times[x.key], skipped = rec.skipped[x.key], late = late,
                                    onToggle = { if (sel > today) toast("Nie można odhaczać dawek z przyszłości.") else store.update { it.toggleDose(sel, x.key, today, now) } },
                                    onSkip = { skipFor = x }, onTime = { timeFor = x },
                                )
                            }
                        }
                    }
                }
            }
        }

        val goal = s.settings.waterGoal
        if (goal > 0) item {
            Column(Modifier.padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("Woda", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                    Text("${num(rec.water * s.settings.glassMl / 1000.0)} l z ${num(goal * s.settings.glassMl / 1000.0)} l", style = TextStyle(fontSize = 13.sp, color = pal.ink3))
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (i in 1..goal) {
                        val on = i <= rec.water
                        Box(
                            Modifier.size(width = 31.dp, height = 40.dp).clip(CtlShape).border(1.dp, if (on) pal.accent else pal.line, CtlShape)
                                .background(if (on) pal.accent else pal.surface)
                                .clickable(role = Role.Button) { if (sel > today) toast("Nie można zapisywać wody z przyszłości.") else store.update { it.setWater(sel, i) } }
                                .semantics { contentDescription = "$i szklanek" },
                            contentAlignment = Alignment.Center,
                        ) { PhIcon(Ph.Drop, size = 16.dp, tint = if (on) pal.accentInk else pal.ink3) }
                    }
                }
                Hint("Jedna szklanka to ${s.settings.glassMl} ml.", Modifier.padding(top = 8.dp))
            }
        }

        if (s.stockOverview(today).isNotEmpty()) item {
            LinkButton("Sprawdź zapasy leków ›", nav.showMeds, Modifier.padding(top = 16.dp))
        }
        item {
            var note by remember(selStr) { mutableStateOf(rec.note) }
            Column(Modifier.padding(top = 24.dp)) {
                LekiField("Notatka dnia", note, { note = it; store.update { st2 -> st2.setDayNote(sel, it) } }, singleLine = false, minLines = 3, placeholder = "Samopoczucie, objawy, uwagi")
                LinkButton("Wyczyść odhaczenia z tego dnia", { confirmReset = true })
            }
        }
    }

    skipFor?.let { x ->
        SkipDialog(x, sel, rec.skipped[x.key], onSave = { r, n -> store.update { it.skipDose(sel, x.key, r, n) }; skipFor = null; toast("Zapisano pominięcie dawki") },
            onClear = { store.update { it.clearSkip(sel, x.key) }; skipFor = null }, onDismiss = { skipFor = null })
    }
    timeFor?.let { x ->
        TimeDialog(rec.times[x.key] ?: x.slot.time, onDismiss = { timeFor = null }, onPick = { t -> store.update { it.setDoseTime(sel, x.key, t) }; timeFor = null })
    }
    if (confirmReset) ConfirmDialog(
        "Wyczyścić ten dzień?", "Odhaczone leki, woda, notatka i wyniki kontroli z dnia ${plDate(sel)} zostaną usunięte. Pomiary ciśnienia zostają.", "Wyczyść",
        onConfirm = { store.update { it.resetDay(sel) }; toast("Wyczyszczono wybrany dzień") }, onDismiss = { confirmReset = false }, danger = true,
    )
}

/** "": brak, "past": dzień minął, "late": dzisiejsza pora już minęła o więcej niż próg spóźnienia. */
fun lateState(sel: LocalDate, slotTime: LocalTime, today: LocalDate, nowMinutes: Int, lateMin: Int): String {
    if (sel > today) return ""
    if (sel < today) return "past"
    if (lateMin == 0) return ""
    return if (nowMinutes > slotTime.minutes + lateMin) "late" else ""
}

@Composable
private fun DayChip(d: LocalDate, today: LocalDate, selected: Boolean, stats: pl.asystent.leki.core.DayStats, hasEvent: Boolean, onClick: () -> Unit) {
    val bg = if (selected) pal.accent else pal.surface
    val fg = if (selected) pal.accentInk else pal.ink
    val sub = if (selected) pal.accentInk else pal.ink3
    Column(
        Modifier.width(58.dp).clip(CtlShape).border(1.dp, if (selected) pal.accent else pal.line, CtlShape).background(bg)
            .clickable(role = Role.Tab, onClick = onClick).padding(top = 9.dp, bottom = 8.dp)
            .semantics { contentDescription = longDay(d) + if (stats.tot > 0) ", przyjęto ${stats.n} z ${stats.tot}" else "" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(if (d == today) "DZIŚ" else Pl.dowShort[d.jsDow].uppercase(), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = sub, letterSpacing = 0.5.sp))
        Text("%02d".format(d.dayOfMonth), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = fg))
        Row(Modifier.heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (hasEvent) PhIcon(Ph.Flask, size = 11.dp, tint = sub)
            if (stats.tot > 0) {
                if (stats.complete) PhIcon(Ph.CheckCircle, size = 12.dp, tint = if (selected) pal.accentInk else pal.accent)
                else Text("${stats.n}/${stats.tot}", style = TextStyle(fontSize = 11.sp, fontFamily = Mono, color = sub))
            }
        }
    }
}

@Composable
private fun DoseRow(
    x: Item, sel: LocalDate, today: LocalDate, s: AppState, on: Boolean, time: LocalTime?, skipped: Skipped?, late: String,
    onToggle: () -> Unit, onSkip: () -> Unit, onTime: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() }).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp).size(28.dp).clip(CircleShape).border(2.dp, if (on) pal.accent else pal.ink3, CircleShape).background(if (on) pal.accent else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { contentDescription = x.med.name + ", " + num(x.dose) + " " + x.med.unit },
            contentAlignment = Alignment.Center,
        ) { if (on) PhIcon(Ph.Check, size = 16.dp, tint = pal.accentInk) }
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (on) pal.ink2 else pal.ink)) { append(x.med.name) }
                    append("  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, fontFamily = Mono, color = pal.accent)) { append(num(x.dose) + " " + x.med.unit) }
                },
                style = TextStyle(fontSize = 15.sp),
            )
            if (x.med.substance.isNotEmpty()) Text(x.med.substance, style = TextStyle(fontSize = 13.sp, color = pal.ink2))
            s.stockForecast(x.med, today)?.let { f ->
                val dl = f.daysLeft
                val lowNow = dl != null && s.settings.stockWarnDays > 0 && dl <= s.settings.stockWarnDays
                Text("Zapas: " + stockText(f, x.med.unit), style = TextStyle(fontSize = 12.5.sp, fontWeight = if (lowNow) FontWeight.SemiBold else FontWeight.Normal, color = if (lowNow) pal.warn else pal.ink3), modifier = Modifier.padding(top = 2.dp))
            }
            x.med.end?.let { end ->
                Text("Dzień ${daysBetween(x.med.phases.first().from, sel) + 1} z ${daysBetween(x.med.phases.first().from, end) + 1}", style = TextStyle(fontSize = 13.sp, fontFamily = Mono, color = pal.ink2))
            }
            when {
                skipped != null && !on -> Text("Pominięta: " + skipped.reason + if (skipped.note.isNotEmpty()) ", " + skipped.note else "", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
                !on && late == "late" -> Text("Spóźniona, pora była o " + x.slot.time.hhmm(), style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
                !on && late == "past" -> Text("Nie odhaczono", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
            }
            if (x.med.note.isNotEmpty()) Text(x.med.note, style = TextStyle(fontSize = 12.5.sp, color = pal.ink3), modifier = Modifier.padding(top = 3.dp))
        }
        if (on) {
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onTime).padding(horizontal = 6.dp, vertical = 6.dp)) {
                Text(time?.hhmm() ?: "--:--", style = TextStyle(fontSize = 14.sp, fontFamily = Mono, color = pal.ink2))
            }
        } else if (sel <= today) {
            Box(
                Modifier.heightIn(min = 36.dp).clip(CtlShape).border(1.dp, pal.line, CtlShape).background(pal.surface).clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (skipped != null) "Zmień" else "Pomiń", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipDialog(x: Item, d: LocalDate, cur: Skipped?, onSave: (String, String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf(cur?.reason.orEmpty()) }
    var note by remember { mutableStateOf(cur?.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pominięta dawka", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BodyText("${x.med.name}, ${num(x.dose)} ${x.med.unit}, ${x.slot.name}, ${plDate(d)}. Zapisane powody trafią do raportu dla lekarza.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SKIP_REASONS.forEach { r -> Pill(r, reason == r) { reason = if (reason == r) "" else r } }
                }
                LekiField("Notatka (opcjonalnie)", note, { note = it })
                if (cur != null) LinkButton("Cofnij pominięcie", onClear, danger = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reason, note) }) { Text("Zapisz", color = pal.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

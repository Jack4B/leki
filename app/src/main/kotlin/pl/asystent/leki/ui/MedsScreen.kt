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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.stateDescription
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
import pl.asystent.leki.core.clearTimer
import pl.asystent.leki.core.dayStats
import pl.asystent.leki.core.eventLab
import pl.asystent.leki.core.eventResult
import pl.asystent.leki.core.daysBetween
import pl.asystent.leki.core.firstDate
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.jsDow
import pl.asystent.leki.core.longDay
import pl.asystent.leki.core.markAll
import pl.asystent.leki.core.markSlot
import pl.asystent.leki.core.minutes
import pl.asystent.leki.core.needBackup
import pl.asystent.leki.core.num
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.resetDay
import pl.asystent.leki.core.setDayNote
import pl.asystent.leki.core.setDoseTime
import pl.asystent.leki.core.setEventResult
import pl.asystent.leki.core.setWater
import pl.asystent.leki.core.stepWater
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.shortDate
import pl.asystent.leki.core.skipDose
import pl.asystent.leki.core.lowStock
import pl.asystent.leki.core.stockForecast
import pl.asystent.leki.core.stockOverview
import pl.asystent.leki.core.stockText
import pl.asystent.leki.core.toggleDose
import pl.asystent.leki.core.unmarkDoses
import java.time.LocalDate
import java.time.LocalTime

private val SKIP_REASONS = listOf("Zapomniano o dawce", "Złe samopoczucie", "Brak leku", "Zalecenie lekarza", "Inny powód")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedsScreen(s: AppState, store: Store, nav: Nav, onGoData: () -> Unit, modifier: Modifier = Modifier, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val recovered by store.recovered.collectAsState()
    val now by rememberNow()
    val today = now.toLocalDate()
    val toast = LocalToast.current
    val undo = LocalUndo.current
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

    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp)) {
        item { StorageProblemBanner(store, Modifier.padding(top = 6.dp)) }
        if (recovered.isNotEmpty() && !store.readOnly) item {
            AlertBox(
                "Plik danych był uszkodzony",
                body = "Uszkodzone pliki zachowano w pamięci aplikacji jako ${recovered.values.joinToString(", ")}; dotknięte osoby startują puste. " +
                    "Do czasu decyzji automatyczna kopia zapasowa jest wstrzymana. Wczytaj ostatnią kopię w Ustawieniach albo zacznij od nowa.",
                modifier = Modifier.padding(top = 8.dp),
                content = { LinkButton("Zaczynam od nowa", onClick = { store.acknowledgeRecovery() }) },
            )
        }

        // Pasek dni + szybkie przejście do dzisiaj, kalendarza i leków
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
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LekiButton("Dziś", { select(today) }, small = true, tonal = sel != today, primary = false, icon = Ph.CalendarCheck)
                DatePickerButton(sel, { select(it) })
                Spacer(Modifier.weight(1f))
                // tylko jeden przycisk po prawej, żeby rząd mieścił się na wąskim ekranie (lista leków jest pod planem dnia)
                LekiButton("Dodaj lek", nav.newMed, small = true, tonal = true, icon = Ph.Plus)
            }
        }

        // Karta podsumowania wybranego dnia (koło postępu, najbliższa pora, potas/woda i zwarty alert zapasu)
        item {
            val low = s.lowStock(today)
            val nextSlot = s.sortedSlots().firstOrNull { sl ->
                st.items.any { it.slot.id == sl.id && it.key !in rec.taken && it.key !in rec.skipped }
            }
            LekiCard(Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (st.tot > 0) ProgressRing(st.n, st.tot, size = 58.dp)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(longDay(sel), style = TextStyle(fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = pal.ink), modifier = Modifier.weight(1f, fill = false))
                                if (sel == today || sel > today) {
                                    StatusPill(if (sel == today) "dziś" else "plan", kind = "ok")
                                }
                            }
                            if (st.tot > 0) {
                                val statusLine = when {
                                    st.complete -> "Wszystkie dawki z planu przyjęte."
                                    sel > today -> "Zaplanowano ${st.tot} ${plN(st.tot, "dawkę", "dawki", "dawek")}."
                                    nextSlot != null -> "Do przyjęcia: ${nextSlot.name} (${nextSlot.time.hhmm()})"
                                    else -> "Przyjęto ${st.n} z ${st.tot} dawek."
                                }
                                Text(statusLine, style = TextStyle(fontSize = 13.sp, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
                            }
                            if (st.hasMmol || s.settings.waterGoal > 0) {
                                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (st.hasMmol) StatusPill("Potas: ${num(st.mmolTaken)}/${num(st.mmolPlan)} mmol")
                                    if (s.settings.waterGoal > 0) StatusPill("Woda: ${num(rec.water * s.settings.glassMl / 1000.0)} l")
                                }
                            }
                        }
                    }
                    if (st.tot > 1 && sel <= today && !st.complete) {
                        Divider()
                        LinkButton("Zaznacz wszystkie dawki z dnia jako przyjęte", { store.update { it.markAll(sel, today, now) } })
                    }
                    if (low.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clip(CtlShape).background(pal.warnBg).border(1.dp, pal.warnLine, CtlShape).clickable(role = Role.Button, onClick = nav.showMeds).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PhIcon(Ph.Warning, size = 18.dp, tint = pal.warn)
                            Column(Modifier.weight(1f)) {
                                Text("Kończy się zapas (${low.size})", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                                Text(low.joinToString("; ") { (m, f) -> m.name + ": " + stockText(f, m.unit) }, style = TextStyle(fontSize = 12.5.sp, color = pal.ink2), maxLines = 2)
                            }
                            Text("Uzupełnij ›", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
                        }
                    }
                }
            }
        }

        // najbliższe kontrole (do 14 dni), bez duplikowania bloku „za 1–3 dni”
        val soonEvents = s.events.filter { it.date >= today && it.date <= today.plusDays(14) && daysBetween(sel, it.date) !in 1..3 && it.date != sel }.sortedBy { it.date }
        if (soonEvents.isNotEmpty()) item {
            Column(Modifier.padding(top = 12.dp)) {
                LekiCard {
                    soonEvents.take(3).forEachIndexed { i, e ->
                        if (i > 0) Divider()
                        val n = daysBetween(today, e.date)
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = nav.showEvents).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(Ph.Stethoscope)
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                                Text(if (n == 0) "dziś" else if (n == 1) "jutro" else "za $n ${plN(n, "dzień", "dni", "dni")}, " + plDate(e.date), style = TextStyle(fontSize = 12.5.sp, color = pal.ink3))
                            }
                            PhIcon(Ph.CaretRight, size = 15.dp)
                        }
                    }
                }
            }
        }

        // kontrole w wybranym dniu i nadchodzące za 1–3 dni
        items(s.events.filter { it.date == sel }, key = { "ev" + it.id }) { e ->
            val done = s.eventResult(e)
            var text by remember(e.id, selStr) { mutableStateOf(done.orEmpty()) }
            var editing by remember(e.id, selStr) { mutableStateOf(false) }
            val fromLabs = s.eventLab(e) != null
            if (done != null && (e.field.isNotEmpty() || fromLabs) && !editing) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(CardShape).border(1.dp, pal.line, CardShape).background(pal.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PhIcon(Ph.Check, size = 22.dp, tint = pal.accent)
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        Text("Wynik zapisany: $done" + if (fromLabs) " (w Badaniach)" else "", style = TextStyle(fontSize = 13.5.sp, color = pal.ink2))
                    }
                    if (!fromLabs) LinkButton("Zmień", { text = done; editing = true })
                }
            } else AlertBox(e.title, body = e.note.ifEmpty { null }, modifier = Modifier.padding(top = 12.dp)) {
                if (e.field.isNotEmpty()) LekiField(
                    e.field, text, { text = it; editing = true; store.update { st2 -> st2.setEventResult(e.id, it) } },
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
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(CardShape).border(1.dp, pal.line, CardShape).background(pal.surface).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BodyText("Ostatnia kopia: $nb. Dane są tylko w telefonie.", Modifier.weight(1f))
                LinkButton("Zrób kopię", onGoData, Modifier.padding(start = 12.dp))
            }
        }

        // Lista pór dnia albo ekran startowy (pusty stan)
        if (s.meds.isEmpty()) item {
            LekiCard(Modifier.padding(top = 20.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(Ph.Pill)
                    Text("Nie dodano jeszcze żadnych leków", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink), textAlign = TextAlign.Center)
                    BodyText("Dodaj pierwszy lek (ręcznie albo ze zdjęcia opakowania) lub wczytaj kopię zapasową w Ustawieniach.", color = pal.ink3)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LekiButton("Dodaj lek", nav.newMed, Modifier.weight(1f), primary = true, icon = Ph.Plus)
                        LekiButton("Wczytaj kopię", onGoData, Modifier.weight(1f), tonal = true, icon = Ph.UploadSimple)
                    }
                }
            }
        } else if (st.tot == 0) item {
            EmptyBox("Na ten dzień nie zaplanowano żadnych leków.", Modifier.padding(top = 20.dp))
        } else {
            s.sortedSlots().forEach { slot ->
                val items = st.items.filter { it.slot.id == slot.id }
                if (items.isEmpty()) return@forEach
                val late = lateState(sel, slot.time, today, now.toLocalTime().minutes, s.settings.lateMin)
                val openKeys = items.filter { it.key !in rec.taken && it.key !in rec.skipped }.map { it.key }
                val lateN = if (late == "late") openKeys.size else 0
                item(key = "slot" + slot.id) {
                    Column(Modifier.padding(top = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(if (openKeys.isEmpty()) pal.accentSoft else pal.sunken), contentAlignment = Alignment.Center) {
                                    PhIcon(slotIcon(slot.time), size = 18.dp, tint = if (openKeys.isEmpty()) pal.accent else pal.ink2)
                                }
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(slot.name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.ink))
                                    // jeden tekst (zawija się między słowami, a nie w środku „spóźnione” obok przycisku na wąskim ekranie)
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = pal.ink3, fontFeatureSettings = TNUM)) { append(slot.time.hhmm()) }
                                            if (lateN > 0) withStyle(SpanStyle(color = pal.warn, fontWeight = FontWeight.SemiBold)) { append(" · spóźnione: $lateN") }
                                        },
                                        style = TextStyle(fontSize = 12.sp),
                                    )
                                }
                            }
                            if (sel <= today && openKeys.size >= 2) {
                                LekiButton(
                                    "Weź porę (${openKeys.size})",
                                    onClick = {
                                        store.update { it.markSlot(sel, slot.id, today, now) }
                                        undo("Odhaczono porę: ${slot.name} (${openKeys.size})") {
                                            store.update { it.unmarkDoses(sel, openKeys).clearTimer() }
                                        }
                                    },
                                    small = true, primary = late == "late", tonal = late != "late", icon = Ph.Check,
                                )
                            } else if (openKeys.isEmpty() && items.any { it.key in rec.taken }) {
                                StatusPill("✓ Komplet", kind = "ok")
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

        // Ergonomiczna karta nawodnienia (przyciski − / + szklanka oraz pasek objętości i krople)
        val goal = s.settings.waterGoal
        if (goal > 0) item {
            LekiCard(Modifier.padding(top = 20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PhIcon(Ph.Drop, size = 18.dp, tint = pal.accent)
                                Text("Woda", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.ink))
                            }
                            Text(
                                "${num(rec.water * s.settings.glassMl / 1000.0)} l z ${num(goal * s.settings.glassMl / 1000.0)} l (${rec.water}/$goal, szklanka ${s.settings.glassMl} ml)",
                                style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = TNUM, color = pal.ink3),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LekiButton(
                                "−",
                                { if (sel > today) toast("Nie można zapisywać wody z przyszłości.") else store.update { it.stepWater(sel, -1) } },
                                small = true, enabled = rec.water > 0,
                            )
                            LekiButton(
                                "+1",
                                { if (sel > today) toast("Nie można zapisywać wody z przyszłości.") else store.update { it.stepWater(sel, 1) } },
                                small = true, primary = true, icon = Ph.Drop,
                            )
                        }
                    }
                    ProgressBar(rec.water.toFloat() / goal)
                    if (goal <= 12) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (i in 1..goal) {
                                val on = i <= rec.water
                                Box(
                                    Modifier.size(width = 28.dp, height = 36.dp).clip(RoundedCornerShape(9.dp))
                                        .border(1.dp, if (on) pal.accent else pal.line, RoundedCornerShape(9.dp))
                                        .background(if (on) pal.accentSoft else pal.surface)
                                        .clickable(role = Role.Button) { if (sel > today) toast("Nie można zapisywać wody z przyszłości.") else store.update { it.setWater(sel, i) } }
                                        .semantics { contentDescription = "$i ${plN(i, "szklanka", "szklanki", "szklanek")}"; stateDescription = if (on) "wypite" else "niewypite" },
                                    contentAlignment = Alignment.Center,
                                ) { PhIcon(Ph.Drop, size = 14.dp, tint = if (on) pal.accent else pal.ink3) }
                            }
                        }
                    }
                }
            }
        }

        if (s.meds.isNotEmpty()) item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                LinkButton("Dodaj lek ›", nav.newMed)
                LinkButton(if (s.stockOverview(today).isNotEmpty()) "Leki i zapasy ›" else "Wszystkie leki ›", nav.showMeds)
            }
        }
        item {
            var note by remember(selStr) { mutableStateOf(rec.note) }
            Column(Modifier.padding(top = 20.dp)) {
                LekiField("Notatka dnia", note, { note = it; store.update { st2 -> st2.setDayNote(sel, it) } }, singleLine = false, minLines = 3, placeholder = "Samopoczucie, objawy, uwagi")
                LinkButton("Wyczyść odhaczenia z tego dnia", { confirmReset = true })
            }
        }
    }

    skipFor?.let { x ->
        SkipDialog(x, sel, rec.skipped[x.key], onSave = { r, n -> store.update { it.skipDose(sel, x.key, r, n) }; skipFor = null; toast("Zapisano pominięcie dawki") },
            onClear = { store.update { it.clearSkip(sel, x.key) }; skipFor = null }, onEditMed = { skipFor = null; nav.editMed(x.med.id) }, onDismiss = { skipFor = null })
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
        Modifier.width(58.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, if (selected) pal.accent else pal.line, RoundedCornerShape(16.dp)).background(bg)
            .clickable(role = Role.Tab, onClick = onClick).padding(top = 9.dp, bottom = 8.dp)
            .semantics { contentDescription = longDay(d) + if (stats.tot > 0) ", przyjęto ${stats.n} z ${stats.tot}" else "" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(if (d == today) "DZIŚ" else Pl.dowShort[d.jsDow].uppercase(), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = sub, letterSpacing = 0.5.sp))
        Text("%02d".format(d.dayOfMonth), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM, color = fg))
        Row(Modifier.heightIn(min = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (hasEvent) PhIcon(Ph.Flask, size = 11.dp, tint = sub)
            if (stats.tot > 0) {
                if (stats.complete) PhIcon(Ph.CheckCircle, size = 12.dp, tint = if (selected) pal.accentInk else pal.accent)
                else Text("${stats.n}/${stats.tot}", style = TextStyle(fontSize = 11.sp, fontFeatureSettings = TNUM, color = sub))
            }
        }
    }
}

@Composable
private fun DoseRow(
    x: Item, sel: LocalDate, today: LocalDate, s: AppState, on: Boolean, time: LocalTime?, skipped: Skipped?, late: String,
    onToggle: () -> Unit, onSkip: () -> Unit, onTime: () -> Unit,
) {
    val rowBg = if (on) pal.accentSoft.copy(alpha = if (pal.dark) 0.25f else 0.35f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(rowBg).toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle() }).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(28.dp).clip(CircleShape).border(2.dp, if (on) pal.accent else pal.ink3, CircleShape)
                .background(if (on) pal.accent else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { contentDescription = x.med.name + ", " + num(x.dose) + " " + x.med.unit },
            contentAlignment = Alignment.Center,
        ) { if (on) PhIcon(Ph.Check, size = 16.dp, tint = pal.accentInk) }
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (on) pal.ink2 else pal.ink)) { append(x.med.name) }
                    append("  ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFeatureSettings = TNUM, color = pal.accent)) { append(num(x.dose) + " " + x.med.unit) }
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
                Text("Dzień ${daysBetween(x.med.phases.first().from, sel) + 1} z ${daysBetween(x.med.phases.first().from, end) + 1}", style = TextStyle(fontSize = 12.5.sp, fontFeatureSettings = TNUM, color = pal.ink2))
            }
            when {
                skipped != null && !on -> Text("Pominięta: " + skipped.reason + if (skipped.note.isNotEmpty()) ", " + skipped.note else "", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
                !on && late == "late" -> Text("Spóźniona, pora była o " + x.slot.time.hhmm(), style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
                !on && late == "past" -> Text("Nie odhaczono", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
            }
            if (x.med.note.isNotEmpty()) Text(x.med.note, style = TextStyle(fontSize = 12.5.sp, color = pal.ink3), modifier = Modifier.padding(top = 3.dp))
        }
        if (on) {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(pal.surface).border(1.dp, pal.line, RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onTime).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(time?.hhmm() ?: "--:--", style = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, color = pal.ink2))
            }
        } else if (sel <= today) {
            Box(
                Modifier.heightIn(min = 38.dp).clip(CtlShape).border(1.dp, pal.line, CtlShape).background(pal.surface).clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (skipped != null) "Zmień" else "Pomiń", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipDialog(x: Item, d: LocalDate, cur: Skipped?, onSave: (String, String) -> Unit, onClear: () -> Unit, onEditMed: () -> Unit, onDismiss: () -> Unit) {
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    LinkButton("Edytuj lek i zapas ›", onEditMed)
                    if (cur != null) LinkButton("Cofnij pominięcie", onClear, danger = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(reason, note) }) { Text("Zapisz", color = pal.accent, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj", color = pal.ink2) } },
        containerColor = pal.surface,
    )
}

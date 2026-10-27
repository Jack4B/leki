package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(8)

/** Zmiany stanu aplikacji jako czyste funkcje (łatwe do przetestowania). */

// ---------- Dzień: odhaczanie dawek, woda, notatka ----------

fun nowMillis(now: LocalDateTime): Long = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Odhacza lub cofa dawkę. Dla dzisiejszej dawki leku ze stoperem uruchamia odliczanie. Przyszłych dni nie wolno odhaczać (sprawdza interfejs). */
fun AppState.toggleDose(d: LocalDate, key: String, today: LocalDate, now: LocalDateTime): AppState {
    val item = scheduleFor(d).firstOrNull { it.key == key } ?: return this
    val r = day(d)
    if (key in r.taken) return updateDay(d) { it.copy(taken = it.taken - key, times = it.times - key) }
    val t = if (d == today) now.toLocalTime().withSecond(0).withNano(0) else item.slot.time
    var next = updateDay(d) { it.copy(taken = it.taken + key, times = it.times + (key to t), skipped = it.skipped - key) }
    if (item.med.timerMin > 0 && d == today) {
        next = next.copy(timerEnd = nowMillis(now) + item.med.timerMin * 60_000L, timerMed = item.med.name, timerNote = item.med.note)
    }
    return next
}

/** Odhacza dawki dnia, które są już po porze i nie zostały świadomie pominięte (pominięcie z powodem zostaje, a dawki z późniejszych pór dziś nie są ruszane). */
fun AppState.markAll(d: LocalDate, today: LocalDate, now: LocalDateTime): AppState {
    var s = this
    for (x in scheduleFor(d)) if (x.key !in s.day(d).taken && x.key !in s.day(d).skipped && !(d == today && x.slot.time.minutes > now.hour * 60 + now.minute)) {
        val t = if (d == today) now.toLocalTime().withSecond(0).withNano(0) else x.slot.time
        s = s.updateDay(d) { it.copy(taken = it.taken + x.key, times = it.times + (x.key to t), skipped = it.skipped) }
    }
    return s
}

/** Dawki z pory [slotId] w dniu [d], które nie są ani odhaczone, ani pominięte (to, o czym przypomina powiadomienie). */
fun AppState.openDoses(d: LocalDate, slotId: String): List<Item> {
    val r = day(d)
    return scheduleFor(d).filter { it.slot.id == slotId && it.key !in r.taken && it.key !in r.skipped }
}

/**
 * Odhacza jednym ruchem wszystkie jeszcze otwarte dawki pory (przycisk „Wzięte” w powiadomieniu), tak jak ręczne odhaczenie: godzina przyjęcia to teraz
 * (dla dzisiaj) albo pora z planu, a lek ze stoperem uruchamia odliczanie.
 */
fun AppState.markSlot(d: LocalDate, slotId: String, today: LocalDate, now: LocalDateTime): AppState {
    var s = this
    for (x in openDoses(d, slotId)) s = s.toggleDose(d, x.key, today, now)
    return s
}

/** Cofa odhaczenie dawek o podanych kluczach (przycisk „Cofnij” po „Wzięte”): zdejmuje odhaczenie i godzinę; stoper zostaje zatrzymany przez [clearTimer] po stronie wywołującego. */
fun AppState.unmarkDoses(d: LocalDate, keys: Collection<String>): AppState =
    if (keys.isEmpty()) this else updateDay(d) { it.copy(taken = it.taken - keys.toSet(), times = it.times - keys.toSet()) }

fun AppState.setDoseTime(d: LocalDate, key: String, t: LocalTime): AppState = updateDay(d) { it.copy(times = it.times + (key to t)) }

fun AppState.skipDose(d: LocalDate, key: String, reason: String, note: String): AppState = updateDay(d) {
    it.copy(skipped = it.skipped + (key to Skipped(reason.ifEmpty { "Bez podania powodu" }, note.trim())), taken = it.taken - key, times = it.times - key)
}

fun AppState.clearSkip(d: LocalDate, key: String): AppState = updateDay(d) { it.copy(skipped = it.skipped - key) }

fun AppState.setWater(d: LocalDate, n: Int): AppState = updateDay(d) { it.copy(water = if (it.water == n) n - 1 else n) }

fun AppState.setDayNote(d: LocalDate, text: String): AppState = updateDay(d) { it.copy(note = text) }

fun AppState.resetDay(d: LocalDate): AppState = copy(days = days - d)

/** Wynik wpisany w dniu kontroli trafia też do dziennika badań (id wyniku: "ev:<id kontroli>"). */
fun AppState.setEventResult(eventId: String, text: String): AppState {
    val e = event(eventId) ?: return this
    val t = text.trim()
    var s = updateDay(e.date) { it.copy(results = if (t.isEmpty()) it.results - eventId else it.results + (eventId to t)) }
    s = s.copy(labs = s.labs.filter { it.id != "ev:$eventId" })
    if (e.marker.isNotEmpty() && !s.hasOtherLab(e)) parseNum(t)?.let { n -> s = s.copy(labs = s.labs + Lab("ev:$eventId", e.date, e.marker, n, e.title)) }
    return s
}

/** Wynik tego parametru z dnia kontroli, który nie pochodzi z samej kontroli (z pliku, zdjęcia albo wpisany w Badaniach). */
fun AppState.eventLab(e: Event): Lab? =
    if (e.marker.isEmpty()) null else labs.firstOrNull { it.marker == e.marker && it.date == e.date && it.id != "ev:${e.id}" }

private fun AppState.hasOtherLab(e: Event): Boolean = eventLab(e) != null

/** Wynik kontroli: wpisany w polu kontroli albo, gdy parametr jest już w dzienniku badań z dnia kontroli, jego wartość. Null = kontrola czeka na wynik. */
fun AppState.eventResult(e: Event): String? =
    day(e.date).results[e.id]?.takeIf { it.isNotBlank() } ?: (eventLab(e) ?: labs.firstOrNull { it.id == "ev:${e.id}" })?.let { lnum(it.value) }

/** Wynik z dziennika badań trafia do kontroli tego samego parametru i dnia, która nie ma jeszcze wyniku (kontrola przestaje czekać na wpis i nie ma dubla przy ręcznym wpisie). */
private fun AppState.linkLabToEvents(l: Lab, old: Lab? = null): AppState {
    var s = this
    for (e in events) if (e.marker == l.marker && e.date == l.date && s.day(e.date).results[e.id].let { it.isNullOrBlank() || (old != null && it == lnum(old.value)) })
        s = s.updateDay(e.date) { it.copy(results = it.results + (e.id to lnum(l.value))) }
    return s
}

// ---------- Ciśnienie ----------

fun AppState.addBp(b: Bp): AppState = copy(bp = bp + b)
fun AppState.deleteBp(id: String): AppState = copy(bp = bp.filter { it.id != id })

// ---------- Badania ----------

fun AppState.saveLab(l: Lab): AppState {
    val old = labs.firstOrNull { it.id == l.id }
    val exists = old != null
    var s = copy(labs = if (exists) labs.map { if (it.id == l.id) l else it } else labs + l)
    if (l.id.startsWith("ev:")) {
        val eid = l.id.removePrefix("ev:")
        s.event(eid)?.let { e -> s = s.updateDay(e.date) { it.copy(results = it.results + (eid to lnum(l.value))) } }
    } else s = s.linkLabToEvents(l, old)
    return s
}

fun AppState.deleteLab(id: String): AppState {
    val gone = labs.firstOrNull { it.id == id }
    var s = copy(labs = labs.filter { it.id != id })
    if (id.startsWith("ev:")) {
        val eid = id.removePrefix("ev:")
        s.event(eid)?.let { e -> s = s.updateDay(e.date) { it.copy(results = it.results - eid) } }
    } else if (gone != null) {
        // kontrola, której wynik przyszedł z usuniętego wyniku, znów czeka na wpis
        for (e in s.events) if (e.marker == gone.marker && e.date == gone.date && s.day(e.date).results[e.id] == lnum(gone.value) && s.labs.none { it.id == "ev:${e.id}" })
            s = s.updateDay(e.date) { it.copy(results = it.results - e.id) }
    }
    return s
}

fun AppState.markerUsed(id: String) = labs.any { it.marker == id } || events.any { it.marker == id }

fun AppState.saveMarker(m: Marker): AppState =
    if (markers.any { it.id == m.id }) copy(markers = markers.map { if (it.id == m.id) m else it }) else copy(markers = markers + m)

fun AppState.deleteMarker(id: String): AppState = copy(markers = markers.filter { it.id != id })

data class LabsMergePreview(val fresh: Int, val skipped: Int, val newMarkers: Int)

fun AppState.previewLabsMerge(f: Backup.LabsFile): LabsMergePreview {
    val fileIds = f.markers.map { it.id }.toSet()
    val fresh = f.labs.filter { l -> (marker(l.marker) != null || l.marker in fileIds) && labs.none { it.marker == l.marker && it.date == l.date } }
    return LabsMergePreview(fresh.size, f.labs.size - fresh.size, f.markers.count { marker(it.id) == null })
}

/** Dołącza wyniki z pliku: zakresy odniesienia parametrów z pliku zastępują dotychczasowe, a wyniki z tym samym parametrem i dniem są pomijane. */
fun AppState.applyLabsMerge(f: Backup.LabsFile): AppState {
    var s = this
    for (m in f.markers) {
        val cur = s.marker(m.id)
        s = if (cur != null) s.saveMarker(cur.copy(lo = m.lo ?: cur.lo, hi = m.hi ?: cur.hi, unit = cur.unit.ifEmpty { m.unit }, grp = cur.grp.ifEmpty { m.grp }))
        else s.saveMarker(m)
    }
    val add = f.labs.filter { l -> s.marker(l.marker) != null && s.labs.none { it.marker == l.marker && it.date == l.date } }
    return add.fold(s.copy(labs = s.labs + add)) { acc, l -> acc.linkLabToEvents(l) }
}

// ---------- Kontrole ----------

fun AppState.saveEvent(e: Event): AppState {
    val old = event(e.id)
    var s = if (old != null) copy(events = events.map { if (it.id == e.id) e else it }) else copy(events = events + e)
    // zmiana daty kontroli: wynik zapisany pod starą datą przechodzi razem z nią
    if (old != null && old.date != e.date) {
        val moved = s.day(old.date).results[e.id]
        if (moved != null) s = s.updateDay(old.date) { it.copy(results = it.results - e.id) }.updateDay(e.date) { it.copy(results = it.results + (e.id to moved)) }
    }
    // wynik wpisany wcześniej w dniu kontroli ma trafić do dziennika badań
    val res = s.day(e.date).results[e.id].orEmpty()
    var labs2 = s.labs.filter { it.id != "ev:${e.id}" }
    s = s.copy(labs = labs2)
    if (e.marker.isNotEmpty() && !s.hasOtherLab(e)) parseNum(res)?.let { n -> labs2 = labs2 + Lab("ev:${e.id}", e.date, e.marker, n, e.title) }
    s = s.copy(labs = labs2)
    // wynik tego parametru z dnia kontroli już jest w dzienniku badań: kontrola od razu go pokazuje
    return s.labs.firstOrNull { it.marker == e.marker && it.date == e.date && e.marker.isNotEmpty() }?.let { s.linkLabToEvents(it) } ?: s
}

fun AppState.deleteEvent(id: String): AppState = copy(events = events.filter { it.id != id })

// ---------- Pory dnia ----------

fun AppState.saveSlot(s: Slot): AppState =
    if (slots.any { it.id == s.id }) copy(slots = slots.map { if (it.id == s.id) s else it }) else copy(slots = slots + s)

fun AppState.slotUsage(id: String): Int = meds.count { m -> m.phases.any { (it.doses[id] ?: 0.0) > 0 } }

/** Usuwa porę dnia i jej dawki z planu leków; wcześniejsze odhaczenia zostają. Zwraca null, gdy to ostatnia pora. */
fun AppState.deleteSlot(id: String): AppState? {
    if (slots.size <= 1) return null
    return copy(slots = slots.filter { it.id != id }, meds = meds.map { m -> m.copy(phases = m.phases.map { p -> p.copy(doses = p.doses - id) }) })
}

// ---------- Leki ----------

data class MedEdit(
    val id: String?, val name: String, val substance: String, val note: String, val unit: String,
    val timerMin: Int, val mmol: Double, val end: LocalDate?, val from: LocalDate,
    val doses: Map<String, Double>, val dow: List<Int>, val replaces: String? = null,
    /** Ile zostało w opakowaniu teraz (null = zapasu nie śledzimy). [stockEdited]: użytkownik zmienił tę liczbę (inaczej zostaje zapisany stan). */
    val stockLeft: Double? = null, val stockEdited: Boolean = false, val today: LocalDate = LocalDate.now(),
)

sealed class MedSave {
    data class Ok(val state: AppState, val message: String) : MedSave()
    data class Error(val message: String) : MedSave()
}

fun AppState.saveMed(e: MedEdit): MedSave {
    val existing = e.id?.let { med(it) }
    val name = e.name.trim()
    if (name.isEmpty()) return MedSave.Error("Wpisz nazwę leku.")
    val doses = e.doses.filter { (k, v) -> v > 0 && slot(k) != null }
    if (doses.isEmpty()) return MedSave.Error("Ustaw dawkę w przynajmniej jednej porze dnia.")
    if (e.dow.isEmpty()) return MedSave.Error("Wybierz przynajmniej jeden dzień tygodnia.")
    val first = if (existing == null) e.from else minOf(existing.phases.first().from, e.from)
    if (e.end != null && e.end < first) return MedSave.Error("Ostatni dzień przyjmowania nie może być przed początkiem dawkowania.")
    var m = existing ?: Med(id = newId(), name = name, phases = emptyList())
    m = m.copy(name = name, substance = e.substance.trim(), note = e.note.trim(), unit = e.unit.trim().ifEmpty { "tabl." },
        timerMin = e.timerMin.coerceIn(0, 600), mmol = e.mmol.coerceIn(0.0, 1000.0), end = e.end)
    m = m.upsertPhase(e.from, doses, e.dow.sorted())
    var s = if (existing == null) copy(meds = meds + m) else copy(meds = meds.map { if (it.id == m.id) m else it })
    // zapas: wpisana liczba oznacza stan „teraz”; bez zmiany liczby zostaje poprzedni zapis, a puste pole wyłącza śledzenie
    val stockMed = when {
        e.stockLeft == null -> m.withoutStock()
        e.stockEdited || m.stock == null -> m.withStockLeft(s, e.stockLeft, e.today)
        else -> m
    }
    if (stockMed != m) { m = stockMed; s = s.copy(meds = s.meds.map { if (it.id == m.id) m else it }) }
    var note = if (existing == null) "Dodano lek $name" else "Zapisano zmiany: $name"
    if (e.replaces != null) {
        val old = s.med(e.replaces)
        val last = e.from.minusDays(1)
        if (old != null) {
            if (old.end == null || old.end > last) s = s.copy(meds = s.meds.map { if (it.id == old.id) it.copy(end = last) else it })
            note = "Dodano $name. ${old.name} zakończony ${plDate(last)}."
        }
    }
    return MedSave.Ok(s, note)
}

fun AppState.deletePhase(medId: String, from: LocalDate): AppState = copy(meds = meds.map { m ->
    if (m.id == medId && m.phases.size > 1) m.copy(phases = m.phases.filter { it.from != from }) else m
})

/** Usuwa lek razem z odhaczeniami i pominięciami tego leku. */
fun AppState.deleteMed(id: String): AppState {
    val prefix = "$id|"
    return copy(
        meds = meds.filter { it.id != id },
        days = days.mapValues { (_, r) ->
            r.copy(taken = r.taken.filterNot { it.startsWith(prefix) }.toSet(), times = r.times.filterKeys { !it.startsWith(prefix) }, skipped = r.skipped.filterKeys { !it.startsWith(prefix) })
        },
    )
}

// ---------- Inne ----------

/** Tekst przypomnienia o kopii zapasowej albo null, gdy nie trzeba. */
fun AppState.needBackup(today: LocalDate): String? {
    // dane do stracenia to nie tylko ciśnienie i odhaczenia, ale też leki, badania, EKG i analizy
    if (bp.isEmpty() && days.isEmpty() && meds.isEmpty() && labs.isEmpty() && ecgs.isEmpty() && analyses.isEmpty() && cykl.isEmpty) return null
    val lb = lastBackup ?: return if (daysBetween(created, today) >= 3) "nigdy" else null
    val n = daysBetween(lb, today)
    return if (n >= 7) "$n dni temu" else null
}

fun AppState.clearTimer(): AppState = copy(timerEnd = 0)

fun AppState.withBackupMark(today: LocalDate): AppState = copy(lastBackup = today)

// ---------- Analizy wklejone przez użytkownika ----------

fun AppState.saveAnalysis(a: Analysis): AppState =
    if (analyses.any { it.id == a.id }) copy(analyses = analyses.map { if (it.id == a.id) a else it }) else copy(analyses = analyses + a)

fun AppState.deleteAnalysis(id: String): AppState =
    copy(analyses = analyses.filter { it.id != id }, settings = if (settings.rp.analysisId == id) settings.copy(rp = settings.rp.copy(analysisId = "")) else settings)

// ---------- EKG z zegarka ----------

fun AppState.saveEcg(e: Ecg): AppState =
    if (ecgs.any { it.id == e.id }) copy(ecgs = ecgs.map { if (it.id == e.id) e else it }) else copy(ecgs = ecgs + e)

fun AppState.deleteEcg(id: String): AppState =
    copy(ecgs = ecgs.filter { it.id != id }, settings = settings.rp.ecgIds?.let { ids -> settings.copy(rp = settings.rp.copy(ecgIds = ids - id)) } ?: settings)

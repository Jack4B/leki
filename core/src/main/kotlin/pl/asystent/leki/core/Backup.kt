package pl.asystent.leki.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Wczytywanie i zapis danych w formacie kopii zapasowej wersji 3 (taki sam jak w poprzednich wersjach aplikacji).
 * Wczytywanie jest tolerancyjne: błędne fragmenty są pomijane, brakujące pola dostają wartości domyślne.
 */
object Backup {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    private val compact = Json { encodeDefaults = true }

    class FormatException(message: String) : Exception(message)

    /** Górny limit rozmiaru importowanego pliku kopii (zwykła kopia ma kilkaset KB; EKG może ją powiększyć do kilku MB). */
    const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

    /**
     * Największa dawka jednorazowa wczytywana z kopii, w jednostce leku (tabletki, ml, mg…). Dawki bywają podawane w mg (np. 500 albo 850),
     * więc dawny limit 50 po cichu usuwał prawidłowe dawki; 1000 mieści typowe dawki w mg i odrzuca tylko oczywiste błędy (np. wynik
     * pomyłki w znaku dziesiętnym). Limit nie zależy od jednostki: taka walidacja wymagałaby listy jednostek i znajomości leku, a błędne
     * odrzucenie dawki jest groźniejsze niż przepuszczenie niezwykłej wartości (użytkownik widzi ją w aplikacji i może poprawić).
     */
    const val MAX_DOSE = 1000.0

    class TooLargeException : Exception("Plik jest za duży (limit ${MAX_IMPORT_BYTES / (1024 * 1024)} MB). To nie wygląda na kopię z tej aplikacji.")

    /** Zamienia bajty odczytane z limitem (najwyżej [MAX_IMPORT_BYTES] + 1) na tekst; za duży plik kończy się [TooLargeException]. */
    fun decodeImport(bytes: ByteArray): String {
        if (bytes.size > MAX_IMPORT_BYTES) throw TooLargeException()
        return String(bytes, Charsets.UTF_8)
    }

    // ---------- Pomocnicze odczyty ----------

    private val isoRe = Regex("""^\d{4}-\d\d-\d\d$""")
    private val timeRe = Regex("""^\d{2}:\d{2}$""")

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.strOr(def: String = ""): String =
        (this as? JsonPrimitive)?.let { if (it.isString) it.content else it.content } ?: def
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonElement?.date(): LocalDate? = str()?.takeIf { isoRe.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun JsonElement?.time(): LocalTime? = str()?.takeIf { timeRe.matches(it) }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    private fun JsonElement?.truthy(): Boolean = when (this) {
        null, JsonNull -> false
        is JsonPrimitive -> if (isString) content.isNotEmpty() else (booleanOrNull ?: ((doubleOrNull ?: 0.0) != 0.0))
        else -> true
    }

    /** Odpowiednik parseFloat z JavaScriptu: liczba z początku tekstu, przecinek jak kropka. */
    private val floatRe = Regex("""^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
    private fun jsFloat(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (!p.isString) return p.doubleOrNull
        val m = floatRe.find(p.content.replace(',', '.')) ?: return null
        return m.value.trim().toDoubleOrNull()
    }

    private fun numOr(v: JsonElement?, def: Double, min: Double, max: Double): Double {
        val n = jsFloat(v) ?: return def
        return n.coerceIn(min, max)
    }

    private fun intOr(v: JsonElement?, def: Int, min: Int, max: Int): Int =
        Math.round(numOr(v, def.toDouble(), min.toDouble(), max.toDouble())).toInt()

    private fun newId() = UUID.randomUUID().toString().take(8)

    // ---------- Wczytywanie ----------

    /** Czy [o] to plik stanu aplikacji (a nie zaszyfrowana kopia czy plik wyników)? */
    fun parseEnvelope(text: String): JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw FormatException("json")
    } catch (e: StackOverflowError) {
        // plik z bardzo głębokim zagnieżdżeniem nawiasów
        throw FormatException("json")
    }

    fun kindOf(o: JsonElement): String = when (o.obj()["app"].str()) {
        "asystent-lekow-enc" -> "enc"
        "asystent-lekow-wyniki" -> "labs"
        else -> "state"
    }

    /** Wczytuje stan z pliku kopii (z obwolutą `{app, version, data}` albo samym obiektem danych). */
    fun parseState(o: JsonElement): AppState {
        val root = o.obj()
        val d = if (root["data"] is JsonObject) root["data"].obj() else root
        if (d["days"] !is JsonObject) throw FormatException("format")
        val ver = d["v"].number() ?: root["version"].number()
        if (ver != 3.0) throw FormatException("version")
        return normalize(d)
    }

    fun parseState(text: String): AppState = parseState(parseEnvelope(text))

    fun normalize(d: JsonObject): AppState {
        val st = d["settings"].obj()
        val rpIn = st["rp"].obj()
        val aiIn = st["ai"].obj()
        val rmIn = st["remind"].obj()
        val rpD = ReportParts()
        val rp = ReportParts(
            bp = rpIn["bp"].bool(rpD.bp), labs = rpIn["labs"].bool(rpD.labs), labsPeriod = rpIn["labsPeriod"].bool(rpD.labsPeriod),
            meds = rpIn["meds"].bool(rpD.meds), plan = rpIn["plan"].bool(rpD.plan), events = rpIn["events"].bool(rpD.events),
            analysis = rpIn["analysis"].bool(rpD.analysis), analysisId = rpIn["analysisId"].str() ?: "", ecg = rpIn["ecg"].bool(rpD.ecg), cykl = rpIn["cykl"].bool(rpD.cykl),
            ecgIds = rpIn["ecgIds"].arr()?.mapNotNull { it.str() },
        )
        val aD = AiParts()
        val ai = AiParts(
            meds = aiIn["meds"].bool(aD.meds), history = aiIn["history"].bool(aD.history), adherence = aiIn["adherence"].bool(aD.adherence),
            bp = aiIn["bp"].bool(aD.bp), labs = aiIn["labs"].bool(aD.labs), events = aiIn["events"].bool(aD.events),
            notes = aiIn["notes"].bool(aD.notes), ecg = aiIn["ecg"].bool(aD.ecg), cykl = aiIn["cykl"].bool(aD.cykl), name = aiIn["name"].bool(aD.name),
            context = aiIn["context"].str()?.take(2000) ?: "",
            detail = intOr(aiIn["detail"], aD.detail, 0, 1),
        )
        val D = Settings()
        val settings = Settings(
            rp = rp, ai = ai,
            lateMin = intOr(st["lateMin"], D.lateMin, 0, 600),
            waterGoal = intOr(st["waterGoal"], D.waterGoal, 0, 20),
            glassMl = intOr(st["glassMl"], D.glassMl, 50, 1000),
            hiSys = intOr(st["hiSys"], D.hiSys, 100, 250), hiDia = intOr(st["hiDia"], D.hiDia, 50, 150),
            loSys = intOr(st["loSys"], D.loSys, 60, 130), loDia = intOr(st["loDia"], D.loDia, 30, 100),
            theme = ThemeMode.values().firstOrNull { it.id == st["theme"].str() } ?: ThemeMode.Auto,
            reportNote = st["reportNote"].str() ?: "",
            remindOn = rmIn["on"].bool(false),
            remindLead = intOr(rmIn["lead"], 0, 0, 120),
            stockWarnDays = intOr(rmIn["stock"], D.stockWarnDays, 0, 60),
            backupRemindDays = intOr(rmIn["backup"], D.backupRemindDays, 0, 365),
            cyklOn = st["cyklOn"].bool(false),
            cyklFrom = st["cyklFrom"].str()?.takeIf { Regex("p\\d{1,2}|#\\d").matches(it) } ?: "",
            bpOn = st["bpOn"].bool(true),
            labsOn = st["labsOn"].bool(true),
        )

        val slots = d["slots"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val time = o["time"].time() ?: return@mapNotNull null
            Slot(id, name, time)
        }.ifEmpty { Defaults.slots() }

        val meds = d["meds"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val name = o["name"].str() ?: return@mapNotNull null
            val phasesIn = o["phases"].arr() ?: return@mapNotNull null
            val phases = phasesIn.mapNotNull { pe ->
                val p = pe as? JsonObject ?: return@mapNotNull null
                val from = p["from"].date() ?: return@mapNotNull null
                val dosesIn = p["doses"] as? JsonObject ?: return@mapNotNull null
                val doses = LinkedHashMap<String, Double>()
                dosesIn.forEach { (k, v) -> val n = jsFloat(v) ?: 0.0; if (n > 0 && n <= MAX_DOSE) doses[k] = n }
                val dow = (p["dow"].arr()?.mapNotNull { it.number() } ?: Defaults.allDow.map { it.toDouble() })
                    .filter { it in 0.0..6.0 && it % 1.0 == 0.0 }.map { it.toInt() }
                Phase(from, doses, dow.ifEmpty { Defaults.allDow })
            }.sortedBy { it.from }
            if (phases.isEmpty()) return@mapNotNull null
            Med(
                id = id, name = name, substance = o["substance"].strOr(), note = o["note"].strOr(),
                unit = o["unit"].strOr("tabl.").ifEmpty { "tabl." },
                timerMin = intOr(o["timerMin"], 0, 0, 600), mmol = numOr(o["mmol"], 0.0, 0.0, 1000.0),
                end = o["end"].date(), phases = phases,
                stock = o["stock"].number()?.takeIf { it in 0.0..100000.0 }, stockDate = o["stockDate"].date(),
            ).let { if (it.stock == null || it.stockDate == null) it.copy(stock = null, stockDate = null) else it }
        }

        val markers = d["markers"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = o["name"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Marker(id, name, o["unit"].strOr(), jsFloatOrNull(o["lo"]), jsFloatOrNull(o["hi"]), (o["grp"].str()?.takeIf { it.isNotEmpty() } ?: Defaults.groupOf[id] ?: "").trim(), o["pin"].bool(false))
        }.ifEmpty { Defaults.markers() }
        val markerIds = markers.map { it.id }.toSet()

        val labs = d["labs"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val date = o["date"].date() ?: return@mapNotNull null
            val marker = o["marker"].str()?.takeIf { it in markerIds } ?: return@mapNotNull null
            val value = o["value"].number()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Lab(id, date, marker, value, o["note"].strOr(), jsFloatOrNull(o["lo"]), jsFloatOrNull(o["hi"]))
        }.toMutableList()

        val events = d["events"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str() ?: return@mapNotNull null
            val date = o["date"].date() ?: return@mapNotNull null
            val title = o["title"].str() ?: return@mapNotNull null
            val marker = (o["marker"].str() ?: "").let { if (it in markerIds) it else "" }
            Event(id, date, title, o["note"].strOr(), o["field"].strOr(), marker)
        }

        val days = LinkedHashMap<LocalDate, DayRec>()
        (d["days"] as? JsonObject)?.forEach { (k, v) ->
            val date = JsonPrimitive(k).date() ?: return@forEach
            val r = v.obj()
            val taken = r["taken"].obj().filter { it.value.truthy() }.keys
            val times = r["times"].obj().mapNotNull { (key, tv) -> tv.time()?.let { key to it } }.toMap()
            val skipped = r["skipped"].obj().mapValues { (_, sv) ->
                val s = sv.obj()
                Skipped(s["reason"].strOr("Bez podania powodu").ifEmpty { "Bez podania powodu" }, s["note"].strOr())
            }
            val results = r["results"].obj().mapValues { it.value.strOr() }
            days[date] = DayRec(taken, times, skipped, intOr(r["water"], 0, 0, 100), r["note"].str() ?: "", results)
        }

        val bp = d["bp"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val sys = o["sys"].number() ?: return@mapNotNull null
            val dia = o["dia"].number() ?: return@mapNotNull null
            val date = o["date"].date() ?: return@mapNotNull null
            val time = o["time"].time() ?: return@mapNotNull null
            // wartości spoza rozsądnych granic to błąd pliku (a ogromne liczby psuły wykresy i raporty)
            if (sys !in 30.0..300.0 || dia !in 20.0..200.0) return@mapNotNull null
            val pulse = o["pulse"].number()?.takeIf { it != 0.0 && it in 20.0..300.0 }?.let { Math.round(it).toInt() }
            Bp(o["id"].str()?.takeIf { it.isNotEmpty() } ?: newId(), date, time, Math.round(sys).toInt(), Math.round(dia).toInt(), pulse,
                o["ctx"].strOr("inne").ifEmpty { "inne" }, o["note"].strOr())
        }

        val analyses = d["analyses"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = o["text"].str() ?: return@mapNotNull null
            val date = o["date"].date() ?: return@mapNotNull null
            Analysis(id, date, o["title"].strOr("Analiza"), text, o["excluded"].arr().orEmpty().mapNotNull { it.str() })
        }

        val ecgs = d["ecgs"].arr().orEmpty().mapNotNull { x ->
            val o = x as? JsonObject ?: return@mapNotNull null
            val id = o["id"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val date = o["date"].date() ?: return@mapNotNull null
            val time = o["time"].time() ?: return@mapNotNull null
            val fs = o["fs"].number()?.let { Math.round(it).toInt() }?.takeIf { it in 50..1000 } ?: return@mapNotNull null
            // nowy zapis zwarty (cmvz) albo starszy, jako lista liczb (cmv)
            val vec = o["cmvp"].str(); val packed = o["cmvz"].str()
            // uszkodzony zapis jednego EKG nie może przerwać wczytywania całej kopii
            val raw = runCatching {
                if (vec != null) EcgVector.unpack(vec)
                else if (packed != null) EcgPacking.unpack(packed)
                else o["cmv"].arr().orEmpty().take(200_000).mapNotNull { v -> v.number()?.let { Math.round(it).toInt().coerceIn(-1000, 1000) } }
            }.getOrNull() ?: return@mapNotNull null
            // przebieg w pamięci ma być dokładnie tym, co zapisze się do pliku (patrz EcgVector.normalize)
            val cmv = runCatching { EcgVector.normalize(raw) }.getOrNull() ?: return@mapNotNull null
            if (cmv.size < fs) return@mapNotNull null
            Ecg(id, date, time, o["source"].strOr("zegarek"), intOr(o["watchHr"], 0, 0, 300), o["watchNote"].strOr().take(200), fs, cmv, o["note"].strOr().take(500))
        }

        // Wyniki wpisane przy kontrolach (pole wyniku) trafiają do dziennika badań
        for (e in events) {
            if (e.marker.isEmpty()) continue
            val n = parseNum(days[e.date]?.results?.get(e.id)) ?: continue
            val id = "ev:" + e.id
            if (labs.none { it.id == id }) labs.add(Lab(id, e.date, e.marker, n, e.title))
        }

        return AppState(
            patient = d["patient"].str()?.trim() ?: "",
            created = d["created"].date() ?: LocalDate.now(),
            lastBackup = d["lastBackup"].date(),
            settings = settings,
            rules = d["rules"].str() ?: "",
            slots = slots, meds = meds, events = events, markers = markers, labs = labs, days = days, bp = bp, analyses = analyses, ecgs = ecgs,
            timerEnd = d["timerEnd"].number()?.toLong() ?: 0L,
            timerMed = d["timerMed"].strOr(), timerNote = d["timerNote"].strOr(),
            cykl = Cykl.fromJson(d["cykl"]),
        )
    }

    private fun JsonElement?.bool(def: Boolean): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: def
    private fun jsFloatOrNull(v: JsonElement?): Double? {
        if (v == null || v is JsonNull) return null
        val p = v as? JsonPrimitive ?: return null
        if (p.isString && p.content.isBlank()) return null
        return jsFloat(v)?.takeIf { it.isFinite() }
    }

    // ---------- Zapis ----------

    fun toJson(s: AppState): JsonObject = buildJsonObject {
        put("v", 3)
        put("patient", s.patient)
        put("created", s.created.toString())
        if (s.lastBackup != null) put("lastBackup", s.lastBackup.toString()) else put("lastBackup", JsonNull)
        putJsonObject("settings") {
            val c = s.settings
            putJsonObject("rp") {
                put("bp", c.rp.bp); put("labs", c.rp.labs); put("labsPeriod", c.rp.labsPeriod)
                put("meds", c.rp.meds); put("plan", c.rp.plan); put("events", c.rp.events)
                put("analysis", c.rp.analysis); put("analysisId", c.rp.analysisId); put("ecg", c.rp.ecg); if (c.rp.cykl) put("cykl", true)
                c.rp.ecgIds?.let { ids -> putJsonArray("ecgIds") { ids.forEach { add(JsonPrimitive(it)) } } }
            }
            putJsonObject("ai") {
                put("meds", c.ai.meds); put("history", c.ai.history); put("adherence", c.ai.adherence)
                put("bp", c.ai.bp); put("labs", c.ai.labs); put("events", c.ai.events)
                put("notes", c.ai.notes); put("ecg", c.ai.ecg); if (c.ai.cykl) put("cykl", true); put("name", c.ai.name); put("context", c.ai.context); put("detail", c.ai.detail)
            }
            put("lateMin", c.lateMin); put("waterGoal", c.waterGoal); put("glassMl", c.glassMl)
            put("hiSys", c.hiSys); put("hiDia", c.hiDia); put("loSys", c.loSys); put("loDia", c.loDia)
            put("theme", c.theme.id); put("reportNote", c.reportNote)
            putJsonObject("remind") { put("on", c.remindOn); put("lead", c.remindLead); put("stock", c.stockWarnDays); put("backup", c.backupRemindDays) }
            if (c.cyklOn) put("cyklOn", true)
            if (c.cyklFrom.isNotEmpty()) put("cyklFrom", c.cyklFrom)
            if (!c.bpOn) put("bpOn", false)
            if (!c.labsOn) put("labsOn", false)
        }
        put("rules", s.rules)
        putJsonArray("slots") { s.slots.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name); put("time", it.time.hhmm()) }) } }
        putJsonArray("meds") {
            s.meds.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id); put("name", m.name); put("substance", m.substance); put("note", m.note); put("unit", m.unit)
                    put("timerMin", m.timerMin); put("mmol", numberOrInt(m.mmol))
                    if (m.end != null) put("end", m.end.toString()) else put("end", JsonNull)
                    if (m.stock != null && m.stockDate != null) { put("stock", numberOrInt(m.stock)); put("stockDate", m.stockDate.toString()) }
                    putJsonArray("phases") {
                        m.phases.forEach { p ->
                            add(buildJsonObject {
                                put("from", p.from.toString())
                                putJsonObject("doses") { p.doses.forEach { (k, v) -> put(k, numberOrInt(v)) } }
                                putJsonArray("dow") { p.dow.forEach { add(JsonPrimitive(it)) } }
                            })
                        }
                    }
                })
            }
        }
        putJsonArray("events") {
            s.events.forEach { e ->
                add(buildJsonObject { put("id", e.id); put("date", e.date.toString()); put("title", e.title); put("note", e.note); put("field", e.field); put("marker", e.marker) })
            }
        }
        putJsonArray("markers") {
            s.markers.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id); put("name", m.name); put("unit", m.unit)
                    if (m.lo != null) put("lo", numberOrInt(m.lo)) else put("lo", JsonNull)
                    if (m.hi != null) put("hi", numberOrInt(m.hi)) else put("hi", JsonNull)
                    put("grp", m.grp)
                    if (m.pin) put("pin", true)
                })
            }
        }
        putJsonArray("labs") {
            s.labs.forEach { l -> add(buildJsonObject { put("id", l.id); put("date", l.date.toString()); put("marker", l.marker); put("value", numberOrInt(l.value)); put("note", l.note); if (l.lo != null) put("lo", numberOrInt(l.lo)); if (l.hi != null) put("hi", numberOrInt(l.hi)) }) }
        }
        putJsonObject("days") {
            s.days.toSortedMap().forEach { (date, r) ->
                putJsonObject(date.toString()) {
                    putJsonObject("taken") { r.taken.forEach { put(it, 1) } }
                    putJsonObject("times") { r.times.forEach { (k, v) -> put(k, v.hhmm()) } }
                    putJsonObject("skipped") { r.skipped.forEach { (k, v) -> putJsonObject(k) { put("reason", v.reason); put("note", v.note) } } }
                    put("water", r.water); put("note", r.note)
                    putJsonObject("results") { r.results.forEach { (k, v) -> put(k, v) } }
                }
            }
        }
        putJsonArray("bp") {
            s.bp.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id); put("date", b.date.toString()); put("time", b.time.hhmm()); put("sys", b.sys); put("dia", b.dia)
                    if (b.pulse != null) put("pulse", b.pulse) else put("pulse", JsonNull)
                    put("ctx", b.ctx); put("note", b.note)
                })
            }
        }
        putJsonArray("analyses") {
            s.analyses.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id); put("date", a.date.toString()); put("title", a.title); put("text", a.text)
                    putJsonArray("excluded") { a.excluded.forEach { add(JsonPrimitive(it)) } }
                })
            }
        }
        putJsonArray("ecgs") {
            s.ecgs.forEach { e ->
                add(buildJsonObject {
                    put("id", e.id); put("date", e.date.toString()); put("time", e.time.hhmm()); put("source", e.source)
                    put("watchHr", e.watchHr); put("watchNote", e.watchNote); put("fs", e.fs); put("note", e.note)
                    put("cmvp", EcgVector.pack(e.cmv))
                })
            }
        }
        put("timerEnd", s.timerEnd); put("timerMed", s.timerMed); put("timerNote", s.timerNote)
        // sekcja tylko, gdy moduł ma dane: kopie osób, które go nie używają, wyglądają jak dotąd
        if (!s.cykl.isEmpty) put("cykl", Cykl.toJson(s.cykl))
    }

    private fun numberOrInt(d: Double): JsonPrimitive = if (d % 1.0 == 0.0 && Math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    /** Plik kopii z obwolutą, jak w poprzednich wersjach aplikacji (jedna osoba). */
    fun export(s: AppState, now: Instant = Instant.now()): String = exportAll(listOf(s), now)

    /**
     * Kopia wszystkich osób w aplikacji. `data` to pierwsza osoba (starsze wersje aplikacji wczytają tylko ją, jak dotąd),
     * a pozostałe są w `profiles`. Przy jednej osobie plik jest identyczny z dawnym.
     */
    fun exportAll(states: List<AppState>, now: Instant = Instant.now()): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("app", "asystent-lekow"); put("version", 3); put("exportedAt", now.toString()); put("data", toJson(states.first()))
            if (states.size > 1) putJsonArray("profiles") { states.drop(1).forEach { add(buildJsonObject { put("data", toJson(it)) }) } }
        },
    )

    /** Wszystkie osoby z pliku kopii: pierwsza z `data`, kolejne z `profiles` (uszkodzone wpisy pomijane). */
    fun parseAll(o: JsonElement): List<AppState> {
        val first = parseState(o)
        val more = (o.obj()["profiles"].arr() ?: JsonArray(emptyList())).mapNotNull { x ->
            val d = x.obj()["data"]
            if (d is JsonObject && d["days"] is JsonObject && (d["v"].number() ?: 3.0) == 3.0) normalize(d) else null
        }.take(MAX_PROFILES - 1)
        return listOf(first) + more
    }

    fun parseAll(text: String): List<AppState> = parseAll(parseEnvelope(text))

    const val MAX_PROFILES = 8

    /**
     * Osoby do kopii: [states] to (identyfikator, stan) w kolejności aplikacji, [only] = null oznacza wszystkich, a zbiór bez żadnej
     * znanej osoby jest błędem (kopia nigdy po cichu nie rozszerza się na wszystkich). [ownerOf] zwraca właściciela dziennika cyklu osoby.
     * Powiązanie „osoba korzysta z cudzego dziennika” jest zapisywane jako „#numer” (w wynikowej liście); gdy właściciela nie ma w kopii,
     * dziennik niesie pierwsza wybrana osoba, która go używa, a pozostałe wskazują na nią (dziennik właściciela jest w pliku raz).
     */
    fun selectForExport(states: List<Pair<String, AppState>>, only: Set<String>?, ownerOf: (String) -> String, today: LocalDate): List<AppState> {
        val chosen = states.filter { only == null || it.first in only }
        require(chosen.isNotEmpty()) { "Zakres kopii nie zawiera żadnej osoby." }
        val ids = chosen.map { it.first }
        val carrier = HashMap<String, Int>()
        return chosen.mapIndexed { i, (id, st) ->
            val owner = ownerOf(id)
            var cykl = st.cykl
            var link = ""
            if (owner != id) {
                if (owner in ids) link = "#" + ids.indexOf(owner)
                else {
                    val j = carrier[owner]
                    if (j != null) link = "#$j" else { carrier[owner] = i; cykl = states.first { it.first == owner }.second.cykl }
                }
            }
            st.copy(lastBackup = today, cykl = cykl, settings = st.settings.copy(cyklFrom = link))
        }
    }

    /** Imię dla osoby dopisywanej z pliku: powtórzone (bez względu na wielkość liter) dostaje dopisek „(z pliku)”, a kolejne „(z pliku 3)” itd. */
    fun uniqueName(name: String, taken: Set<String>): String {
        val n = name.trim()
        if (n.isEmpty() || n.lowercase() !in taken) return n
        var k = 1
        var candidate = n
        while (candidate.lowercase() in taken) { k++; candidate = if (k == 2) "$n (z pliku)" else "$n (z pliku $k)" }
        return candidate
    }

    /** Stan zapisywany w pamięci aplikacji (bez obwoluty, zwarty). */
    fun serialize(s: AppState): String = compact.encodeToString(JsonElement.serializer(), toJson(s))

    // ---------- Plik z wynikami badań ----------

    data class LabsFile(val markers: List<Marker>, val labs: List<Lab>)

    fun parseLabsFile(o: JsonElement): LabsFile {
        val root = o.obj()
        val markers = root["markers"].arr().orEmpty().mapNotNull { x ->
            val m = x as? JsonObject ?: return@mapNotNull null
            val id = m["id"].str()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = m["name"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Marker(id, name, m["unit"].strOr(), jsFloatOrNull(m["lo"]), jsFloatOrNull(m["hi"]), (m["grp"].str()?.takeIf { it.isNotEmpty() } ?: Defaults.groupOf[id] ?: "").trim())
        }
        val labs = root["labs"].arr().orEmpty().mapNotNull { x ->
            val l = x as? JsonObject ?: return@mapNotNull null
            val marker = l["marker"].str() ?: return@mapNotNull null
            val date = l["date"].date() ?: return@mapNotNull null
            val value = l["value"].number()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Lab("i" + newId(), date, marker, value, l["note"].strOr())
        }
        return LabsFile(markers, labs)
    }
}


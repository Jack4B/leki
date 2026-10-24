package pl.asystent.leki.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/**
 * Dane modułu „Cykl” (dziennik cyklu). Sam moduł to osobna strona HTML (`cykl/`), która zapisuje bazę jako tekst JSON
 * pod kluczami `luna_db_v6` (baza) i `luna_db_v6_prev` (poprzednia wersja przed „Zastąp wszystko” i „Usuń dane”).
 * Aplikacja trzyma te dwa teksty bez zmian, żeby nie powielać logiki modułu, ale sprawdza, że są poprawną bazą.
 */
data class CyklData(val db: String = "", val prev: String = "") {
    val isEmpty: Boolean get() = db.isEmpty() && prev.isEmpty()

    /** Wartość pod kluczem modułu albo null, gdy jej nie ma (klucz motywu obsługuje aplikacja, nie dane). */
    fun get(key: String): String? = when (key) {
        Cykl.KEY_DB -> db.ifEmpty { null }
        Cykl.KEY_PREV -> prev.ifEmpty { null }
        else -> null
    }

    /** Zapis wartości; null, gdy klucz jest obcy albo wartość nie jest poprawną bazą (wtedy nic się nie zmienia). */
    fun put(key: String, value: String): CyklData? {
        if (value.length > Cykl.MAX_CHARS || !Cykl.isDb(value)) return null
        return when (key) {
            Cykl.KEY_DB -> copy(db = value)
            Cykl.KEY_PREV -> copy(prev = value)
            else -> null
        }
    }

    fun remove(key: String): CyklData? = when (key) {
        Cykl.KEY_DB -> copy(db = "")
        Cykl.KEY_PREV -> copy(prev = "")
        else -> null
    }
}

/** Skrót zawartości bazy do pokazania w Ustawieniach (bez treści wpisów). */
data class CyklSummary(val entries: Int, val periods: Int, val first: String?, val last: String?)

object Cykl {
    const val KEY_DB = "luna_db_v6"
    const val KEY_PREV = "luna_db_v6_prev"
    const val KEY_THEME = "cykl_theme"
    /** Górna granica rozmiaru jednej bazy (znaków). Kilka lat wpisów to setki KB, więc to margines, nie ograniczenie. */
    const val MAX_CHARS = 4_000_000

    private val isoRe = Regex("""^\d{4}-\d\d-\d\d""")

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    /** Czy tekst to baza modułu: obiekt JSON z tablicą `entries` (może być pusta po „Usuń dane”). */
    fun isDb(text: String): Boolean = parse(text)?.get("entries") is JsonArray

    fun summary(text: String): CyklSummary? {
        val entries = parse(text)?.get("entries") as? JsonArray ?: return null
        var periods = 0
        val dates = ArrayList<String>()
        for (e in entries) {
            val o = e as? JsonObject ?: continue
            val date = (o["date"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { isoRe.containsMatchIn(it) }?.take(10)
                ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess } ?: continue
            dates.add(date)
            if ((o["type"] as? JsonPrimitive)?.content == "period_start") periods++
        }
        return CyklSummary(dates.size, periods, dates.minOrNull(), dates.maxOrNull())
    }

    /** Czy plik (np. kopia z poprzedniej aplikacji do cyklu) wygląda jak baza modułu, a nie jak kopia Asystenta leków. */
    fun looksLikeDb(text: String): Boolean {
        val o = parse(text) ?: return false
        return o["entries"] is JsonArray && o["days"] == null && o["app"] == null
    }

    // ---------- Kopia zapasowa: sekcja `cykl` ----------

    internal fun fromJson(e: JsonElement?): CyklData {
        val o = e as? JsonObject ?: return CyklData()
        fun part(k: String): String {
            val v = o[k] as? JsonObject ?: return ""
            if (v["entries"] !is JsonArray) return ""
            val text = Json.encodeToString(JsonElement.serializer(), v)
            return if (text.length <= MAX_CHARS) text else ""
        }
        return CyklData(part("db"), part("prev"))
    }

    internal fun toJson(c: CyklData): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (c.db.isNotEmpty()) parse(c.db)?.let { m["db"] = it }
        if (c.prev.isNotEmpty()) parse(c.prev)?.let { m["prev"] = it }
        return JsonObject(m)
    }

    /** Polityka treści strony modułu: żadnych zasobów spoza strony, tylko jej własny skrypt i style (plus blob/data do zapisu pliku). */
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; connect-src blob: data:"

    /**
     * Składa stronę modułu: polityka treści i most z aplikacją na początku `<head>`, nakładka wyglądu na jego końcu.
     * Kod modułu zostaje bez zmian. Złożenie nie używa `replace` z wzorcem, bo skrypt może zawierać `$`.
     */
    fun page(html: String, css: String, js: String): String {
        val head = html.indexOf("<head>")
        val end = html.indexOf("</head>")
        require(head >= 0 && end > head) { "To nie jest strona modułu Cykl." }
        val afterHead = head + "<head>".length
        return html.substring(0, afterHead) +
            "\n<meta http-equiv=\"Content-Security-Policy\" content=\"$CSP\">\n<script>\n" + js + "\n</script>" +
            html.substring(afterHead, end) +
            "<style>\n" + css + "\n</style>\n" + html.substring(end)
    }

    /** Nazwa pliku z modułu zamieniona na bezpieczną (litery, cyfry, kropka, myślnik, podkreślenie), z sensownym zapasem. */
    fun fileName(raw: String, fallback: String = "cykl.json"): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(80)
        return cleaned.ifEmpty { fallback }
    }

    // ---------- Import z pliku po stronie aplikacji (niezależny od okna WebView) ----------

    /** Wynik wczytania pliku: baza w postaci znormalizowanej oraz liczba pominiętych wpisów (zła data albo brak typu). */
    class Incoming(val db: String, val entries: Int, val skipped: Int)

    private val dmyRe = Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{4})""")

    private fun normDate(raw: String): String? {
        val t = raw.trim()
        val d = if (isoRe.containsMatchIn(t)) t.take(10) else dmyRe.find(t)?.let { m ->
            "%04d-%02d-%02d".format(java.util.Locale.ROOT, m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        } ?: return null
        return d.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
    }

    private fun prim(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    /**
     * Wczytuje plik z dziennikiem tak jak moduł: obiekt z `entries` albo sama tablica wpisów; daty ISO lub dd.mm.rrrr,
     * wpisy ze złą datą lub bez typu są pomijane. Zwraca null, gdy to nie jest dziennik albo nie ma w nim ani jednego poprawnego wpisu.
     */
    fun readIncoming(text: String): Incoming? {
        val root = try { Json.parseToJsonElement(text.removePrefix("\uFEFF").trim()) } catch (e: Exception) { return null } catch (e: StackOverflowError) { return null }
        val list = (root as? JsonArray) ?: ((root as? JsonObject)?.get("entries") as? JsonArray) ?: return null
        val out = ArrayList<JsonElement>()
        var skipped = 0
        for (x in list) {
            val o = x as? JsonObject
            val date = prim(o?.get("date"))?.let { normDate(it) }
            val type = prim(o?.get("type"))?.trim()?.takeIf { it.isNotEmpty() }
            if (o == null || date == null || type == null) { skipped++; continue }
            val m = LinkedHashMap<String, JsonElement>()
            m["type"] = JsonPrimitive(type); m["date"] = JsonPrimitive(date)
            o["id"]?.let { if (it is JsonPrimitive && it !is kotlinx.serialization.json.JsonNull) m["id"] = it }
            o["value"]?.let { if (it is JsonPrimitive && it !is kotlinx.serialization.json.JsonNull) m["value"] = it }
            prim(o["text"])?.let { m["text"] = JsonPrimitive(it) }
            prim(o["result"])?.let { m["result"] = JsonPrimitive(it) }
            if (truthy(o["limits"])) m["limits"] = JsonPrimitive(true)
            out.add(JsonObject(m))
        }
        if (out.isEmpty()) return null
        val obj = root as? JsonObject
        val cycles = obj?.get("cycles") as? JsonObject ?: JsonObject(emptyMap())
        val settings = obj?.get("settings") as? JsonObject ?: JsonObject(emptyMap())
        val db = JsonObject(mapOf("entries" to JsonArray(out), "cycles" to cycles, "settings" to settings))
        val encoded = Json.encodeToString(JsonElement.serializer(), db)
        return if (encoded.length > MAX_CHARS) null else Incoming(encoded, out.size, skipped)
    }

    /** Jak „prawdziwość” w JavaScript: niepusty tekst, liczba różna od zera albo true. */
    private fun truthy(e: JsonElement?): Boolean {
        val p = e as? JsonPrimitive ?: return false
        if (p is kotlinx.serialization.json.JsonNull) return false
        if (p.isString) return p.content.isNotEmpty()
        return p.content == "true" || (p.content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false)
    }

    /** Część klucza wpisu: liczby porównujemy jak w JavaScript (2 i 2.0 to to samo). */
    private fun keyPart(e: JsonElement?): String {
        val p = e as? JsonPrimitive ?: return ""
        if (p is kotlinx.serialization.json.JsonNull) return ""
        if (!p.isString) p.content.toDoubleOrNull()?.let { d -> return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
        return p.content
    }

    private fun entryKey(o: JsonObject): String = listOf("type", "date", "value", "text", "result").joinToString("|") { keyPart(o[it]) }

    /** Wynik łączenia: nowa baza i liczba dodanych wpisów. */
    class Merged(val db: String, val added: Int)

    /** Dodaje do [current] wpisy z [incoming], których tam jeszcze nie ma (ten sam typ, dzień i treść); ustawienia z [current] mają pierwszeństwo, jak w module. */
    fun merge(current: String, incoming: String): Merged? {
        val a = parse(current) ?: JsonObject(mapOf("entries" to JsonArray(emptyList())))
        val b = parse(incoming) ?: return null
        val ae = a["entries"] as? JsonArray ?: JsonArray(emptyList())
        val be = b["entries"] as? JsonArray ?: return null
        val seen = ae.mapNotNull { (it as? JsonObject)?.let(::entryKey) }.toHashSet()
        // tak jak w module: porównanie z wpisami już zapisanymi (powtórzenia wewnątrz samego pliku nie są zwijane)
        val add = be.filter { e -> (e as? JsonObject)?.let { entryKey(it) !in seen } == true }
        fun obj(x: JsonObject, k: String) = x[k] as? JsonObject ?: JsonObject(emptyMap())
        val db = JsonObject(mapOf(
            "entries" to JsonArray(ae + add),
            "cycles" to JsonObject(obj(b, "cycles") + obj(a, "cycles")),
            "settings" to JsonObject(obj(b, "settings") + obj(a, "settings")),
        ))
        return Merged(Json.encodeToString(JsonElement.serializer(), db), add.size)
    }

    private val pretty = Json { prettyPrint = true }

    /** Cała baza do pliku (czytelny zapis, ten sam format co „Pobierz kopię” w module). */
    fun exportText(db: String): String = parse(db)?.let { pretty.encodeToString(JsonElement.serializer(), it) + "\n" } ?: db

    // ---------- Podsumowanie do raportów (opisowe, bez wpisów intymnych i bez rozpoznań) ----------

    /** Długości cykli (dni między kolejnymi początkami okresu), ostatni początek i wpisy zdrowotne wpisane w module. */
    class Brief(
        val cycles: List<Int>, val totalCycles: Int, val lastStart: LocalDate?, val daysSinceStart: Int?,
        val bleedDays: Int?, val longGaps: Int, val conditions: String, val meds: String,
    )

    /** Cykl krótszy niż 15 albo dłuższy niż 90 dni nie jest liczony jako cykl (to zwykle brak wpisu albo przerwa). */
    fun brief(db: String, today: LocalDate): Brief? {
        val root = parse(db) ?: return null
        val entries = (root["entries"] as? JsonArray) ?: return null
        fun dates(type: String): List<LocalDate> = entries.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (prim(o["type"]) != type) return@mapNotNull null
            prim(o["date"])?.takeIf { isoRe.containsMatchIn(it) }?.take(10)?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
        }.distinct().sorted()
        val starts = dates("period_start").filter { !it.isAfter(today) }
        if (starts.isEmpty()) return null
        val ends = dates("period_end")
        val gaps = starts.zipWithNext { a, b -> java.time.temporal.ChronoUnit.DAYS.between(a, b).toInt() }
        val cycles = gaps.filter { it in 15..90 }
        val bleed = starts.mapNotNull { st -> ends.firstOrNull { !it.isBefore(st) && java.time.temporal.ChronoUnit.DAYS.between(st, it) <= 14 }?.let { java.time.temporal.ChronoUnit.DAYS.between(st, it).toInt() + 1 } }.sorted()
        val health = (root["settings"] as? JsonObject)?.get("health") as? JsonObject
        fun h(k: String) = prim(health?.get(k))?.trim()?.take(300) ?: ""
        val last = starts.last()
        return Brief(cycles.takeLast(12), cycles.size, last, java.time.temporal.ChronoUnit.DAYS.between(last, today).toInt(),
            bleed.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }, gaps.count { it > 90 }, h("conditions"), h("meds"))
    }

    /** Zdania do raportu; liczby i daty z dziennika, bez rozpoznań (ocena należy do lekarza). */
    fun briefLines(b: Brief): List<String> {
        val out = ArrayList<String>()
        val last = b.lastStart
        if (last != null) out.add("Ostatni początek okresu: ${plDate(last)}" + (b.daysSinceStart?.let { " ($it ${plN(it, "dzień", "dni", "dni")} temu)" } ?: "") + ".")
        if (b.cycles.size >= 2) {
            val sorted = b.cycles.sorted()
            val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            out.add("Długości ostatnich cykli (dni, od najstarszego): ${b.cycles.joinToString(", ")}. Mediana ${fmtNum(med, 1)}, zakres ${sorted.first()}–${sorted.last()}, rozrzut ${sorted.last() - sorted.first()} dni (z ${b.totalCycles} policzonych cykli).")
        } else out.add("Za mało zapisanych początków okresu, żeby policzyć długości cykli.")
        b.bleedDays?.let { out.add("Typowy czas krwawienia: około $it ${plN(it, "dzień", "dni", "dni")} (z wpisów początku i końca okresu).") }
        if (b.longGaps > 0) out.add("Przerwy dłuższe niż 90 dni między zapisanymi początkami okresu: ${b.longGaps} (mogą oznaczać brak wpisów).")
        if (b.conditions.isNotEmpty()) out.add("Choroby wpisane przez pacjentkę lub pacjenta w dzienniku: ${b.conditions}.")
        if (b.meds.isNotEmpty()) out.add("Leki wpisane w dzienniku: ${b.meds}.")
        out.add("Dane z dziennika są samodzielnymi wpisami; nie zawierają oceny ani rozpoznania.")
        return out
    }
}

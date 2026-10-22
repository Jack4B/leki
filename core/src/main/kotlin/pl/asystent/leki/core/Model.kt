package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalTime

/** Pora dnia, np. Śniadanie 08:00. Kolejność pór wyznacza godzina. */
data class Slot(val id: String, val name: String, val time: LocalTime)

/** Dawkowanie obowiązujące od dnia [from]. [dow]: 0 = niedziela, 1 = poniedziałek … 6 = sobota (jak w pliku kopii). */
data class Phase(val from: LocalDate, val doses: Map<String, Double>, val dow: List<Int>)

data class Med(
    val id: String,
    val name: String,
    val substance: String = "",
    val note: String = "",
    val unit: String = "tabl.",
    val timerMin: Int = 0,
    val mmol: Double = 0.0,
    val end: LocalDate? = null,
    val phases: List<Phase>,
)

data class Event(
    val id: String,
    val date: LocalDate,
    val title: String,
    val note: String = "",
    val field: String = "",
    val marker: String = "",
)

data class Marker(
    val id: String,
    val name: String,
    val unit: String = "",
    val lo: Double? = null,
    val hi: Double? = null,
    val grp: String = "",
)

data class Lab(val id: String, val date: LocalDate, val marker: String, val value: Double, val note: String = "")

data class Skipped(val reason: String, val note: String = "")

data class DayRec(
    val taken: Set<String> = emptySet(),
    val times: Map<String, LocalTime> = emptyMap(),
    val skipped: Map<String, Skipped> = emptyMap(),
    val water: Int = 0,
    val note: String = "",
    val results: Map<String, String> = emptyMap(),
) {
    val isBlank get() = taken.isEmpty() && skipped.isEmpty() && water == 0 && note.isBlank() && results.isEmpty()
}

data class Bp(
    val id: String,
    val date: LocalDate,
    val time: LocalTime,
    val sys: Int,
    val dia: Int,
    val pulse: Int? = null,
    val ctx: String = "inne",
    val note: String = "",
)

data class ReportParts(
    val bp: Boolean = true,
    val labs: Boolean = true,
    val labsPeriod: Boolean = false,
    val meds: Boolean = true,
    val plan: Boolean = true,
    val events: Boolean = true,
    /** Czy dołączyć do raportu wklejoną analizę AI. */
    val analysis: Boolean = true,
    /** Czy dołączyć zapisy EKG z zegarka z wybranego okresu. */
    val ecg: Boolean = true,
    /** Id wybranej analizy; puste = najnowsza. */
    val analysisId: String = "",
) {
    val any get() = bp || labs || meds || plan || events || analysis || ecg
}

/**
 * Analiza (np. wygenerowana przez AI) wklejona przez użytkownika jako uzupełnienie raportu dla lekarza.
 * [excluded]: nagłówki sekcji, które nie wchodzą do raportu (skracanie długiej analizy).
 */
data class Analysis(val id: String, val date: LocalDate, val title: String, val text: String, val excluded: List<String> = emptyList())

data class AiParts(
    val meds: Boolean = true,
    val history: Boolean = true,
    val adherence: Boolean = true,
    val bp: Boolean = true,
    val labs: Boolean = true,
    val events: Boolean = true,
    val notes: Boolean = true,
    /** Czy dołączyć zapisy EKG z zegarka (wskaźniki i odstępy RR, bez surowego przebiegu). */
    val ecg: Boolean = true,
    val name: Boolean = false,
    val context: String = "",
)

enum class ThemeMode(val id: String) { Auto("auto"), Light("light"), Dark("dark") }

data class Settings(
    val rp: ReportParts = ReportParts(),
    val ai: AiParts = AiParts(),
    val lateMin: Int = 60,
    val waterGoal: Int = 10,
    val glassMl: Int = 250,
    val hiSys: Int = 135,
    val hiDia: Int = 85,
    val loSys: Int = 100,
    val loDia: Int = 60,
    val theme: ThemeMode = ThemeMode.Auto,
    val reportNote: String = "",
    val remindOn: Boolean = false,
    val remindLead: Int = 0,
)

data class AppState(
    val patient: String = "",
    val created: LocalDate = LocalDate.now(),
    val lastBackup: LocalDate? = null,
    val settings: Settings = Settings(),
    val rules: String = "",
    val slots: List<Slot> = Defaults.slots(),
    val meds: List<Med> = emptyList(),
    val events: List<Event> = emptyList(),
    val markers: List<Marker> = Defaults.markers(),
    val labs: List<Lab> = emptyList(),
    val days: Map<LocalDate, DayRec> = emptyMap(),
    val bp: List<Bp> = emptyList(),
    val analyses: List<Analysis> = emptyList(),
    val ecgs: List<Ecg> = emptyList(),
    /** Koniec odliczania po dawce, czas w milisekundach od 1970 (0 = brak). */
    val timerEnd: Long = 0,
    val timerMed: String = "",
    val timerNote: String = "",
) {
    fun day(d: LocalDate): DayRec = days[d] ?: DayRec()
    fun med(id: String): Med? = meds.firstOrNull { it.id == id }
    fun slot(id: String): Slot? = slots.firstOrNull { it.id == id }
    fun marker(id: String): Marker? = markers.firstOrNull { it.id == id }
    fun event(id: String): Event? = events.firstOrNull { it.id == id }
    fun sortedSlots(): List<Slot> = slots.sortedBy { it.time }
    fun ecg(id: String): Ecg? = ecgs.firstOrNull { it.id == id }
    fun analysis(id: String): Analysis? = analyses.firstOrNull { it.id == id }
    /** Analiza do raportu: wybrana, a gdy nie ma wyboru, najnowsza. */
    fun reportAnalysis(): Analysis? = analysis(settings.rp.analysisId) ?: analyses.maxByOrNull { it.date }

    inline fun updateDay(d: LocalDate, f: (DayRec) -> DayRec): AppState {
        val n = f(day(d))
        return copy(days = if (n.isBlank) days - d else days + (d to n))
    }
}

object Defaults {
    fun slots() = listOf(
        Slot("morning", "Śniadanie", LocalTime.of(8, 0)),
        Slot("lunch", "Obiad", LocalTime.of(14, 30)),
        Slot("dinner", "Kolacja", LocalTime.of(19, 0)),
        Slot("night", "Przed snem", LocalTime.of(22, 0)),
    )

    fun markers() = listOf(
        Marker("potas", "Potas", "mmol/l", 3.5, 5.5, "Elektrolity"),
        Marker("sod", "Sód", "mmol/l", 136.0, 146.0, "Elektrolity"),
        Marker("magnez", "Magnez", "mmol/l", 0.7, 1.1, "Elektrolity"),
        Marker("kreatynina", "Kreatynina", "mg/dl", 0.67, 1.17, "Nerki i metabolizm"),
        Marker("moczowy", "Kwas moczowy", "mg/dl", 3.5, 7.2, "Nerki i metabolizm"),
    )

    /** Grupy znanych parametrów (także z plików wyników przygotowanych w innych narzędziach). */
    val groupOf: Map<String, String> = mapOf(
        "potas" to "Elektrolity", "sod" to "Elektrolity", "magnez" to "Elektrolity",
        "kreatynina" to "Nerki i metabolizm", "moczowy" to "Nerki i metabolizm", "glukoza" to "Nerki i metabolizm",
        "leukocyty" to "Morfologia", "erytrocyty" to "Morfologia", "hemoglobina" to "Morfologia", "hematokryt" to "Morfologia", "plytki" to "Morfologia",
        "chol" to "Lipidy", "hdl" to "Lipidy", "ldl" to "Lipidy", "tg" to "Lipidy",
        "alt" to "Wątroba i tarczyca", "ast" to "Wątroba i tarczyca", "ggtp" to "Wątroba i tarczyca", "tsh" to "Wątroba i tarczyca",
        "ob" to "Stan zapalny i inne", "crp" to "Stan zapalny i inne", "zelazo" to "Stan zapalny i inne", "psa" to "Stan zapalny i inne",
    )

    val groupOrder = listOf("Elektrolity", "Nerki i metabolizm", "Morfologia", "Lipidy", "Wątroba i tarczyca", "Stan zapalny i inne")

    val allDow = listOf(0, 1, 2, 3, 4, 5, 6)
    /** Kolejność dni w interfejsie: poniedziałek … niedziela. */
    val dowOrder = listOf(1, 2, 3, 4, 5, 6, 0)
}

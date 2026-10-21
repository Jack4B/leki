package pl.asystent.leki.core

import java.time.LocalDate
import java.time.LocalDateTime

/** Dane testowe wygenerowane w poprzedniej wersji aplikacji (JavaScript) dla dnia 21.10.2026, 14:00 (czas Warszawy). */
object Fixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 21)
    val now: LocalDateTime = LocalDateTime.of(2026, 10, 21, 14, 0)

    fun text(name: String): String =
        (Fixtures::class.java.getResourceAsStream("/fixtures/$name") ?: error("brak $name")).readBytes().toString(Charsets.UTF_8)

    val state: AppState by lazy { Backup.parseState(text("state.json")) }

    fun period(preset: Int): Period = Period.resolve(preset, null, today, state.firstDate(today))
}

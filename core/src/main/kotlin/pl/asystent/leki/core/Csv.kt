package pl.asystent.leki.core

import java.time.LocalDate

/**
 * Eksport do arkusza (Excel, LibreOffice, Arkusze Google): średnik jako separator i przecinek dziesiętny (układ polskich ustawień regionalnych),
 * UTF-8 z BOM (żeby polskie znaki się nie psuły), znaki końca wiersza CRLF. Tekst zaczynający się od =, +, - lub @ dostaje apostrof,
 * żeby arkusz nie wykonał go jako formuły.
 */
object Csv {
    private const val BOM = "﻿"

    fun field(s: String): String {
        var t = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        if (t.isNotEmpty() && t[0] in "=+-@\t") t = "'$t"
        return if (t.any { it == ';' || it == '"' }) "\"" + t.replace("\"", "\"\"") + "\"" else t
    }

    private fun build(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder(BOM)
        sb.append(header.joinToString(";") { field(it) }).append("\r\n")
        rows.forEach { r -> sb.append(r.joinToString(";") { field(it) }).append("\r\n") }
        return sb.toString()
    }

    private fun inRange(d: LocalDate, from: LocalDate?, to: LocalDate?) = (from == null || d >= from) && (to == null || d <= to)

    fun bp(s: AppState, from: LocalDate? = null, to: LocalDate? = null): String = build(
        listOf("Data", "Godzina", "Skurczowe", "Rozkurczowe", "Tętno", "Okoliczności", "Notatka"),
        s.bp.filter { inRange(it.date, from, to) }.sortedWith(compareBy<Bp> { it.date }.thenBy { it.time })
            .map { listOf(it.date.toString(), it.time.hhmm(), it.sys.toString(), it.dia.toString(), it.pulse?.toString() ?: "", it.ctx, it.note) },
    )

    fun labs(s: AppState, from: LocalDate? = null, to: LocalDate? = null): String = build(
        listOf("Data", "Parametr", "Wartość", "Jednostka", "Norma od", "Norma do", "Notatka"),
        s.labs.filter { inRange(it.date, from, to) }.sortedWith(compareBy<Lab> { it.date }.thenBy { it.marker }).map { l ->
            val m = s.marker(l.marker)
            // zakres z danego badania, a gdy go nie ma, zakres parametru
            val lo = if (l.lo != null || l.hi != null) l.lo else m?.lo
            val hi = if (l.lo != null || l.hi != null) l.hi else m?.hi
            listOf(l.date.toString(), m?.name ?: l.marker, lnum(l.value), m?.unit ?: "", lo?.let { lnum(it) } ?: "", hi?.let { lnum(it) } ?: "", l.note)
        },
    )

    /** Dziennik dawek: każda zaplanowana dawka w dniach od [from] do [to] ze stanem (przyjęto, pominięto, brak odhaczenia) i godziną. */
    fun doses(s: AppState, from: LocalDate, to: LocalDate, today: LocalDate): String = build(
        listOf("Data", "Pora", "Lek", "Dawka", "Jednostka", "Stan", "Godzina przyjęcia", "Powód pominięcia"),
        dayList(from, minOf(to, today)).flatMap { d ->
            val r = s.day(d)
            s.scheduleFor(d).map { x ->
                val state = when { x.key in r.taken -> "przyjęto"; x.key in r.skipped -> "pominięto"; else -> "brak odhaczenia" }
                listOf(d.toString(), x.slot.name, x.med.name, num(x.dose), x.med.unit, state, r.times[x.key]?.hhmm() ?: "", r.skipped[x.key]?.reason ?: "")
            }
        },
    )

    /** Podsumowanie zapisów EKG (bez surowego przebiegu). */
    fun ecg(s: AppState, from: LocalDate? = null, to: LocalDate? = null): String = build(
        listOf("Data", "Godzina", "Czas trwania [s]", "Tętno wg zegarka", "Tętno z przebiegu (mediana)", "Ocena zegarka", "Oznaczenia aplikacji", "Notatka"),
        s.ecgs.filter { inRange(it.date, from, to) }.sortedWith(compareBy<Ecg> { it.date }.thenBy { it.time }).map { e ->
            val r = EcgAnalysis.analyze(e)
            listOf(
                e.date.toString(), e.time.hhmm(), fmtNum(e.durationS, 0), if (e.watchHr > 0) e.watchHr.toString() else "", r.hrMedian?.let { Math.round(it).toString() } ?: "",
                e.watchNote, r.flags.filter { it.code != "none" }.joinToString(" | ") { it.title }, e.note,
            )
        },
    )
}

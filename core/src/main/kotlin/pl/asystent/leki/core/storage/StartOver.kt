package pl.asystent.leki.core.storage

/**
 * „Zaczynam od nowa” po utracie klucza (plan, rozdział 1): zawsze nowy `dekId` (n+1). Kolejność odporna na zabicie procesu:
 * (1) RENAME zmiana nazw `state*.enc` na `*.nieczytelny.enc`, (2) KEYS zapis i weryfikacja `keys.bin`, (3) MARKER zapis znacznika
 * z nowym `dekId`, (4) KEYS2 zapis i weryfikacja `keys.bin.2`. Postęp jest zapisywany atomowo w pliku [PENDING_FILE]; jego obecność oznacza
 * operację w toku. **Po zabiciu procesu po kroku (1) zapisy są zablokowane do ukończenia kroków (2) i (3)**, nawet po restarcie.
 */
object StartOver {
    const val PENDING_FILE = "startover.pending"
    enum class Step { RENAME, KEYS, MARKER, KEYS2 }

    fun encode(done: Set<Step>): String = done.sortedBy { it.ordinal }.joinToString(",") { it.name }
    fun decode(text: String): Set<Step>? {
        if (text.isBlank()) return emptySet()
        val steps = text.trim().split(',').map { n -> Step.values().firstOrNull { it.name == n } ?: return null }
        return steps.toSet().takeIf { d -> steps.size == d.size && d.all { s -> Step.values().take(s.ordinal).all { it in d } } }
    }

    /**
     * Zapisy danych są zablokowane, **gdy istnieje plik [PENDING_FILE]**, niezależnie od jego treści: także pusty (proces zabity w trakcie
     * kroku 1, zanim zapisano postęp) i nieczytelny. [pendingText] = treść pliku albo null, gdy go nie ma. Blokadę zdejmuje dopiero usunięcie
     * pliku po ukończeniu wszystkich kroków (pierwszy plan dokańcza operację do przodu).
     */
    fun writesBlocked(pendingText: String?): Boolean = pendingText != null

    /** Pozostałe kroki dla pliku postępu; nieczytelny postęp (null) = zaczynamy od kroku (1) jeszcze raz (zmiana nazw jest powtarzalna). */
    fun remainingFor(pendingText: String): List<Step> = remaining(decode(pendingText) ?: emptySet())

    /** Pozostałe kroki do wykonania (dokończenie do przodu na pierwszym planie po restarcie). */
    fun remaining(done: Set<Step>): List<Step> = Step.values().filter { it !in done }
}

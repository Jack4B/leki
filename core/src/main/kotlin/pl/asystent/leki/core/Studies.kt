package pl.asystent.leki.core

import java.time.LocalDate
import java.util.Base64

/** Rodzaj badania opisowego lub obrazowego (Badania → Opisowe). */
enum class StudyKind(val id: String, val label: String) {
    Usg("usg", "USG"), Tk("tk", "Tomografia (TK)"), Mri("mri", "Rezonans (MRI)"), Rtg("rtg", "RTG"),
    Endo("endo", "Endoskopia"), Echo("echo", "Echo serca"), Other("inne", "Inne");

    companion object { fun of(id: String?): StudyKind = values().firstOrNull { it.id == id } ?: Other }
}

/** Zdjęcie dołączone do badania: JPEG zmniejszony na telefonie, zapisany jako base64 (limity w [Studies]). */
data class StudyImage(val id: String, val data: String, val caption: String = "")

/**
 * Badanie opisowe (tekst z wyniku: opis i wnioski) i/lub obrazowe (zdjęcia z USG, TK, rezonansu). Opis i wnioski wpisuje albo wczytuje ze zdjęcia lub PDF osoba;
 * aplikacja niczego nie ocenia ani nie interpretuje obrazów.
 */
data class Study(
    val id: String,
    val date: LocalDate,
    val kind: StudyKind = StudyKind.Other,
    val title: String,
    val place: String = "",
    val description: String = "",
    val conclusions: String = "",
    val images: List<StudyImage> = emptyList(),
)

object Studies {
    const val MAX_TITLE = 120
    const val MAX_PLACE = 120
    const val MAX_CAPTION = 120
    const val MAX_DESCRIPTION = 20_000
    const val MAX_CONCLUSIONS = 4_000
    const val MAX_IMAGES = 8
    /** Jedno zdjęcie po zmniejszeniu: do ok. 260 KB JPEG (jako base64 ok. 350 tys. znaków). */
    const val MAX_IMAGE_CHARS = 350_000
    /** Wszystkie zdjęcia jednej osoby razem (ok. 4,5 MB JPEG), żeby plik danych osoby nie puchł. */
    const val MAX_TOTAL_CHARS = 6_000_000
    /**
     * Zdjęcia wszystkich osób razem (ok. 7,5 MB JPEG). Kopia całości trafia do jednego pliku, a import przyjmuje najwyżej [Backup.MAX_IMPORT_BYTES] (20 MB);
     * kopia zaszyfrowana koduje szyfrogram w base64 (+1/3), więc 10 mln znaków zdjęć i reszta danych mieszczą się z zapasem.
     */
    const val MAX_ALL_CHARS = 10_000_000
    /** Z jednego badania do raportu dla AI trafia najwyżej tyle znaków opisu. */
    const val AI_DESCRIPTION_CHARS = 3_000

    /** Czy tekst to poprawny JPEG w base64 (dekoduje się i zaczyna od znacznika SOI 0xFFD8, czyli „/9j/”) i mieści się w limicie jednego zdjęcia. */
    fun isJpegBase64(data: String): Boolean =
        data.length in 8..MAX_IMAGE_CHARS && data.startsWith("/9j/") && runCatching { Base64.getDecoder().decode(data) }.isSuccess

    /** Obcina pola do limitów i odrzuca niepoprawne zdjęcia (zapis i wczytywanie kopii przechodzą przez to samo sito). */
    fun sanitize(s: Study): Study = s.copy(
        title = s.title.trim().take(MAX_TITLE).ifEmpty { s.kind.label },
        place = s.place.trim().take(MAX_PLACE),
        description = s.description.trim().take(MAX_DESCRIPTION),
        conclusions = s.conclusions.trim().take(MAX_CONCLUSIONS),
        images = s.images.filter { isJpegBase64(it.data) }.take(MAX_IMAGES).map { it.copy(caption = it.caption.trim().take(MAX_CAPTION)) },
    )

    /** Łączna liczba znaków wszystkich zdjęć (miara zajętości; patrz [MAX_TOTAL_CHARS]). */
    fun imageChars(studies: List<Study>): Int = studies.sumOf { st -> st.images.sumOf { it.data.length } }

    /** Zostawia zdjęcia do limitu łącznego: badania po kolei, zdjęcia ponad limit są pomijane. */
    fun capTotal(studies: List<Study>): List<Study> {
        var left = MAX_TOTAL_CHARS
        return studies.map { st -> st.copy(images = st.images.filter { im -> (im.data.length <= left).also { ok -> if (ok) left -= im.data.length } }) }
    }

    /**
     * Powód, dla którego zdjęcia nie mogą urosnąć o [delta] znaków (null = mogą): limit osoby ([MAX_TOTAL_CHARS]) i wszystkich osób razem ([MAX_ALL_CHARS], [otherPeople]
     * to zdjęcia pozostałych osób). Wspólne sprawdzenie dla dodania zdjęcia, ponownej korekty zdjęcia i cofnięcia usunięcia badania, żeby nic nie było obcinane po cichu przy wczytaniu.
     */
    fun cannotGrow(all: List<Study>, delta: Int, otherPeople: Int = 0): String? {
        if (delta <= 0) return null
        val mine = imageChars(all)
        fun mb(c: Int) = c * 3 / 4 / 1024 / 1024
        return when {
            mine + delta > MAX_TOTAL_CHARS -> "Zajęte jest ${mb(mine)} z ${mb(MAX_TOTAL_CHARS)} MB na zdjęcia badań tej osoby. Usuń niepotrzebne zdjęcie, żeby dodać nowe."
            mine + otherPeople + delta > MAX_ALL_CHARS -> "Zdjęcia badań wszystkich osób zajmują ${mb(mine + otherPeople)} z ${mb(MAX_ALL_CHARS)} MB (tyle mieści kopia zapasowa, którą da się wczytać). Usuń niepotrzebne zdjęcie, żeby dodać nowe."
            else -> null
        }
    }

    /** Powód, dla którego nie można dodać zdjęcia o [dataChars] znakach do badania [study] (null = można). */
    fun cannotAddImage(all: List<Study>, study: Study?, dataChars: Int, otherPeople: Int = 0): String? = when {
        study != null && study.images.size >= MAX_IMAGES -> "W jednym badaniu może być najwyżej $MAX_IMAGES zdjęć."
        dataChars > MAX_IMAGE_CHARS -> "Zdjęcie jest za duże nawet po zmniejszeniu."
        else -> cannotGrow(all, dataChars, otherPeople)
    }

    private val PATIENT_LABEL = Regex("""(?i)^\s*(pacjent(ka)?|imię i nazwisko|nazwisko|imię)\s*:""")

    /**
     * Usuwa z tekstu dla AI dane osoby, gdy imię nie ma trafić do tekstu: wiersze z etykietą („Pacjent:”, „Imię i nazwisko:”) oraz każde wystąpienie części imienia
     * i nazwiska z [patient] (od 3 liter, bez względu na wielkość liter), także w innym przypadku („Jana Testowego”, „Kowalskiej”), zastępowane przez „[…]”.
     * Odmianę przybliża temat (część bez ostatniej litery, gdy ma co najmniej 5 liter albo 4 i kończy się samogłoską) z najwyżej 4 literami końcówki; lepiej zasłonić o słowo za dużo niż zostawić nazwisko.
     */
    fun stripPatient(text: String, patient: String): String {
        val kept = text.lines().filterNot { PATIENT_LABEL.containsMatchIn(it) }.joinToString("\n")
        val stems = patient.split(Regex("""[\s\-]+""")).map { it.trim().trim('.', ',') }.filter { it.length >= 3 }
            .map { if (it.length >= 5 || it.length == 4 && it.last().lowercaseChar() in "aeiouyąę") it.dropLast(1) else it }.distinct()
        if (stems.isEmpty()) return kept
        val re = Regex("(?i)(?<!\\p{L})(" + stems.joinToString("|") { Regex.escape(it) } + ")\\p{L}{0,4}(?!\\p{L})")
        return kept.replace(re, "[…]")
    }

    /**
     * Czy wybrany plik to PDF: nagłówek „%PDF-” rozstrzyga (niezależnie od typu i nazwy, bo selektory plików często nie podają typu), potem typ obrazu oznacza zdjęcie,
     * a dopiero potem typ `application/pdf` albo nazwa kończąca się na „.pdf”. [head]: pierwsze bajty pliku (może być null, gdy nie dało się ich odczytać).
     */
    fun looksLikePdf(mime: String?, name: String?, head: ByteArray?): Boolean = when {
        head != null && head.size >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> true
        mime?.startsWith("image/", ignoreCase = true) == true -> false
        else -> mime.equals("application/pdf", ignoreCase = true) || name?.trim()?.endsWith(".pdf", ignoreCase = true) == true
    }

    /** Dopisek do podglądu wczytywanej kopii (liczba badań opisowych i obrazowych oraz zdjęć); pusty, gdy badań nie ma, żeby kopia z samymi badaniami nie wyglądała jak pusta. */
    fun importNote(studies: List<Study>): String {
        if (studies.isEmpty()) return ""
        val images = studies.sumOf { it.images.size }
        return ", badania opisowe i obrazowe: ${studies.size}" + if (images > 0) ", $images ${plN(images, "zdjęcie", "zdjęcia", "zdjęć")}" else ""
    }

    private val PESEL_LINE = Regex("""(?i)pesel|(?<!\d)\d{11}(?!\d)""")

    /** Czy wiersz zawiera numer PESEL (słowo „PESEL” lub 11 cyfr z rzędu). */
    fun hasIdentifier(line: String): Boolean = PESEL_LINE.containsMatchIn(line)

    /**
     * Usuwa z tekstu wczytanego z wyniku wiersze z numerem PESEL (słowo „PESEL” lub 11 cyfr z rzędu); w takim wierszu zwykle jest też imię i nazwisko. Numer PESEL i imię nie
     * są potrzebne w aplikacji ani w raporcie dla AI. To zabezpieczenie dodatkowe: przed zapisem osoba i tak sprawdza tekst.
     */
    fun stripIdentifiers(text: String): String = text.lines().filterNot { PESEL_LINE.containsMatchIn(it) }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /** Tekst jednego badania do raportu dla AI: opis oczyszczony z numerów PESEL i obcięty. */
    fun aiDescription(s: Study): String = stripIdentifiers(s.description).let { if (it.length > AI_DESCRIPTION_CHARS) it.take(AI_DESCRIPTION_CHARS).trimEnd() + " …" else it }
}

/**
 * Wybór badań opisowych i obrazowych do raportów. [ReportParts.studyIds] równe null oznacza tryb automatyczny (najnowsze badania z okresu raportu, najwyżej [AUTO_MAX]);
 * lista (także pusta) oznacza wybór ręczny: tylko zaznaczone badania, **niezależnie od daty** (takie badania robi się rzadko i do raportu zwykle trafiają starsze).
 */
object StudySelection {
    const val AUTO_MAX = 5

    /** [chosen]: badania do raportu, od najnowszego; [inPeriod]: ile badań jest w okresie. */
    class Pick(val chosen: List<Study>, val inPeriod: Int)

    fun all(s: AppState): List<Study> = s.studies.sortedWith(compareByDescending<Study> { it.date }.thenBy { it.id })

    fun forReport(s: AppState, per: Period, ids: List<String>?): Pick {
        val all = all(s)
        val inPeriod = all.filter { it.date >= per.from && it.date <= per.to }
        return Pick(if (ids == null) inPeriod.take(AUTO_MAX) else all.filter { it.id in ids }, inPeriod.size)
    }

    /** Lista wyborów po dotknięciu badania [id]: z trybu automatycznego przechodzi się do ręcznego, zaczynając od badań, które trafiłyby do raportu. */
    fun toggled(s: AppState, per: Period, ids: List<String>?, id: String): List<String> {
        val base = ids ?: forReport(s, per, null).chosen.map { it.id }
        return if (id in base) base - id else base + id
    }
}

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
    /** Wszystkie zdjęcia jednej osoby razem (ok. 4,5 MB JPEG), żeby plik danych i kopia (limit importu 20 MB) nie puchły. */
    const val MAX_TOTAL_CHARS = 6_000_000
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

    /** Powód, dla którego nie można dodać zdjęcia o [dataChars] znakach do badania [study] (null = można). */
    fun cannotAddImage(all: List<Study>, study: Study?, dataChars: Int): String? = when {
        study != null && study.images.size >= MAX_IMAGES -> "W jednym badaniu może być najwyżej $MAX_IMAGES zdjęć."
        dataChars > MAX_IMAGE_CHARS -> "Zdjęcie jest za duże nawet po zmniejszeniu."
        imageChars(all) + dataChars > MAX_TOTAL_CHARS -> "Zajęte jest ${imageChars(all) * 3 / 4 / 1024 / 1024} z ${MAX_TOTAL_CHARS * 3 / 4 / 1024 / 1024} MB na zdjęcia badań. Usuń niepotrzebne zdjęcie, żeby dodać nowe."
        else -> null
    }

    private val PESEL_LINE = Regex("""(?i)pesel|(?<!\d)\d{11}(?!\d)""")

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

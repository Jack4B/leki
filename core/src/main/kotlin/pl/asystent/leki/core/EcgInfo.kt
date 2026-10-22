package pl.asystent.leki.core

/** Opis obliczeń wykonywanych na zapisie EKG: pokazywany w aplikacji, w raporcie dla lekarza i w raporcie dla AI. Musi zgadzać się z kodem w [EcgAnalysis] i [EcgDigitizer]. */
object EcgInfo {
    class Step(val title: String, val text: String)

    val STEPS = listOf(
        Step("Odtworzenie przebiegu z PDF",
            "Strona PDF jest renderowana do obrazu (200 dpi). Piksele czerwonej linii (R ≥ 170, G i B ≤ 130) są zamieniane na przebieg: każdy wiersz obrazu to chwila czasu, a odległość linii od jej mediany to napięcie. Skala z wydruku: 25 mm/s i 10 mm/mV (około 197 próbek na sekundę). Zapis jest następnie przeliczany do 125 próbek na sekundę z dokładnością do 0,01 mV."),
        Step("Usunięcie dryfu linii podstawowej",
            "Od sygnału odejmowana jest ruchoma mediana z okna 0,6 s, co usuwa powolne falowanie linii (oddech, ruch)."),
        Step("Wykrywanie uderzeń (zespół QRS)",
            "Metoda oparta na energii pochodnej, w duchu algorytmu Pana i Tompkinsa (1985), ale uproszczona: kwadrat różnicy kolejnych próbek, wygładzony oknem 60 ms. Próg to 40% mediany maksimów z okien 1,5 s (mediana jest odporna na pojedyncze artefakty), odstęp refrakcji wynosi 250 ms, a punkt R to maksimum w okolicy ±70 ms. Kierunek QRS (dodatni lub ujemny) wybierany jest według większości uderzeń."),
        Step("Zakłócenia",
            "Pomijany jest fragment ±0,35 s wokół: wychyleń większych niż 2,5 typowej amplitudy R, wychyleń przeciwnych do QRS o amplitudzie co najmniej 0,9 amplitudy R oraz skoków ponad 4 razy bardziej stromych niż typowy QRS. Odstępy RR, które przechodzą przez takie miejsce, nie są liczone."),
        Step("Wskaźniki",
            "Tętno to 60 podzielone przez odstęp RR w sekundach (mediana i średnia, a zakres z najdłuższego i najkrótszego odstępu). SDNN to odchylenie standardowe odstępów RR, RMSSD to pierwiastek ze średniej kwadratów różnic kolejnych odstępów, pNN50 to odsetek różnic kolejnych odstępów większych niż 50 ms. Do oceny potrzeba co najmniej 8 uderzeń i 6 wiarygodnych odstępów."),
        Step("Reguły oznaczeń",
            "Wolne tętno: mediana poniżej 50/min. Szybkie: powyżej 100/min. Rytm wyraźnie nieregularny: co najmniej 35% odstępów odbiega o ponad 15% od mediany i współczynnik zmienności przekracza 0,1. Przedwczesne uderzenie: odstęp krótszy niż 80% mediany, po którym następuje odstęp dłuższy niż 110% mediany. Przerwa: odstęp dłuższy niż 1,6 mediany i dłuższy niż 1,8 s."),
        Step("Czego to nie robi",
            "Nie mierzy odstępu QT ani odcinka ST, nie ocenia kształtu zespołów, nie rozpoznaje migotania przedsionków (wskazuje tylko nieregularność odstępów), bloków, niedokrwienia ani zawału. To zwykła heurystyka, a nie zatwierdzony algorytm medyczny; nie była walidowana klinicznie (sprawdzono ją na jednym wydruku z zegarka i na sygnałach syntetycznych)."),
    )

    /** Krótka wersja do raportów. */
    const val SHORT = "przebieg odtworzony z obrazu wydruku (25 mm/s, 10 mm/mV), uderzenia wykryte metodą energii pochodnej zbliżoną do algorytmu Pana i Tompkinsa, odstępy RR, tętno, SDNN i RMSSD; zakłócenia pomijane. Bez oceny QT, ST i kształtu zespołów. Heurystyka bez walidacji klinicznej, nie diagnoza."
}

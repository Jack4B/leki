import type { AgeBand, History, Mode, Settings } from "./types.ts";
import { addDays, diffDays, mean, median, stdev, theilSen } from "./dates.ts";
import type { Entry } from "./types.ts";

/** Wiek, od którego tekst o fazie przejściowej (STRAW+10) ma sens: wcześniej zmienność cykli ma zwykle inne przyczyny. */
export const isPerimenopausal = (band: AgeBand): boolean => band === "42-45" || band === "46-50" || band === "51+";

/**
 * Próg regularności wg FIGO 2018: wahanie najkrótszy–najdłuższy cykl (26–41 lat: 7 dni, 18–25 i 42–45: 9 dni).
 * Po 45. roku życia FIGO nie podaje progu: duża zmienność cykli jest wtedy częsta (okołomenopauza), więc nie jest oceniana (null).
 */
export const regularityLimit = (band: AgeBand): number | null => (band === "46-50" || band === "51+" ? null : band === "18-25" || band === "42-45" ? 9 : 7);

export interface Trend {
  n: number; lengths: number[]; slope: number; intercept: number; fitted: number[];
  direction: "stable" | "longer" | "shorter" | "unknown"; recent3: number | null; prev3: number | null; sd: number;
}
/** Statystyka testu Manna–Kendalla (|z|): czy kierunek zmian jest czymś więcej niż szumem. */
const mannKendallZ = (y: number[]): number => {
  const n = y.length;
  let s = 0;
  for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) s += Math.sign(y[j]! - y[i]!);
  return Math.abs(s) / Math.sqrt((n * (n - 1) * (2 * n + 5)) / 18);
};
/**
 * Trend długości cykli: nachylenie Theil–Sen (dni na cykl) + porównanie 3 ostatnich z 3 poprzednimi.
 * Kierunek (wydłużanie/skracanie) pokazujemy dopiero od 6 cykli, przy istotnym teście Manna–Kendalla (|z| ≥ 1,645)
 * i łącznej zmianie ≥3 dni; przy mniejszych danych zwykły rozrzut wyglądałby jak trend.
 */
export const trendOf = (lengths: number[]): Trend => {
  const n = lengths.length;
  if (n < 4) return { n, lengths, slope: 0, intercept: lengths[0] ?? 0, fitted: lengths, direction: "unknown", recent3: null, prev3: null, sd: n ? stdev(lengths) : 0 };
  const { slope, intercept } = theilSen(lengths);
  const fitted = lengths.map((_, i) => intercept + slope * i);
  const recent3 = mean(lengths.slice(-3));
  const prev3 = n >= 6 ? mean(lengths.slice(-6, -3)) : null;
  const real = n >= 6 && mannKendallZ(lengths) >= 1.645 && Math.abs(slope) * (n - 1) >= 3;
  const direction = n < 6 ? "unknown" : !real ? "stable" : slope > 0 ? "longer" : "shorter";
  return { n, lengths, slope, intercept, fitted, direction, recent3, prev3, sd: stdev(lengths) };
};

export interface Straw {
  bigJumps: { from: string; days: number }[];
  stage: "none" | "possible-early" | "early-hint" | "late-hint" | "menopause-hint" | "off";
  text: string;
}
/**
 * Wskazówka wg STRAW+10 (Harlow i wsp. 2012), nie rozpoznanie:
 * wczesne przejście = powtarzająca się (w ciągu 10 cykli) różnica ≥7 dni między kolejnymi cyklami;
 * późne przejście = przerwa ≥60 dni; menopauza = brak miesiączki ≥12 miesięcy (rozpoznaje się wstecz).
 * Kryteria dotyczą osób w wieku okołomenopauzalnym, które nie stosują hormonów i nie są w ciąży. U młodszych tekst jest neutralny.
 * Jeden wyjątkowo długi cykl daje dwa sąsiednie skoki, więc sąsiednie skoki liczą się jako jedno zdarzenie.
 */
export const strawHint = (hist: History, today: string, band: AgeBand = "26-41", mode: Mode = "normal"): Straw => {
  const last10 = hist.cycles.slice(-10);
  const bigJumps: Straw["bigJumps"] = [];
  for (let i = 1; i < last10.length; i++) {
    const d = Math.abs(last10[i]!.days - last10[i - 1]!.days);
    if (d >= 7) { bigJumps.push({ from: last10[i]!.start, days: d }); i++; }
  }
  if (mode !== "normal") return { bigJumps: [], stage: "off", text: "Wskazówka wyłączona: antykoncepcja hormonalna, ciąża, połóg i karmienie zmieniają przebieg cykli (ustawienie w zakładce Dane)." };
  const peri = isPerimenopausal(band);
  const openDays = hist.lastStart ? diffDays(hist.lastStart, today) + 1 : 0;
  if (openDays > 365) {
    return { bigJumps, stage: "menopause-hint", text: peri
      ? "Brak miesiączki od ponad 12 miesięcy: to kryterium menopauzy wg STRAW+10 (rozpoznaje lekarz). Każde krwawienie po takiej przerwie wymaga oceny lekarza."
      : "Brak miesiączki od ponad 12 miesięcy: do oceny lekarza (najpierw trzeba wykluczyć ciążę, zaburzenia hormonalne i wpływ leków)." };
  }
  const gap60 = last10.some((c) => c.days >= 60) || openDays >= 60;
  if (gap60) {
    return { bigJumps, stage: "late-hint", text: peri
      ? "Przerwa ≥60 dni między miesiączkami: kryterium późnej fazy przejściowej wg STRAW+10. Najpierw trzeba wykluczyć ciążę. Do oceny lekarza."
      : "Przerwa ≥60 dni między miesiączkami: do oceny lekarza (najpierw trzeba wykluczyć ciążę; częste przyczyny to też tarczyca, PCOS, stres, zmiana masy ciała i leki)." };
  }
  if (bigJumps.length >= 2) {
    return { bigJumps, stage: "early-hint", text: peri
      ? `Różnica ≥7 dni między kolejnymi cyklami wystąpiła ${bigJumps.length} razy w ostatnich 10 cyklach: kryterium wczesnej fazy przejściowej wg STRAW+10${band === "42-45" ? "" : " (w tym wieku to częsty obraz okołomenopauzy)"}. Podobny obraz mogą dawać też choroby tarczycy. Do oceny lekarza.`
      : `Duża zmienność długości cykli (różnica ≥7 dni ${bigJumps.length} razy w ostatnich 10 cyklach). U młodszych osób częste przyczyny to m.in. PCOS, tarczyca, stres, zmiana masy ciała i leki. Do omówienia z lekarzem.` };
  }
  if (bigJumps.length === 1) return { bigJumps, stage: "possible-early", text: "Jedna różnica ≥7 dni między kolejnymi cyklami. To jeszcze nie wzór, obserwuj dalej." };
  return { bigJumps, stage: "none", text: peri && band !== "42-45" ? "Brak powtarzających się skoków ≥7 dni między kolejnymi cyklami. W tym wieku cykle często stają się mniej regularne: warto dalej zapisywać." : "Brak powtarzających się skoków ≥7 dni między kolejnymi cyklami." };
};

export interface Figo {
  avg: number; mn: number; mx: number; variation: number; lim: number | null; band: AgeBand;
  /** Dość cykli (≥6), żeby oceniać regularność; FIGO opisuje ją w ciągu 12 miesięcy. */
  enoughReg: boolean; regOk: boolean; freq: string; freqOk: boolean;
  bMin: number | null; bMax: number | null; durOk: boolean; imb: string[]; hmb: boolean;
  clinic: string[]; urgent: boolean; urgentWhy: string; nCycles: number; anemia: boolean; flowLogged: boolean;
}
/** Dzień „w pobliżu” miesiączki (±2 dni): plamienie tuż przed startem albo po końcu to zwykle jej początek lub koniec. */
const nearBleed = (hist: History, date: string): boolean => [-2, -1, 0, 1, 2].some((k) => hist.bleed.has(addDays(date, k)));

export const figoSystem1 = (hist: History, entries: Entry[], settings: Settings, today: string, overdueDays: number): Figo => {
  const since = addDays(today, -365);
  const mode = settings.mode === "hormonal" || settings.mode === "pause" ? settings.mode : "normal";
  const c12 = hist.cycles.filter((c) => c.end >= since);
  const cyc = c12.length ? c12 : hist.cycles;
  const band = settings.ageBand ?? "26-41";
  const lim = regularityLimit(band);
  const lens = cyc.map((c) => c.days);
  const avg = lens.length ? Math.round(mean(lens)) : settings.averageCycleLength ?? 29;
  const mn = lens.length ? Math.min(...lens) : avg;
  const mx = lens.length ? Math.max(...lens) : avg;
  const variation = mx - mn;
  const enoughReg = cyc.length >= 6 && lim != null;
  const regOk = enoughReg && variation <= (lim ?? 99);
  const missing90 = Boolean(hist.lastStart && diffDays(hist.lastStart, today) > 90);
  const freqOk = !missing90 && cyc.length > 0 && avg >= 24 && avg <= 38;
  const freq = missing90 ? "brak miesiączki >90 dni" : !cyc.length ? "za mało cykli" : avg < 24 ? "częsty (<24)" : avg > 38 ? "rzadki (>38)" : "norma (24–38)";
  // długość krwawienia, skrzepy, obfitość i plamienia: tylko z ostatnich 12 miesięcy (jeden dawny epizod nie świeci flagą do końca)
  const recentEps = hist.eps.filter((e) => e.start >= since);
  const bl = recentEps.flatMap((e) => (e.days ? [e.days] : []));
  const bMin = bl.length ? Math.min(...bl) : null;
  const bMax = bl.length ? Math.max(...bl) : null;
  const durOk = bMax == null || bMax <= 8;
  const epOf = (date: string): number => recentEps.findIndex((e) => date >= e.start && date <= e.last);
  const outside = entries.filter((e) => ["spotting", "clot", "flow"].includes(e.type) && e.date >= since && e.date <= today && !nearBleed(hist, e.date));
  // plamienie poza okresem liczy się, gdy powtarza się w różnych cyklach (pojedyncze plamienie bywa przypadkowe)
  const cycleKey = (d: string): string => [...hist.starts].reverse().find((s) => s <= d) ?? "";
  const imbAll = [...new Set(outside.map((e) => e.date))].sort();
  const imb = new Set(imbAll.map(cycleKey)).size >= 2 ? imbAll : [];
  const flowHigh = new Map<number, Set<string>>(), clots = new Set<number>();
  let limits = 0;
  for (const e of entries) {
    if (e.date < since || !hist.bleed.has(e.date)) continue;
    const ep = epOf(e.date);
    if (e.type === "clot") clots.add(ep);
    if (e.type === "pain" && e.limits) limits++;
    if (e.type === "flow" && (Number(e.value) || 0) >= 4) flowHigh.set(ep, (flowHigh.get(ep) ?? new Set()).add(e.date));
  }
  // obfite krwawienie: obfitość 4–5 w co najmniej 2 dniach jednej miesiączki albo skrzepy w co najmniej 2 miesiączkach
  const hmb = [...flowHigh.values()].some((s) => s.size >= 2) || clots.size >= 2;
  const cond = settings.health?.conditions ?? "";
  const anemia = /anemi|niedokrwisto|niedob\S*\s+żelaz|niska\s+ferryt|ferrytyn\S*\s+(niska|obniżon)/i.test(cond)
    && !/(brak|bez|wykluczon\w*|nie\s+ma|nie\s+stwierdz\w*)\s+(anemi\w*|niedokrwisto\w*)|(anemi\w*|niedokrwisto\w*)\s+(wykluczon\w*|nie\s+stwierdz\w*)/i.test(cond);
  const flowLogged = entries.some((e) => e.type === "flow" && hist.bleed.has(e.date));
  const testPos = entries.some((e) => e.type === "preg_test" && e.result === "pos" && diffDays(e.date, today) >= 0 && diffDays(e.date, today) <= 100);
  const clinic: string[] = [];
  // krwawienie po ≥12 miesiącach bez miesiączki: w każdym wieku wymaga oceny lekarza
  for (let i = 1; i < hist.starts.length; i++) {
    if (diffDays(hist.starts[i - 1]!, hist.starts[i]!) >= 365 && diffDays(hist.starts[i]!, today) <= 365) {
      clinic.push(`krwawienie (${hist.starts[i]}) po ponad 12 miesiącach bez miesiączki: do oceny lekarza, także gdy jest skąpe`);
    }
  }
  if (mode !== "pause") {
    if (missing90) clinic.push("brak miesiączki >90 dni: najpierw wyklucz ciążę (test), potem omów z lekarzem");
    if (enoughReg && !regOk) clinic.push(`wahanie długości cykli ${variation} dni w ostatnich 12 miesiącach (próg ${lim}); jeśli się utrzymuje, warto omówić`);
    // pojedynczy cykl poza 24–38 dniami to jeszcze nie nieprawidłowość: liczy się powtarzanie się (≥2 z ostatnich 6) albo mediana poza zakresem
    const last6 = cyc.slice(-6), outLast6 = last6.filter((c) => c.days < 24 || c.days > 38);
    const med = lens.length ? median(lens) : null;
    if (outLast6.length >= 2 || (cyc.length >= 3 && med != null && (med < 24 || med > 38))) {
      clinic.push(`cykle poza zakresem 24–38 dni: ${outLast6.length} z ostatnich ${last6.length} (${outLast6.map((c) => `${c.start} → ${c.end}: ${c.days} d`).join("; ")})`);
    }
    if (overdueDays > 7) clinic.push(`okres spóźniony o ${overdueDays} dni poza własnym typowym zakresem: jeśli był stosunek, zrób test ciążowy`);
  }
  if (testPos) clinic.push("dodatni test ciążowy w dzienniku: umów wizytę u lekarza lub położnej");
  if (bMax != null && bMax > 8) clinic.push("krwawienie >8 dni");
  if (imb.length) clinic.push(`plamienie/skrzepy poza okresem w kilku cyklach: ${imb.join(", ")}`);
  if (hmb) clinic.push("sygnał obfitego krwawienia (obfitość 4–5 przez kilka dni albo skrzepy w kilku miesiączkach; opis, nie pomiar ml)");
  if (limits >= 2) clinic.push("ból w czasie miesiączki, który psuje sen albo pracę, w kilku dniach: warto omówić");
  if (anemia && (hmb || (bMax != null && bMax > 7))) clinic.push("anemia przy sygnałach obfitego krwawienia: warto ustalić z lekarzem, czy miesiączki nie pogłębiają niedoboru żelaza");
  const urgent = missing90 && mode !== "pause" || testPos;
  const urgentWhy = missing90 && mode !== "pause" ? "brak miesiączki >90 dni (najpierw wyklucz ciążę testem)" : testPos ? "dodatni test ciążowy w dzienniku" : "";
  return { avg, mn, mx, variation, lim, band, enoughReg, regOk, freq, freqOk, bMin, bMax, durOk, imb, hmb, clinic,
    urgent, urgentWhy, nCycles: cyc.length, anemia, flowLogged };
};

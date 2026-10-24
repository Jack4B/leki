import type { AgeBand, Entry, History, Pmf, Settings } from "./types.ts";
import { addDays, diffDays, mean } from "./dates.ts";
import { T } from "./model.ts";

/* ---------- rozkłady dyskretne (wartość → prawdopodobieństwo) ---------- */
export const normalize = (p: Pmf): Pmf => {
  let s = 0;
  for (const v of p.values()) s += v;
  const out: Pmf = new Map();
  if (s <= 0) return out;
  for (const [k, v] of p) out.set(k, v / s);
  return out;
};
export const addTo = (p: Pmf, k: number, v: number): void => { p.set(k, (p.get(k) ?? 0) + v); };
/** Rozkład sumy dwóch niezależnych zmiennych. */
export const convolve = (a: Pmf, b: Pmf): Pmf => {
  const out: Pmf = new Map();
  for (const [x, px] of a) for (const [y, py] of b) addTo(out, x + y, px * py);
  return out;
};
export const negate = (a: Pmf): Pmf => new Map([...a].map(([k, v]) => [-k, v] as [number, number]));
export const pmfQuantile = (p: Pmf, q: number): number => {
  const keys = [...p.keys()].sort((x, y) => x - y);
  let acc = 0;
  for (const k of keys) { acc += p.get(k)!; if (acc >= q - 1e-12) return k; }
  return keys.at(-1) ?? 0;
};
export const pmfMean = (p: Pmf): number => { let m = 0; for (const [k, v] of p) m += k * v; return m; };

/** Parametry rozkładów zależne od wieku: po 45. roku życia cykle są mniej przewidywalne (okołomenopauza), więc rozkłady są szersze, a pewność prognozy ograniczona. */
export interface AgeProfile { kernel: number[]; prior: number; lutealSpread: number; confCap: "high" | "mid" | "low" }
export const profileOf = (band?: AgeBand): AgeProfile =>
  band === "51+" ? { kernel: [1, 2, 3, 4, 3, 2, 1], prior: 14, lutealSpread: 4, confCap: "low" }
  : band === "46-50" ? { kernel: [1, 2, 3, 2, 1], prior: 10, lutealSpread: 3, confCap: "mid" }
  : { kernel: [1, 2, 1], prior: 7, lutealSpread: 2, confCap: "high" };

/**
 * Rozkład długości cyklu: ostatnie 8 cykli z wagą malejącą z wiekiem (0,85 na cykl),
 * rozmyte jądrem (±1 dzień; szerzej po 45. roku życia); przy <3 cyklach dokładany jest łagodny rozkład a priori
 * (szerszy, bo rozrzut długości cyklu między osobami jest duży).
 */
export const cycleLengthPmf = (lengths: number[], fallback = 29, prof: AgeProfile = profileOf()): Pmf => {
  const recent = lengths.slice(-8);
  const p: Pmf = new Map();
  const half = (prof.kernel.length - 1) / 2, ksum = prof.kernel.reduce((a, b) => a + b, 0);
  recent.forEach((len, i) => {
    const w = 0.85 ** (recent.length - 1 - i);
    prof.kernel.forEach((kw, j) => addTo(p, len + j - half, (w * kw) / ksum));
  });
  const missing = Math.max(0, 3 - recent.length), norm = (prof.prior + 1) ** 2;
  for (let d = -prof.prior; d <= prof.prior; d++) addTo(p, fallback + d, missing * (prof.prior + 1 - Math.abs(d)) / norm);
  return normalize(p);
};

/** Faza lutealna zwykle 12–16 dni (środek 14); rozrzut rośnie z wiekiem. */
export const lutealPmf = (center = 14, spread = 2): Pmf => {
  const p: Pmf = new Map();
  for (let d = -spread; d <= spread; d++) addTo(p, center + d, spread + 1 - Math.abs(d));
  return normalize(p);
};

/**
 * Względna szansa poczęcia przy stosunku w dniu względem owulacji.
 * Wartości orientacyjne wg Wilcox i wsp., NEJM 1995 (dzień +1 ustawiony ostrożnie).
 */
export const FERTILITY_BY_OFFSET: Record<number, number> = {
  [-5]: 0.10, [-4]: 0.16, [-3]: 0.14, [-2]: 0.27, [-1]: 0.31, 0: 0.33, 1: 0.05,
};
export const MAX_FERTILITY = 0.33;
/** Poziomy okna płodnego: prawdopodobieństwo, że dany dzień należy do okna (wg Wilcox 1995 to 6 dni kończących się w dniu owulacji; model liczy też dzień po owulacji z małą wagą, razem 7 dni). */
export const WIN_HIGH = 0.5;
export const WIN_MID = 0.2;
export type Level = "high" | "mid" | "low";
/** „Bardzo prawdopodobne” okno płodne: ≥50% szans, że dzień jest w oknie; „możliwe”: 20–50%. */
export const windowLevel = (q: number): Level => (q >= WIN_HIGH ? "high" : q >= WIN_MID ? "mid" : "low");

/** Metoda kalendarzowa (np. Mayo Clinic, Family Planning Handbook): pierwszy dzień płodny = najkrótszy cykl − 18, ostatni = najdłuższy − 11 (z co najmniej 6 ostatnich cykli). */
export interface CalendarRule { from: number; to: number; n: number; standardDays: boolean }
export const calendarRule = (lengths: number[]): CalendarRule | null => {
  const l = lengths.slice(-12);
  if (l.length < 6) return null;
  return { from: Math.max(1, Math.min(...l) - 18), to: Math.max(1, Math.max(...l) - 11), n: l.length, standardDays: l.slice(-6).every((x) => x >= 26 && x <= 32) };
};

export interface NextPeriod { k: number; date: string; from: string; to: string }
export interface FertileRun { from: string; to: string; peak: string }
export interface Prediction {
  /** Względna szansa poczęcia dla każdej daty (suma po cyklach: bieżący + 2 następne). */
  fertility: Map<string, number>;
  /** Prawdopodobieństwo, że dzień należy do okna płodnego (6 dni kończących się w dniu owulacji); to z niego wynikają poziomy w kalendarzu. */
  window: Map<string, number>;
  /** Okno wg metody kalendarzowej (od 6 cykli) jako kontrola obok modelu; null przy mniejszej liczbie cykli i w trybach bez prognoz płodności. */
  calendar: CalendarRule | null;
  nextPeriods: NextPeriod[];
  lengthMedian: number; lengthLow: number; lengthHigh: number; lengthSd: number;
  confidence: "high" | "mid" | "low";
  expectedOvulation: string | null;
  lhUsed: boolean;
  overdueDays: number;
  nextRun: FertileRun | null;
  /** Brak prognozy bieżącego cyklu: historia nie ma tak długich cykli (okres bardzo spóźniony) albo prognozy są wyłączone w ustawieniach. */
  noForecast: boolean;
  /** Tryb z ustawień: prognozy płodności wyłączone (antykoncepcja hormonalna) albo wszystkie prognozy (ciąża, połóg, karmienie). */
  mode: "normal" | "hormonal" | "pause";
}

const sd = (p: Pmf): number => { const m = pmfMean(p); let v = 0; for (const [k, x] of p) v += x * (k - m) ** 2; return Math.sqrt(v); };

const addOvulationWeights = (map: Map<string, number>, ovDates: Map<string, number>, win?: Map<string, number>): void => {
  for (const [ov, p] of ovDates) {
    for (let off = -5; off <= 1; off++) {
      const date = addDays(ov, off);
      map.set(date, (map.get(date) ?? 0) + p * FERTILITY_BY_OFFSET[off]!);
      if (win) win.set(date, (win.get(date) ?? 0) + p);
    }
  }
};

/** Początek ostatniej serii dodatnich testów LH (testy w odstępie ≤2 dni to jedna seria). */
const lhRunStart = (ds: string[]): string | null => {
  let i = ds.length - 1;
  while (i > 0 && diffDays(ds[i - 1]!, ds[i]!) <= 2) i--;
  return ds[i] ?? null;
};

/** Rozkład ograniczony do wartości ≥ min i znormalizowany; null, gdy zostaje zaniedbywalna masa. */
const conditionOn = (p: Pmf, min: number): Pmf | null => {
  const f = new Map([...p].filter(([k]) => k >= min));
  const mass = [...f.values()].reduce((a, b) => a + b, 0);
  return mass < 0.02 ? null : normalize(f);
};

export const predict = (hist: History, entries: Entry[], settings: Settings, today: string): Prediction => {
  const fertility = new Map<string, number>();
  const win = new Map<string, number>();
  const prof = profileOf(settings.ageBand);
  const mode = settings.mode === "hormonal" || settings.mode === "pause" ? settings.mode : "normal";
  const luteal = lutealPmf(settings.lutealDays ?? 14, prof.lutealSpread);
  const lhAll = [...new Set(entries.filter((e) => e.type === T.LH && e.result !== "neg").map((e) => e.date))].sort();
  /**
   * Wiarygodny test LH w cyklu: nie w trakcie miesiączki ani tuż po niej (cykl dnia 1–8 to zwykle słaby, przypadkowy wynik),
   * nie później niż 9 dni przed następnym okresem (faza lutealna jest dłuższa) i nie w przyszłości; przy kilku seriach liczy się ostatnia.
   */
  const lhIn = (start: string, end: string | null): string | null =>
    lhRunStart(lhAll.filter((d) => d >= start && (!end || d < end) && d <= today && diffDays(start, d) >= hist.bleedLen + 3 && (!end || diffDays(d, end) >= 9)));
  const lhPmf = (d: string): Map<string, number> => new Map([[addDays(d, 0), 0.25], [addDays(d, 1), 0.5], [addDays(d, 2), 0.25]]);

  // 1) cykle zakończone: owulacja wstecz od rzeczywistego następnego okresu (albo od testu LH)
  for (const c of hist.cycles) {
    const lh = lhIn(c.start, c.end);
    if (lh) { addOvulationWeights(fertility, lhPmf(lh), win); continue; }
    const ov = new Map<string, number>();
    for (const [l, p] of luteal) addTo2(ov, addDays(c.end, -l), p);
    addOvulationWeights(fertility, ov, win);
  }

  const lengths = hist.cycles.map((c) => c.days);
  const base = cycleLengthPmf(lengths, settings.averageCycleLength ?? 29, prof);
  const recent = lengths.slice(-8);
  const out: Prediction = {
    fertility, window: win, calendar: null, nextPeriods: [], lengthMedian: Math.round(pmfQuantile(base, 0.5)),
    lengthLow: Math.round(pmfQuantile(base, 0.1)), lengthHigh: Math.round(pmfQuantile(base, 0.9)),
    lengthSd: sd(base), confidence: "low", expectedOvulation: null, lhUsed: false, overdueDays: 0, nextRun: null, noForecast: false, mode,
  };
  // pewność: „wysoka” dopiero od 6 cykli, a po 45. roku życia ograniczona (cykle bywają bezowulacyjne i nieregularne)
  const raw = recent.length < 3 ? "low" : out.lengthSd <= 2.5 ? (recent.length >= 6 ? "high" : "mid") : out.lengthSd <= 4.5 ? "mid" : "low";
  const rank = { low: 0, mid: 1, high: 2 } as const;
  out.confidence = rank[raw] <= rank[prof.confCap] ? raw : prof.confCap;
  if (mode === "pause") { fertility.clear(); win.clear(); out.noForecast = true; return out; }
  if (mode === "normal") out.calendar = calendarRule(lengths);   // ciąża, połóg, karmienie: żadnych prognoz
  const last = hist.lastStart;
  if (!last) { if (mode === "hormonal") { fertility.clear(); win.clear(); } return out; }

  // 2) bieżący cykl: warunkowanie na to, że okresu jeszcze nie ma (okres może zacząć się dopiero dziś lub później)
  const cycleDay = Math.max(1, diffDays(last, today) + 1);
  const sinceStart = cycleDay - 1;
  const lh0 = lhIn(last, null);
  let s0: Pmf | null = null;
  // „spóźnienie” liczymy od końca własnego zakresu (80%): z rozkładu długości cykli albo, gdy jest wiarygodny test LH, z prognozy opartej na owulacji
  let overdueFrom = Math.max(out.lengthHigh, out.lengthMedian + 1);
  if (lh0) {
    // test LH przesuwa prognozę okresu: owulacja ≈ dzień testu + 1, następny okres po fazie lutealnej
    const offs: Pmf = new Map();
    for (const [d, w] of lhPmf(lh0)) addTo(offs, diffDays(last, d), w);
    const lhS = convolve(offs, luteal);
    overdueFrom = pmfQuantile(lhS, 0.9);
    s0 = conditionOn(lhS, sinceStart);
  }
  if (!s0) { if (lh0) overdueFrom = Math.max(overdueFrom, Math.max(out.lengthHigh, out.lengthMedian + 1)); s0 = conditionOn(base, sinceStart); }
  out.overdueDays = Math.max(0, sinceStart - overdueFrom);
  if (!s0) {
    // historia nie ma tak długich cykli: prognozy z rozkładu nie ma, a wynik z rozkładu jednostajnego tylko udawałby pewność
    out.noForecast = true;
    if (mode === "hormonal") { fertility.clear(); win.clear(); }
    return out;
  }

  // 3) kolejne starty okresów: S_k = rozkład przesunięcia (dni od ostatniego startu) początku cyklu k+1
  let S = s0;
  for (let k = 0; k < 3; k++) {
    if (k > 0) S = convolve(S, base);
    out.nextPeriods.push({
      k: k + 1, date: addDays(last, Math.round(pmfQuantile(S, 0.5))),
      from: addDays(last, pmfQuantile(S, 0.1)), to: addDays(last, pmfQuantile(S, 0.9)),
    });
    const ovOffsets = convolve(S, negate(luteal));
    const ovDates = new Map<string, number>();
    if (k === 0 && lh0) { out.lhUsed = true; addOvulationWeights(fertility, lhPmf(lh0), win); continue; }
    // owulacja nie może wypaść w trakcie miesiączki
    const feasible = normalize(new Map([...ovOffsets].filter(([o]) => o >= hist.bleedLen)));
    const useOffsets = feasible.size ? feasible : ovOffsets;
    for (const [o, p] of useOffsets) addTo2(ovDates, addDays(last, o), p);
    addOvulationWeights(fertility, ovDates, win);
    if (k === 0) out.expectedOvulation = addDays(last, Math.round(pmfQuantile(useOffsets, 0.5)));
  }
  if (out.lhUsed) out.expectedOvulation = addDays(lh0!, 1);
  // szacowana owulacja bez testu LH i przy dużym rozrzucie cykli to zgadywanie: nie pokazujemy jednej daty
  else if (out.lengthSd > 2.5) out.expectedOvulation = null;

  if (mode === "hormonal") {
    // hormonalna antykoncepcja: krwawienia są prognozowane, ale szans poczęcia i owulacji nie liczymy
    fertility.clear(); win.clear(); out.calendar = null; out.expectedOvulation = null; out.lhUsed = false;
    return out;
  }

  // 4) najbliższe okno: pierwszy ciągły przedział dni, w którym okno płodne jest co najmniej „możliwe” (≥20%), i który nie skończył się przed dziś
  const run: string[] = [];
  let found: FertileRun | null = null;
  for (let i = 0; i <= 90 && !found; i++) {
    const d = addDays(today, i);
    if ((win.get(d) ?? 0) >= WIN_MID) run.push(d);
    else if (run.length) found = mkRun(run, fertility);
  }
  if (!found && run.length) found = mkRun(run, fertility);
  out.nextRun = found;
  return out;
};

function addTo2(m: Map<string, number>, k: string, v: number): void { m.set(k, (m.get(k) ?? 0) + v); }
function mkRun(days: string[], f: Map<string, number>): FertileRun {
  let peak = days[0]!;
  for (const d of days) if ((f.get(d) ?? 0) > (f.get(peak) ?? 0)) peak = d;
  return { from: days[0]!, to: days.at(-1)!, peak };
}
export const fertilityOn = (p: Prediction, date: string): number => p.fertility.get(date) ?? 0;
export const windowOn = (p: Prediction, date: string): number => Math.min(1, p.window.get(date) ?? 0);
export const meanOf = mean;

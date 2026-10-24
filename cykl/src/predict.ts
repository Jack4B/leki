import type { Entry, History, Pmf, Settings } from "./types.ts";
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

/**
 * Rozkład długości cyklu: ostatnie 8 cykli z wagą malejącą z wiekiem (0,85 na cykl),
 * rozmyte jądrem ±1 dzień; przy <3 cyklach dokładany jest łagodny rozkład a priori.
 */
export const cycleLengthPmf = (lengths: number[], fallback = 28): Pmf => {
  const recent = lengths.slice(-8);
  const p: Pmf = new Map();
  recent.forEach((len, i) => {
    const w = 0.85 ** (recent.length - 1 - i);
    addTo(p, len - 1, w * 0.25); addTo(p, len, w * 0.5); addTo(p, len + 1, w * 0.25);
  });
  const missing = Math.max(0, 3 - recent.length);
  for (let d = -4; d <= 4; d++) addTo(p, fallback + d, missing * (5 - Math.abs(d)) / 25);
  return normalize(p);
};

/** Faza lutealna zwykle 12–16 dni (środek 14). */
export const lutealPmf = (center = 14): Pmf => {
  const p: Pmf = new Map();
  for (let d = -2; d <= 2; d++) addTo(p, center + d, 3 - Math.abs(d));
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
export const HIGH = 0.15;
export const MID = 0.04;
export type Level = "high" | "mid" | "low";
export const levelOf = (f: number): Level => (f >= HIGH ? "high" : f >= MID ? "mid" : "low");

export interface NextPeriod { k: number; date: string; from: string; to: string }
export interface FertileRun { from: string; to: string; peak: string }
export interface Prediction {
  /** Względna szansa poczęcia dla każdej daty (suma po cyklach: bieżący + 2 następne). */
  fertility: Map<string, number>;
  nextPeriods: NextPeriod[];
  lengthMedian: number; lengthLow: number; lengthHigh: number; lengthSd: number;
  confidence: "high" | "mid" | "low";
  expectedOvulation: string | null;
  lhUsed: boolean;
  overdueDays: number;
  nextRun: FertileRun | null;
}

const sd = (p: Pmf): number => { const m = pmfMean(p); let v = 0; for (const [k, x] of p) v += x * (k - m) ** 2; return Math.sqrt(v); };

const addOvulationWeights = (map: Map<string, number>, ovDates: Map<string, number>): void => {
  for (const [ov, p] of ovDates) {
    for (let off = -5; off <= 1; off++) {
      const date = addDays(ov, off);
      map.set(date, (map.get(date) ?? 0) + p * FERTILITY_BY_OFFSET[off]!);
    }
  }
};

export const predict = (hist: History, entries: Entry[], settings: Settings, today: string): Prediction => {
  const fertility = new Map<string, number>();
  const luteal = lutealPmf(settings.lutealDays ?? 14);
  const lhDates = [...new Set(entries.filter((e) => e.type === T.LH && e.result !== "neg").map((e) => e.date))].sort();
  const lhIn = (start: string, end: string | null): string | null => lhDates.find((d) => d >= start && (!end || d < end)) ?? null;
  const lhPmf = (d: string): Map<string, number> => new Map([[addDays(d, 0), 0.25], [addDays(d, 1), 0.5], [addDays(d, 2), 0.25]]);

  // 1) cykle zakończone: owulacja wstecz od rzeczywistego następnego okresu (albo od testu LH)
  for (const c of hist.cycles) {
    const lh = lhIn(c.start, c.end);
    if (lh) { addOvulationWeights(fertility, lhPmf(lh)); continue; }
    const ov = new Map<string, number>();
    for (const [l, p] of luteal) addTo2(ov, addDays(c.end, -l), p);
    addOvulationWeights(fertility, ov);
  }

  const lengths = hist.cycles.map((c) => c.days);
  const base = cycleLengthPmf(lengths, settings.averageCycleLength ?? 28);
  const recent = lengths.slice(-8);
  const out: Prediction = {
    fertility, nextPeriods: [], lengthMedian: Math.round(pmfQuantile(base, 0.5)),
    lengthLow: Math.round(pmfQuantile(base, 0.1)), lengthHigh: Math.round(pmfQuantile(base, 0.9)),
    lengthSd: sd(base), confidence: "low", expectedOvulation: null, lhUsed: false, overdueDays: 0, nextRun: null,
  };
  out.confidence = recent.length < 3 ? "low" : out.lengthSd <= 2.5 ? "high" : out.lengthSd <= 4.5 ? "mid" : "low";
  const last = hist.lastStart;
  if (!last) return out;

  // 2) bieżący cykl: warunkowanie na to, że okresu jeszcze nie ma (długość ≥ dzień cyklu)
  const cycleDay = Math.max(1, diffDays(last, today) + 1);
  let s0: Pmf = new Map([...base].filter(([k]) => k >= cycleDay));
  if (s0.size === 0 || [...s0.values()].reduce((a, b) => a + b, 0) < 1e-9) {
    s0 = new Map();
    for (let k = cycleDay; k <= cycleDay + 14; k++) s0.set(k, 1);   // brak historii tak długich cykli
  }
  s0 = normalize(s0);
  out.overdueDays = Math.max(0, diffDays(addDays(last, out.lengthMedian), today));

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
    const lh = k === 0 ? lhIn(last, null) : null;
    if (lh) { out.lhUsed = true; addOvulationWeights(fertility, lhPmf(lh)); continue; }
    // owulacja nie może wypaść w trakcie miesiączki
    const feasible = normalize(new Map([...ovOffsets].filter(([o]) => o >= hist.bleedLen)));
    const useOffsets = feasible.size ? feasible : ovOffsets;
    for (const [o, p] of useOffsets) addTo2(ovDates, addDays(last, o), p);
    addOvulationWeights(fertility, ovDates);
    if (k === 0) out.expectedOvulation = addDays(last, Math.round(pmfQuantile(useOffsets, 0.5)));
  }
  if (out.lhUsed) {
    const lh = lhIn(last, null)!;
    out.expectedOvulation = addDays(lh, 1);
  }

  // 4) najbliższe okno: pierwszy ciągły przedział dni z szansą ≥ MID, który nie skończył się przed dziś
  const run: string[] = [];
  let found: FertileRun | null = null;
  for (let i = 0; i <= 90 && !found; i++) {
    const d = addDays(today, i);
    if ((fertility.get(d) ?? 0) >= MID) run.push(d);
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
export const meanOf = mean;

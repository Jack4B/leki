import type { AgeBand, History, Settings } from "./types.ts";
import { addDays, diffDays, mean, stdev, theilSen } from "./dates.ts";
import type { Entry } from "./types.ts";

/** Próg regularności wg FIGO 2018: wahanie najkrótszy–najdłuższy cykl. */
export const regularityLimit = (band: AgeBand): number => (band === "18-25" || band === "42-45" ? 9 : 7);

export interface Trend {
  n: number; lengths: number[]; slope: number; intercept: number; fitted: number[];
  direction: "stable" | "longer" | "shorter" | "unknown"; recent3: number | null; prev3: number | null; sd: number;
}
/** Trend długości cykli: nachylenie Theil–Sen (dni na cykl) + porównanie 3 ostatnich z 3 poprzednimi. */
export const trendOf = (lengths: number[]): Trend => {
  const n = lengths.length;
  if (n < 4) return { n, lengths, slope: 0, intercept: lengths[0] ?? 0, fitted: lengths, direction: "unknown", recent3: null, prev3: null, sd: n ? stdev(lengths) : 0 };
  const { slope, intercept } = theilSen(lengths);
  const fitted = lengths.map((_, i) => intercept + slope * i);
  const recent3 = mean(lengths.slice(-3));
  const prev3 = n >= 6 ? mean(lengths.slice(-6, -3)) : null;
  const direction = Math.abs(slope) < 0.3 ? "stable" : slope > 0 ? "longer" : "shorter";
  return { n, lengths, slope, intercept, fitted, direction, recent3, prev3, sd: stdev(lengths) };
};

export interface Straw {
  bigJumps: { from: string; days: number }[];
  stage: "none" | "possible-early" | "early-hint" | "late-hint";
  text: string;
}
/**
 * Wskazówka wg STRAW+10 (Harlow i wsp. 2012), nie rozpoznanie:
 * wczesne przejście = powtarzająca się (w ciągu 10 cykli) różnica ≥7 dni między kolejnymi cyklami;
 * późne przejście = przerwa ≥60 dni.
 */
export const strawHint = (hist: History, today: string): Straw => {
  const last10 = hist.cycles.slice(-10);
  const bigJumps: Straw["bigJumps"] = [];
  for (let i = 1; i < last10.length; i++) {
    const d = Math.abs(last10[i]!.days - last10[i - 1]!.days);
    if (d >= 7) bigJumps.push({ from: last10[i]!.start, days: d });
  }
  const openDays = hist.lastStart ? diffDays(hist.lastStart, today) + 1 : 0;
  const gap60 = last10.some((c) => c.days >= 60) || openDays >= 60;
  if (gap60) return { bigJumps, stage: "late-hint", text: "Przerwa ≥60 dni między miesiączkami: kryterium późnej fazy przejściowej wg STRAW+10. Do oceny lekarza." };
  if (bigJumps.length >= 2) return { bigJumps, stage: "early-hint", text: `Różnica ≥7 dni między kolejnymi cyklami wystąpiła ${bigJumps.length} razy w ostatnich 10 cyklach: kryterium wczesnej fazy przejściowej wg STRAW+10. Podobny obraz mogą dawać też choroby tarczycy. Do oceny lekarza.` };
  if (bigJumps.length === 1) return { bigJumps, stage: "possible-early", text: "Jedna różnica ≥7 dni między kolejnymi cyklami. To jeszcze nie wzór, obserwuj dalej." };
  return { bigJumps, stage: "none", text: "Brak powtarzających się skoków ≥7 dni między kolejnymi cyklami." };
};

export interface Figo {
  avg: number; mn: number; mx: number; variation: number; lim: number; band: AgeBand;
  enoughReg: boolean; regOk: boolean; freq: string; freqOk: boolean;
  bMin: number | null; bMax: number | null; durOk: boolean; imb: string[]; hmb: boolean;
  clinic: string[]; urgent: boolean; urgentWhy: string; nCycles: number; anemia: boolean; flowLogged: boolean;
}
export const figoSystem1 = (hist: History, entries: Entry[], settings: Settings, today: string, overdueDays: number): Figo => {
  const since = addDays(today, -365);
  const c12 = hist.cycles.filter((c) => c.end >= since);
  const cyc = c12.length ? c12 : hist.cycles;
  const band = settings.ageBand ?? "42-45";
  const lim = regularityLimit(band);
  const lens = cyc.map((c) => c.days);
  const avg = lens.length ? Math.round(mean(lens)) : settings.averageCycleLength ?? 28;
  const mn = lens.length ? Math.min(...lens) : avg;
  const mx = lens.length ? Math.max(...lens) : avg;
  const variation = mx - mn;
  const enoughReg = cyc.length >= 3;
  const regOk = enoughReg && variation <= lim;
  const missing90 = Boolean(hist.lastStart && diffDays(hist.lastStart, today) > 90);
  const freqOk = !missing90 && cyc.length > 0 && avg >= 24 && avg <= 38;
  const freq = missing90 ? "brak miesiączki >90 dni" : !cyc.length ? "za mało cykli" : avg < 24 ? "częsty (<24)" : avg > 38 ? "rzadki (>38)" : "norma (24–38)";
  const bl = hist.eps.flatMap((e) => (e.days ? [e.days] : []));
  const bMin = bl.length ? Math.min(...bl) : null;
  const bMax = bl.length ? Math.max(...bl) : null;
  const durOk = bMax == null || bMax <= 8;
  const outside = entries.filter((e) => ["spotting", "clot", "flow"].includes(e.type) && !hist.bleed.has(e.date));
  const imb = [...new Set(outside.map((e) => e.date))].sort();
  let clot = 0, limits = 0, maxFlow = 0;
  for (const e of entries) {
    if (!hist.bleed.has(e.date)) continue;
    if (e.type === "clot") clot++;
    if (e.type === "pain" && e.limits) limits++;
    if (e.type === "flow") maxFlow = Math.max(maxFlow, Number(e.value) || 0);
  }
  const hmb = maxFlow >= 4 || clot > 0 || limits > 0;
  const anemia = /anemi|niedokrwisto|niedob\S*\s+żelaz|ferryt/i.test(settings.health?.conditions ?? "");
  const flowLogged = entries.some((e) => e.type === "flow" && hist.bleed.has(e.date));
  const testPos = entries.some((e) => e.type === "preg_test" && e.result === "pos");
  const clinic: string[] = [];
  if (missing90) clinic.push("brak miesiączki >90 dni");
  if (testPos) clinic.push("dodatni test ciążowy w dzienniku");
  if (enoughReg && !regOk) clinic.push(`wahanie długości cykli ${variation} dni (próg ${lim})`);
  for (const c of cyc) if (c.days < 24 || c.days > 38) clinic.push(`cykl ${c.start} → ${c.end}: ${c.days} dni (poza 24–38)`);
  if (bMax != null && bMax > 8) clinic.push("krwawienie >8 dni");
  if (imb.length) clinic.push(`plamienie/skrzepy poza okresem: ${imb.join(", ")}`);
  if (hmb) clinic.push("sygnał obfitości / skrzepów / bólu ograniczającego życie (opis, nie pomiar ml)");
  if (anemia && (hmb || (bMax != null && bMax > 7))) clinic.push("anemia przy sygnałach obfitego krwawienia: warto ustalić z lekarzem, czy miesiączki nie pogłębiają niedoboru żelaza");
  if (overdueDays > 7) clinic.push(`okres spóźniony o ${overdueDays} dni względem typowego cyklu`);
  return { avg, mn, mx, variation, lim, band, enoughReg, regOk, freq, freqOk, bMin, bMax, durOk, imb, hmb, clinic,
    urgent: missing90 || testPos, urgentWhy: missing90 ? "brak miesiączki >90 dni" : testPos ? "dodatni test ciążowy w dzienniku" : "", nCycles: cyc.length, anemia, flowLogged };
};

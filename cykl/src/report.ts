import type { Db, History } from "./types.ts";
import { T } from "./model.ts";
import { diffDays } from "./dates.ts";
import { MID, fertilityOn } from "./predict.ts";
import type { Analysis } from "./analysis.ts";

export interface Intimacy {
  sex: number; protected: number; unprotected: number; solo: number; pct: number;
  unprotectedAtRisk: number; atRiskTotal: number;
}
/** "W ryzyku" = stosunek w dniu, w którym szacowana szansa poczęcia ≥ progu MID. */
export const intimacyOf = (a: Analysis): Intimacy => {
  const sex = new Set<string>(), prot = new Set<string>(), solo = new Set<string>();
  for (const e of a.entries) {
    if (e.type === T.SEX) sex.add(e.date);
    if (e.type === T.PROT) prot.add(e.date);
    if (e.type === T.SOLO || e.type === T.HIM) solo.add(e.date);
  }
  const days = [...sex];
  const secured = days.filter((d) => prot.has(d)).length;
  const risk = days.filter((d) => fertilityOn(a.pred, d) >= MID);
  return {
    sex: days.length, protected: secured, unprotected: days.length - secured, solo: solo.size,
    pct: days.length ? Math.round((secured / days.length) * 100) : 0,
    unprotectedAtRisk: risk.filter((d) => !prot.has(d)).length, atRiskTotal: risk.length,
  };
};

export interface MonthRow { month: string; sex: number; prot: number; solo: number; bleed: number }
export const monthlyOf = (a: Analysis): MonthRow[] => {
  const by = new Map<string, { sex: Set<string>; prot: Set<string>; solo: Set<string>; bleed: number }>();
  const row = (ym: string) => { let r = by.get(ym); if (!r) { r = { sex: new Set(), prot: new Set(), solo: new Set(), bleed: 0 }; by.set(ym, r); } return r; };
  for (const e of a.entries) {
    const ym = e.date.slice(0, 7);
    if (!/^\d{4}-\d{2}$/.test(ym)) continue;
    const r = row(ym);
    if (e.type === T.SEX) r.sex.add(e.date);
    if (e.type === T.PROT) r.prot.add(e.date);
    if (e.type === T.SOLO || e.type === T.HIM) r.solo.add(e.date);
  }
  for (const d of a.hist.bleed) row(d.slice(0, 7)).bleed++;
  return [...by].sort(([x], [y]) => x.localeCompare(y)).map(([month, r]) => ({
    month, sex: r.sex.size, prot: [...r.prot].filter((d) => r.sex.has(d)).length, solo: r.solo.size, bleed: r.bleed,
  }));
};

export interface LaneRow {
  start: string; length: number; open: boolean; bleed: number;
  win: [number, number] | null; events: { day: number; prot: boolean }[]; solo: number;
}
/** Jeden rząd na cykl: okres, okno płodne wyliczone wstecz (owulacja ≈ koniec − 14), stosunki. */
export const lanesOf = (a: Analysis): LaneRow[] => {
  const h: History = a.hist;
  const rows: LaneRow[] = [];
  const sexDates = a.entries.filter((e) => e.type === T.SEX).map((e) => e.date);
  const prot = new Set(a.entries.filter((e) => e.type === T.PROT).map((e) => e.date));
  const solo = a.entries.filter((e) => e.type === T.SOLO || e.type === T.HIM).map((e) => e.date);
  h.starts.forEach((start, i) => {
    const next = h.starts[i + 1] ?? null;
    const length = next ? diffDays(start, next) : diffDays(start, a.today) + 1;
    if (length < 1) return;                      // start okresu w przyszłości: bez rzędu (ujemna szerokość)
    if (next && length < 15) return;
    const ep = h.eps[i]!;
    const bleed = ep.days ?? h.bleedLen;
    const ov = length - 14;
    const winFrom = Math.max(bleed, ov - 5);
    const win: [number, number] | null = next && ov + 1 >= winFrom ? [winFrom, ov + 1] : null;
    const inCycle = (d: string) => d >= start && (!next || d < next);
    rows.push({
      start, length, open: !next, bleed, win,
      events: sexDates.filter(inCycle).map((d) => ({ day: diffDays(start, d), prot: prot.has(d) })),
      solo: solo.filter(inCycle).length,
    });
  });
  return rows;
};

const INTIMATE = new Set<string>([T.SEX, T.PROT, T.SOLO, T.HIM]);
const MODE_TXT = { normal: "zwykły cykl", hormonal: "antykoncepcja hormonalna (prognozy płodności wyłączone)", pause: "ciąża, połóg lub karmienie (prognozy wyłączone)" } as const;

/**
 * Tekst raportu dla lekarza / AI. Wpisy intymne nigdy nie wpływają na treść, chyba że wybrano „Dołącz informacje o stosunkach”
 * (wtedy: liczba stosunków i zabezpieczeń oraz ich daty; wpisów solo nie ma w raporcie nigdy).
 */
export const buildBrief = (a: Analysis, db: Db, withDates = false): string => {
  const f = a.figo, t = a.trend, hl = db.settings.health ?? {};
  const mode = a.pred.mode;
  const L: string[] = [
    "# Brief cyklu (FIGO System 1 — objawy, nie przyczyny)",
    `Eksport: ${a.today} · wpisów: ${a.entries.filter((e) => !INTIMATE.has(e.type)).length} · startów: ${a.hist.starts.length}`,
    `Wiek (pasmo): ${f.band === "51+" ? "powyżej 50" : f.band} lat · tryb prognoz: ${MODE_TXT[mode]}`,
    "To nie jest rozpoznanie. Normy: FIGO 2018 (częstość 24–38 d, czas ≤8 d, wahanie ≤7 d w wieku 26–41, ≤9 d w 18–25 i 42–45; po 45. roku życia regularność nie jest oceniana).",
  ];
  if (hl.conditions || hl.meds || hl.tsh || hl.labs) {
    L.push("", "## Kontekst zdrowotny (wpisane ręcznie, rozpoznane przez lekarza)");
    if (hl.conditions) L.push(`- Choroby: ${hl.conditions}`);
    if (hl.meds) L.push(`- Leki: ${hl.meds}`);
    if (hl.tsh) L.push(`- Ostatnie TSH: ${hl.tsh}`);
    if (hl.labs) L.push(`- Morfologia / ferrytyna: ${hl.labs}`);
  }
  L.push("", "## Do gabinetu", ...(f.clinic.length ? f.clinic.map((x) => `- ${x}`) : ["- brak czerwonych flag w dzienniku"]));
  L.push("", "## Osie",
    `- Częstość: średnia ${f.avg} d — ${f.freq}`,
    `- Regularność: ${f.mn}–${f.mx}, wahanie ${f.variation} d (${f.lim == null ? "w tym wieku nie oceniana" : `próg ${f.lim}${f.enoughReg ? "" : ", za mało cykli do oceny (od 6)"}`})`,
    `- Czas trwania: ${f.bMin == null ? "brak końców okresu" : `${f.bMin}–${f.bMax} d`}`,
    `- Plamienie poza okresem: ${f.imb.length ? f.imb.join(", ") : "brak powtarzających się wpisów"}`);
  L.push("", "## Trend i faza przejściowa",
    t.direction === "unknown" ? "- za mało cykli na trend (od 6)" : t.direction === "stable" ? "- bez wyraźnego trendu długości cykli" : `- Trend długości cykli (Theil–Sen): ${t.slope >= 0 ? "+" : ""}${t.slope.toFixed(1)} d na cykl; ostatnie 3 cykle średnio ${t.recent3?.toFixed(0)} d${t.prev3 != null ? `, poprzednie 3: ${t.prev3.toFixed(0)} d` : ""}`,
    `- STRAW+10: ${a.straw.text}`);
  L.push("", "## Cykle (start → następny start)", ...a.hist.cycles.map((c) => `- ${c.start} → ${c.end}: ${c.days} d${c.bleed ? `, krwawienie ${c.bleed} d` : ""}`));
  // informacje o stosunkach tylko na wyraźne życzenie (opcja „Dołącz informacje o stosunkach”); wpisy solo nigdy
  if (withDates) {
    const i = intimacyOf(a);
    L.push("", "## Stosunki (dołączone na życzenie)", `- Liczba: ${i.sex}, z zabezpieczeniem: ${i.protected} (${i.pct}%)`);
    [...a.entries].filter((e) => e.type === T.SEX || e.type === T.PROT).sort((x, y) => x.date.localeCompare(y.date) || x.type.localeCompare(y.type))
      .forEach((e) => L.push(`- ${e.date}: ${e.type === T.SEX ? "stosunek" : "zabezpieczenie"}`));
  }
  return L.join("\n");
};

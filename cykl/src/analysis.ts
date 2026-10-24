import type { Db, Entry, History, Settings } from "./types.ts";
import { addDays } from "./dates.ts";
import { buildHistory } from "./history.ts";
import { predict, windowLevel, fertilityOn, windowOn } from "./predict.ts";
import type { Prediction, Level } from "./predict.ts";
import { figoSystem1, strawHint, trendOf } from "./health.ts";
import type { Figo, Straw, Trend } from "./health.ts";
import { diffDays } from "./dates.ts";

export interface DayInfo {
  bleed: boolean; predBleed: boolean; fertility: number; window: number; level: Level; ovulation: boolean; lh: boolean;
}
export interface Analysis {
  today: string; entries: Entry[]; settings: Settings; hist: History; pred: Prediction;
  figo: Figo; straw: Straw; trend: Trend; cycleDay: number;
  phase: string; todayLevel: Level; lhDays: Set<string>;
  info: (date: string) => DayInfo;
}

export const analyze = (db: Db, today: string): Analysis => {
  const hist = buildHistory(db.entries);
  const pred = predict(hist, db.entries, db.settings, today);
  const lhDays = new Set(db.entries.filter((e) => e.type === "lh" && e.result !== "neg").map((e) => e.date));
  const cycleDay = hist.lastStart && diffDays(hist.lastStart, today) >= 0 ? diffDays(hist.lastStart, today) + 1 : 0;
  const predBleed = new Set<string>();
  for (const p of pred.nextPeriods) for (let i = 0; i < hist.bleedLen; i++) predBleed.add(addDays(p.date, i));
  const lastBleed = hist.eps.at(-1)?.last ?? null;
  const info = (date: string): DayInfo => {
    const bleed = hist.bleed.has(date);
    const f = fertilityOn(pred, date);
    const q = windowOn(pred, date);
    let level = windowLevel(q);
    // przy niskiej pewności prognozy (mało cykli, duży rozrzut) przyszłe dni nie dostają poziomu „wysoka”, chyba że jest test LH
    if (level === "high" && pred.confidence === "low" && !pred.lhUsed && date >= today) level = "mid";
    return {
      bleed, predBleed: !bleed && predBleed.has(date) && (!lastBleed || date > lastBleed),
      fertility: f, window: q, level, ovulation: pred.expectedOvulation === date, lh: lhDays.has(date),
    };
  };
  const t = info(today);
  const sinceStart = cycleDay ? cycleDay - 1 : 0;
  let phase = "Faza cyklu niepewna";
  if (!hist.lastStart) phase = "Brak danych";
  else if (pred.mode === "pause") phase = "Prognozy wyłączone";
  else if (t.bleed) phase = "Miesiączka";
  else if (pred.overdueDays > 0) phase = "Okres się spóźnia";
  else if (pred.noForecast) phase = "Brak prognozy";
  else if (pred.mode === "hormonal") phase = "Antykoncepcja hormonalna";
  else if (t.level === "high") phase = "Okno płodne";
  else if (t.level === "mid") phase = "Możliwe okno płodne";
  else if (sinceStart >= pred.lengthMedian) phase = "Okres spodziewany";
  else if (pred.expectedOvulation && today > addDays(pred.expectedOvulation, 1)) phase = "Po szacowanej owulacji";
  else if (pred.expectedOvulation) phase = "Przed szacowaną owulacją";
  return {
    today, entries: db.entries, settings: db.settings, hist, pred,
    figo: figoSystem1(hist, db.entries, db.settings, today, pred.overdueDays),
    straw: strawHint(hist, today, db.settings.ageBand ?? "26-41", pred.mode), trend: trendOf(hist.cycles.map((c) => c.days)),
    cycleDay, phase, todayLevel: t.level, lhDays, info,
  };
};

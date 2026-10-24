import type { Db, Entry } from "../src/types.ts";
import { addDays } from "../src/dates.ts";

/** Syntetyczny dziennik: starty co zadane długości cykli, krwawienie 5 dni. */
export const makeDb = (first: string, lengths: number[], extra: Entry[] = [], bleedDays = 5): Db => {
  const entries: Entry[] = [];
  let s = first;
  const starts = [s];
  for (const l of lengths) { s = addDays(s, l); starts.push(s); }
  for (const st of starts) {
    entries.push({ type: "period_start", date: st });
    entries.push({ type: "period_end", date: addDays(st, bleedDays - 1) });
  }
  return { entries: [...entries, ...extra], cycles: {}, settings: {} };
};
/** Wymyślone długości cykli: nieregularne, z kilkoma długimi (12 cykli, razem 399 dni). Nie pochodzą z żadnego prawdziwego dziennika. */
export const PERI = [33, 42, 27, 31, 31, 30, 33, 27, 34, 34, 35, 42];

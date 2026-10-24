import type { Db, Entry, DayForm, Settings } from "./types.ts";
import { pad, isIsoDate } from "./dates.ts";

export const T = {
  START: "period_start", END: "period_end", SEX: "sex", PROT: "protected",
  SOLO: "masturb", HIM: "him", LH: "lh",
} as const;

/** Typy wpisów, którymi zarządza formularz dnia; pozostałe (np. z importu) zostają nietknięte. */
export const MANAGED = [
  T.START, T.END, T.SEX, T.PROT, T.SOLO, T.HIM, T.LH,
  "spotting", "clot", "flow", "pain", "painkiller", "preg_test", "note",
];

export const emptyDb = (): Db => ({ entries: [], cycles: {}, settings: {} });

const normDate = (s: unknown): string => {
  const t = String(s ?? "").trim();
  if (/^\d{4}-\d{2}-\d{2}/.test(t)) return t.slice(0, 10);
  const m = t.match(/^(\d{1,2})[./](\d{1,2})[./](\d{4})/);
  return m ? `${m[3]}-${pad(Number(m[2]))}-${pad(Number(m[1]))}` : "";
};

const plainObject = (x: unknown): Record<string, unknown> =>
  x && typeof x === "object" && !Array.isArray(x) ? (x as Record<string, unknown>) : {};

export const parseBackup = (raw: unknown): Db => {
  const data: unknown = typeof raw === "string" ? JSON.parse(raw.replace(/^﻿/, "").trim()) : raw;
  if (!data || typeof data !== "object") throw new Error("To nie jest obiekt JSON.");
  const obj = data as Record<string, unknown>;
  const list = Array.isArray(data) ? data : obj.entries;
  if (!Array.isArray(list)) throw new Error("Brak tablicy entries.");
  const entries: Entry[] = [];
  for (const e of list as Record<string, unknown>[]) {
    if (!e || e.date == null || e.type == null) continue;
    const date = normDate(e.date);
    const type = String(e.type).trim();
    if (!date || !isIsoDate(date) || !type) continue;
    const row: Entry = { type, date };
    if (e.id != null) row.id = e.id as string | number;
    if (e.value != null) row.value = e.value as number | string;
    if (e.text != null) row.text = String(e.text);
    if (e.result != null) row.result = String(e.result);
    if (e.limits) row.limits = true;
    entries.push(row);
  }
  if (!entries.length) throw new Error("Zero poprawnych wpisów.");
  return { entries, cycles: plainObject(obj.cycles), settings: plainObject(obj.settings) as Settings };
};

const entryKey = (e: Entry): string => [e.type, e.date, e.value ?? "", e.text ?? "", e.result ?? ""].join("|");

export const mergeDb = (a: Db, b: Db): Db & { added: number } => {
  const seen = new Set(a.entries.map(entryKey));
  const add = b.entries.filter((e) => !seen.has(entryKey(e)));
  return {
    entries: [...a.entries, ...add],
    cycles: { ...b.cycles, ...a.cycles },
    settings: { ...b.settings, ...a.settings },
    added: add.length,
  };
};

export interface DayFlags {
  start: boolean; end: boolean; sex: boolean; prot: boolean; solo: boolean; soloType: "him" | "masturb";
  spot: boolean; clot: boolean; lh: boolean; flow: number; pain: number; limits: boolean;
  test: string; note: string; pill: string;
}

export const flagsFor = (entries: Entry[], date: string): DayFlags => {
  const f: DayFlags = {
    start: false, end: false, sex: false, prot: false, solo: false, soloType: "him", spot: false, clot: false,
    lh: false, flow: 0, pain: 0, limits: false, test: "", note: "", pill: "",
  };
  for (const e of entries) {
    if (e.date !== date) continue;
    switch (e.type) {
      case T.START: f.start = true; break;
      case T.END: f.end = true; break;
      case T.SEX: f.sex = true; break;
      case T.PROT: f.prot = true; break;
      case T.SOLO: f.solo = true; f.soloType = "masturb"; break;
      case T.HIM: f.solo = true; f.soloType = "him"; break;
      case "spotting": f.spot = true; break;
      case "clot": f.clot = true; break;
      case T.LH: f.lh = e.result !== "neg"; break;
      case "flow": f.flow = Number(e.value) || 0; break;
      case "pain": f.pain = Number(e.value) || 0; f.limits = Boolean(e.limits); break;
      case "preg_test": f.test = e.result ?? ""; break;
      case "note": f.note = e.text ?? ""; break;
      case "painkiller": f.pill = e.text ?? ""; break;
    }
  }
  return f;
};

const hasAny = (entries: Entry[], date: string, types: string[]): boolean =>
  entries.some((e) => e.date === date && types.includes(e.type));

/** Czysta funkcja: przełącza jeden znacznik dnia (szybkie kafelki). */
export const toggleEntry = (entries: Entry[], date: string, type: string): Entry[] => {
  const group = type === T.HIM ? [T.HIM, T.SOLO] : [type];
  if (hasAny(entries, date, group)) {
    let out = entries.filter((e) => !(e.date === date && group.includes(e.type)));
    if (type === T.SEX) out = out.filter((e) => !(e.date === date && e.type === T.PROT));
    return out;
  }
  const out: Entry[] = [...entries, type === T.LH ? { type, date, result: "pos" } : { type, date }];
  if (type === T.PROT && !hasAny(entries, date, [T.SEX])) out.push({ type: T.SEX, date });
  return out;
};

/** Czysta funkcja: zapis pełnego formularza dnia. Zostawia wpisy nieznanych typów. */
export const saveDay = (entries: Entry[], date: string, form: DayForm): Entry[] => {
  const keep = entries.filter((e) => !(e.date === date && MANAGED.includes(e.type)));
  const on = new Set(form.toggles);
  for (const t of on) {
    if (t === T.HIM) keep.push({ type: form.soloType, date });
    else if (t === T.LH) keep.push({ type: T.LH, date, result: "pos" });
    else keep.push({ type: t, date });
  }
  if (on.has(T.PROT) && !on.has(T.SEX)) keep.push({ type: T.SEX, date });
  if (form.flow > 0) keep.push({ type: "flow", date, value: form.flow });
  if (form.pain > 0) keep.push({ type: "pain", date, value: form.pain, limits: form.limits });
  if (form.pill.trim()) keep.push({ type: "painkiller", date, text: form.pill.trim() });
  if (form.test) keep.push({ type: "preg_test", date, result: form.test });
  if (form.note.trim()) keep.push({ type: "note", date, text: form.note.trim() });
  return keep;
};

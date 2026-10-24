import type { Db, Entry } from "./types.ts";
import { emptyDb, parseBackup } from "./model.ts";

export interface StorageLike {
  getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void;
}
export const KEY = "luna_db_v6";     // ten sam klucz co w poprzedniej wersji: dane się przenoszą
export const PREV = "luna_db_v6_prev";
export const THEME = "cykl_theme";
export type Theme = "auto" | "light" | "dark";

export const loadDb = (s: StorageLike): Db => {
  try { const raw = s.getItem(KEY); return raw ? parseBackup(raw) : emptyDb(); } catch { return emptyDb(); }
};
export const saveDb = (s: StorageLike, db: Db): boolean => {
  try { s.setItem(KEY, JSON.stringify(db)); return true; } catch { return false; }
};
export const keepPrevious = (s: StorageLike, db: Db): void => {
  try { s.setItem(PREV, JSON.stringify(db)); } catch { /* pamięć pełna: trudno */ }
};
export const loadTheme = (s: StorageLike): Theme => {
  try { const t = s.getItem(THEME); return t === "light" || t === "dark" ? t : "auto"; } catch { return "auto"; }
};
export const saveTheme = (s: StorageLike, t: Theme): void => {
  try { if (t === "auto") s.removeItem(THEME); else s.setItem(THEME, t); } catch { /* ignoruj */ }
};
export const nextTheme = (t: Theme): Theme => (t === "auto" ? "light" : t === "light" ? "dark" : "auto");

/** Historia zmian do "Cofnij" (ostatnie 20 stanów listy wpisów). */
export class UndoStack {
  private stack: Entry[][] = [];
  push(entries: Entry[]): void { this.stack.push(entries); if (this.stack.length > 20) this.stack.shift(); }
  pop(): Entry[] | undefined { return this.stack.pop(); }
  get size(): number { return this.stack.length; }
}

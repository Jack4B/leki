export type AgeBand = "18-25" | "26-41" | "42-45";
export interface Entry {
  type: string;
  date: string;
  id?: string | number;
  value?: number | string;
  text?: string;
  result?: string;
  limits?: boolean;
}
export interface Health { conditions?: string; meds?: string; tsh?: string; labs?: string }
export interface Settings {
  ageBand?: AgeBand;
  lutealDays?: number;
  averageCycleLength?: number;
  lastExport?: string;
  health?: Health;
}
export interface Db { entries: Entry[]; cycles: Record<string, unknown>; settings: Settings }
export interface Episode {
  start: string; end: string | null; next: string | null; last: string;
  days: number | null; crossesMonth: boolean;
}
export interface Cycle { start: string; end: string; days: number; bleed: number | null }
export interface History {
  starts: string[]; ends: string[]; eps: Episode[]; cycles: Cycle[];
  skipped: { start: string; end: string; days: number }[];
  bleed: Set<string>; bleedLen: number; lastStart: string | null;
}
export type Pmf = Map<number, number>;
export interface DayForm {
  toggles: string[]; flow: number; pain: number; limits: boolean;
  pill: string; test: string; note: string; soloType: "him" | "masturb";
}

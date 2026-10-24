export const pad = (n: number): string => String(n).padStart(2, "0");
export const iso = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
export const todayIso = (): string => iso(new Date());
const at = (s: string): Date => new Date(`${s}T12:00:00`);
export const addDays = (s: string, n: number): string => {
  const d = at(s);
  d.setDate(d.getDate() + n);
  return iso(d);
};
export const diffDays = (a: string, b: string): number => Math.round((at(b).getTime() - at(a).getTime()) / 864e5);
/** Prawdziwa data kalendarzowa: odrzuca miesiąc 13 i 30 lutego (tekst musi wrócić z Date bez zmian). */
export const isIsoDate = (s: string): boolean => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(at(s).getTime()) && iso(at(s)) === s;
/** Polska liczba mnoga: 1 wpis, 2 wpisy, 5 wpisów. */
export const plN = (n: number, one: string, few: string, many: string): string =>
  n === 1 ? one : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? few : many;

export const mean = (a: number[]): number => a.reduce((x, y) => x + y, 0) / a.length;
export const median = (a: number[]): number => {
  const s = [...a].sort((x, y) => x - y);
  const m = s.length >> 1;
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
};
export const stdev = (a: number[]): number => {
  const m = mean(a);
  return Math.sqrt(mean(a.map((x) => (x - m) ** 2)));
};
/** Theil–Sen: mediana nachyleń par punktów; odporna na wartości odstające. */
export const theilSen = (y: number[]): { slope: number; intercept: number } => {
  if (y.length < 2) return { slope: 0, intercept: y[0] ?? 0 };
  const slopes: number[] = [];
  for (let i = 0; i < y.length; i++) for (let j = i + 1; j < y.length; j++) slopes.push((y[j]! - y[i]!) / (j - i));
  const slope = median(slopes);
  const intercept = median(y.map((v, i) => v - slope * i));
  return { slope, intercept };
};

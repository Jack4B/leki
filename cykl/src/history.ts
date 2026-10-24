import type { Cycle, Entry, Episode, History } from "./types.ts";
import { addDays, diffDays, mean } from "./dates.ts";
import { T } from "./model.ts";

const uniqueDates = (entries: Entry[], type: string): string[] =>
  [...new Set(entries.filter((e) => e.type === type).map((e) => e.date))].sort();

/** Koniec okresu należy do startu tylko, gdy wypada przed następnym startem. */
const endFor = (start: string, next: string | null, ends: string[]): string | null =>
  ends.find((d) => d >= start && (!next || d < next)) ?? null;

/** Najkrótszy możliwy cykl: dwa „starty” bliżej niż 15 dni to zwykle podwójny wpis (liczony jest pierwszy). */
export const MIN_CYCLE = 15;

export const buildHistory = (entries: Entry[]): History => {
  const all = uniqueDates(entries, T.START);
  const starts: string[] = [];
  for (const d of all) { const prev = starts.at(-1); if (prev === undefined || diffDays(prev, d) >= MIN_CYCLE) starts.push(d); }
  const ends = uniqueDates(entries, T.END);
  const eps: Episode[] = starts.map((start, i) => {
    const next = starts[i + 1] ?? null;
    const end = endFor(start, next, ends);
    return { start, end, next, last: end ?? start, days: end ? diffDays(start, end) + 1 : null, crossesMonth: false };
  });
  const known = eps.flatMap((e) => (e.days ? [e.days] : []));
  const bleedLen = known.length ? Math.min(8, Math.max(3, Math.round(mean(known)))) : 5;
  const bleed = new Set<string>();
  for (const ep of eps) {
    let last = ep.end ?? addDays(ep.start, bleedLen - 1);
    if (ep.next && last >= ep.next) last = addDays(ep.next, -1);
    ep.last = last;
    ep.crossesMonth = ep.start.slice(0, 7) !== last.slice(0, 7);
    for (let d = ep.start; d <= last; d = addDays(d, 1)) bleed.add(d);
  }
  const cycles: Cycle[] = [];
  const skipped: History["skipped"] = [];
  starts.slice(1).forEach((end, i) => {
    const start = starts[i]!;
    const days = diffDays(start, end);
    if (days >= MIN_CYCLE && days <= 90) cycles.push({ start, end, days, bleed: eps[i]!.days });
    else if (days < MIN_CYCLE) skipped.push({ start, end, days });
  });
  return { starts, ends, eps, cycles, skipped, bleed, bleedLen, lastStart: starts.at(-1) ?? null };
};

import type { Cycle } from "./types.ts";
import { html, raw } from "./html.ts";
import type { SafeHtml } from "./html.ts";
import { addDays, theilSen } from "./dates.ts";
import { MAX_FERTILITY } from "./predict.ts";
import type { Analysis } from "./analysis.ts";
import type { LaneRow, MonthRow } from "./report.ts";


/** Przesuwny wykres długości cykli: pasmo normy 24–38, średnia krocząca, trend Theil–Sen. */
export const cycleChart = (cycles: Cycle[], offset: number, selected: number | null): SafeHtml => {
  const bw = 44, left = 30, H = 170, top = 16, bottom = 26;
  const n = cycles.length;
  if (!n) return html`<p class="mute">Za mało pełnych cykli.</p>`;
  const W = left + n * bw + 8;
  const mx = Math.max(52, ...cycles.map((c) => c.days + 4));
  const y = (v: number): number => top + (H - top - bottom) * (1 - v / mx);
  const lens = cycles.map((c) => c.days);
  const ts = theilSen(lens);
  const bars = cycles.map((c, i) => {
    const x = left + i * bw;
    const bad = c.days < 24 || c.days > 38;
    const idx = offset + i;
    return html`<g class="hit" data-act="cyc" data-i="${idx}" role="button" tabindex="0" aria-label="Cykl ${c.start}, ${c.days} dni">
      <rect x="${x}" y="0" width="${bw}" height="${H}" class="${idx === selected ? "sel" : "nohit"}"/>
      <rect x="${x + 6}" y="${y(c.days)}" width="${bw - 12}" height="${y(0) - y(c.days)}" rx="5" class="bar${bad ? " bad" : ""}"/>
      <text x="${x + bw / 2}" y="${y(c.days) - 4}" text-anchor="middle" class="val">${c.days}</text>
      <text x="${x + bw / 2}" y="${H - 8}" text-anchor="middle" class="ax">${c.start.slice(5, 7)}/${c.start.slice(2, 4)}</text></g>`;
  });
  const ma = lens.map((_, i) => {
    const w = lens.slice(Math.max(0, i - 2), i + 1);
    return `${left + i * bw + bw / 2},${y(w.reduce((a, b) => a + b, 0) / w.length)}`;
  }).join(" ");
  const grid = [0, 14, 28, 42].map((v) => html`<text x="0" y="${y(v) + 4}" class="ax">${v}</text>`);
  return html`<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="Długości cykli, wykres przewijany w poziomie">
    <rect x="${left}" y="${y(38)}" width="${W - left}" height="${y(24) - y(38)}" class="band"/>
    ${grid}${bars}
    <polyline points="${ma}" class="ma" fill="none"/>
    ${n >= 4 ? raw(`<line x1="${left + bw / 2}" y1="${y(ts.intercept)}" x2="${left + (n - 1) * bw + bw / 2}" y2="${y(ts.intercept + ts.slope * (n - 1))}" class="trendline"/>`) : ""}
  </svg>`;
};

/** Oś cykli: każdy cykl jako rząd (okres, okno płodne wstecz, stosunki). */
export const lanes = (rows: LaneRow[]): SafeHtml => {
  const W = 300, maxLen = Math.max(50, ...rows.map((r) => r.length + 6)), sc = W / maxLen;
  const out = [...rows].reverse().map((r) => {
    const dots = r.events.map((e) => html`<circle cx="${(e.day + 0.5) * sc}" cy="10" r="3.6" class="${e.prot ? "ev prot" : "ev"}"/>`);
    const win = r.win ? html`<rect x="${r.win[0] * sc}" y="7" width="${(r.win[1] - r.win[0] + 1) * sc}" height="6" class="winb"/>` : "";
    return html`<div class="lane"><span class="lane-l">${r.start.slice(5, 7)}/${r.start.slice(2, 4)}</span>
      <svg viewBox="0 0 ${W} 20" width="100%" height="20" role="img" aria-label="Cykl od ${r.start}, ${r.length} dni">
        <rect x="0" y="7" width="${r.length * sc}" height="6" rx="3" class="track"/>
        <rect x="0" y="7" width="${r.bleed * sc}" height="6" rx="3" class="bleedb"/>${win}${dots}
        ${r.open ? raw(`<text x="${r.length * sc + 4}" y="14" class="ax">trwa</text>`) : ""}</svg></div>`;
  });
  return html`${out}`;
};

/** Pasek na najbliższe dni: wysokość = względna szansa poczęcia, kolor = poziom okna płodnego. */
export const strip = (a: Analysis, days = 21): SafeHtml => {
  const bars = Array.from({ length: days }, (_, i) => {
    const d = addDays(a.today, i);
    const inf = a.info(d);
    const h = Math.max(4, Math.min(1, inf.fertility / MAX_FERTILITY) * 56);
    const lv = inf.bleed ? "bleed" : inf.level;
    return html`<button class="sb" data-act="day" data-date="${d}" aria-label="${d}, szansa: ${lv}">
      <span class="sbar-wrap"><span class="sbar lv-${lv}" style="height:${h}px"></span></span>
      <span class="sl">${i === 0 ? "dziś" : d.slice(8)}</span></button>`;
  });
  return html`<div class="strip">${bars}</div>`;
};

/** Kółko cyklu: okres, dni z podwyższoną szansą, znacznik dzisiaj. */
export const ring = (a: Analysis): SafeHtml => {
  const last = a.hist.lastStart;
  if (!last) return html`<svg viewBox="0 0 220 220" aria-hidden="true"><circle cx="110" cy="110" r="80" class="rtrack"/></svg>`;
  const L = Math.max(a.pred.lengthMedian, a.cycleDay, 20), r = 80, C = 2 * Math.PI * r, per = C / L;
  const arcs: SafeHtml[] = [html`<circle cx="110" cy="110" r="${r}" class="rtrack"/>`];
  for (let d = 0; d < L; d++) {
    const inf = a.info(addDays(last, d));
    const cls = inf.bleed ? "rbleed" : inf.level === "high" ? "rhigh" : inf.level === "mid" ? "rmid" : "";
    if (cls) arcs.push(html`<circle cx="110" cy="110" r="${r}" class="${cls}" stroke-dasharray="${per} ${C}" stroke-dashoffset="${-d * per}" transform="rotate(-90 110 110)"/>`);
  }
  const ang = ((a.cycleDay - 0.5) / L) * 2 * Math.PI - Math.PI / 2;
  arcs.push(html`<circle cx="${110 + r * Math.cos(ang)}" cy="${110 + r * Math.sin(ang)}" r="11" class="rnow"/>`);
  return html`<svg viewBox="0 0 220 220" aria-hidden="true">${arcs}</svg>`;
};

/** Przesuwny wykres miesięczny: stosunki i solo. */
export const monthChart = (rows: MonthRow[]): SafeHtml => {
  if (!rows.length) return html`<p class="mute">Brak danych.</p>`;
  const bw = 46, left = 4, H = 130, top = 14, bottom = 22, W = left + rows.length * bw + 4;
  const mx = Math.max(3, ...rows.map((r) => Math.max(r.sex, r.solo)));
  const y = (v: number): number => top + (H - top - bottom) * (1 - v / mx);
  const g = rows.map((r, i) => {
    const x = left + i * bw;
    return html`<g><rect x="${x + 5}" y="${y(r.sex)}" width="16" height="${y(0) - y(r.sex)}" rx="3" class="mb-sex"/>
      <rect x="${x + 24}" y="${y(r.solo)}" width="16" height="${y(0) - y(r.solo)}" rx="3" class="mb-solo"/>
      ${r.sex ? raw(`<text x="${x + 13}" y="${y(r.sex) - 3}" text-anchor="middle" class="val">${r.sex}</text>`) : ""}
      ${r.solo ? raw(`<text x="${x + 32}" y="${y(r.solo) - 3}" text-anchor="middle" class="val">${r.solo}</text>`) : ""}
      <text x="${x + 23}" y="${H - 6}" text-anchor="middle" class="ax">${r.month.slice(5)}/${r.month.slice(2, 4)}</text></g>`;
  });
  return html`<svg width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="Stosunki i solo wg miesięcy">${g}</svg>`;
};

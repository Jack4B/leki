/** Bezpieczne szablony: wszystko, co nie jest SafeHtml, jest escapowane. */
export class SafeHtml {
  value: string;
  constructor(value: string) { this.value = value; }
  toString(): string { return this.value; }
}
export const esc = (s: unknown): string =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
export const raw = (s: string): SafeHtml => new SafeHtml(s);
const part = (v: unknown): string => {
  if (v == null || v === false) return "";
  if (v instanceof SafeHtml) return v.value;
  if (Array.isArray(v)) return v.map(part).join("");
  return esc(v);
};
export const html = (strings: TemplateStringsArray, ...vals: unknown[]): SafeHtml => {
  let out = strings[0] ?? "";
  vals.forEach((v, i) => { out += part(v) + (strings[i + 1] ?? ""); });
  return new SafeHtml(out);
};

// Smoke test gotowego dist/cykl.html: uruchamia zbudowany JS na atrapie DOM i klika po widokach.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const page = readFileSync(new URL("../dist/cykl.html", import.meta.url), "utf8");
const scripts = [...page.matchAll(/<script>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
const js = scripts.at(-1);

const mkEl = (id) => {
  const el = { id, innerHTML: "", textContent: "", hidden: false, className: "", scrollLeft: 0, scrollWidth: 999, dataset: {}, attrs: {},
    classList: { toggle() { return true; }, add() {}, remove() {}, contains() { return false; } },
    setAttribute(k, v) { el.attrs[k] = v; }, removeAttribute(k) { delete el.attrs[k]; }, getAttribute: (k) => el.attrs[k],
    appendChild() {}, querySelector() { return null; }, querySelectorAll() { return []; }, select() {} };
  return el;
};
const setup = (stored = {}) => {
  const els = {}, handlers = {}, store = { ...stored };
  const doc = {
    getElementById: (id) => (els[id] ??= mkEl(id)),
    querySelectorAll: () => [], createElement: () => mkEl("x"),
    addEventListener: (t, f) => { (handlers[t] ??= []).push(f); },
    documentElement: mkEl("html"),
  };
  const ctx = {
    document: doc, console, Blob: class {}, URL: { createObjectURL: () => "", revokeObjectURL() {} }, FileReader: class {}, MouseEvent: class {},
    getComputedStyle: () => ({ getPropertyValue: () => " #f6f1ea " }), setTimeout, clearTimeout,
    navigator: { storage: { persist: async () => true }, clipboard: { writeText: async () => {} } },
    window: { scrollTo() {}, setTimeout, addEventListener: (t, f) => { (handlers[t] ??= []).push(f); }, parent: undefined, localStorage: { getItem: (k) => store[k] ?? null, setItem: (k, v) => { store[k] = String(v); }, removeItem: (k) => { delete store[k]; } } },
  };
  ctx.window.document = doc;
  ctx.window.parent = ctx.window;
  vm.createContext(ctx);
  vm.runInContext(js, ctx);
  const click = (data) => {
    const el = { ...mkEl("t"), dataset: data, classList: { toggle: () => true, contains: () => false, add() {}, remove() {} } };
    const target = { closest: (sel) => (sel === "[data-act]" ? el : null) };
    for (const f of handlers.click) f({ target });
  };
  return { els, store, doc, click, handlers, win: ctx.window, view: () => els.app.innerHTML };
};

const backup = JSON.stringify({ entries: [
  { type: "period_start", date: "2031-08-29" }, { type: "period_end", date: "2031-09-03" },
  { type: "period_start", date: "2031-10-02" }, { type: "period_end", date: "2031-10-07" },
  { type: "period_start", date: "2030-12-01" }, { type: "period_start", date: "2031-01-02" }, { type: "period_start", date: "2031-02-01" },
], cycles: {}, settings: {} });

test("bundle startuje na danych z poprzedniej wersji i renderuje Dziś", () => {
  const a = setup({ luna_db_v6: backup });
  assert.match(a.view(), /Najbliższe 21 dni/);
});
test("bundle startuje na pustym magazynie", () => {
  assert.match(setup().view(), /Zacznij od startu okresu/);
});
test("zmiana zakładek renderuje właściwe widoki", () => {
  const a = setup({ luna_db_v6: backup });
  a.click({ act: "tab", tab: "wyk" }); assert.match(a.view(), /Długość cykli/);
  a.click({ act: "tab", tab: "kal" }); assert.match(a.view(), /class="cal"/);
  a.click({ act: "tab", tab: "dane" }); assert.match(a.view(), /Kopia/);
});
test("motyw: cykl auto → jasny → ciemny zapisuje wybór i ustawia data-theme", () => {
  const a = setup({ luna_db_v6: backup });
  a.click({ act: "theme-cycle" });
  assert.equal(a.store.cykl_theme, "light"); assert.equal(a.doc.documentElement.attrs["data-theme"], "light");
  a.click({ act: "theme-cycle" });
  assert.equal(a.store.cykl_theme, "dark"); assert.equal(a.doc.documentElement.attrs["data-theme"], "dark");
  a.click({ act: "theme-cycle" });
  assert.equal(a.store.cykl_theme, undefined); assert.equal(a.doc.documentElement.attrs["data-theme"], undefined);
});
test("szybki zapis zapisuje w magazynie, cofnij przywraca", () => {
  const a = setup({ luna_db_v6: backup });
  const n = () => JSON.parse(a.store.luna_db_v6).entries.length;
  const before = n();
  a.click({ act: "flip", type: "sex" });
  assert.equal(n(), before + 1);
  a.click({ act: "undo" });
  assert.equal(n(), before);
});

test("osadzenie: motyw przyjmowany tylko od rodzica i tylko poprawne wartości", () => {
  const a = setup({ luna_db_v6: backup });
  a.send = a.send;
  const fire = (source, data) => { for (const f of (a.handlers.message ?? [])) f({ source, data }); };
  fire({}, { type: "cykl-theme", theme: "dark" });
  assert.equal(a.store.cykl_theme, undefined, "obcy nadawca ignorowany");
  fire(a.win.parent, { type: "cykl-theme", theme: "dark" });
  assert.equal(a.store.cykl_theme, "dark");
  fire(a.win.parent, { type: "cykl-theme", theme: "<script>" });
  assert.equal(a.store.cykl_theme, "dark", "nieprawidłowa wartość ignorowana");
});

import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { addDays, diffDays, median, theilSen, isIsoDate, plN } from "../src/dates.ts";
import { parseBackup, mergeDb, toggleEntry, saveDay, flagsFor, emptyDb } from "../src/model.ts";
import { buildHistory } from "../src/history.ts";
import { makeDb, PERI } from "./helpers.ts";
import type { DayForm } from "../src/types.ts";

describe("dates", () => {
  test("addDays przez granice miesiąca, roku i zmianę czasu", () => {
    assert.equal(addDays("2031-01-31", 1), "2031-02-01");
    assert.equal(addDays("2030-12-31", 1), "2031-01-01");
    assert.equal(addDays("2031-03-28", 2), "2031-03-30");
    assert.equal(addDays("2031-10-24", 2), "2031-10-26");
    assert.equal(diffDays("2031-10-19", "2031-10-24"), 5);
  });
  test("isIsoDate odrzuca nieistniejące daty", () => {
    assert.ok(isIsoDate("2031-02-28"));
    assert.ok(!isIsoDate("2031-13-01"));
    assert.ok(!isIsoDate("28.02.2026"));
  });
  test("mediana i Theil–Sen (odporny na odstającą wartość)", () => {
    assert.equal(median([3, 1, 2]), 2);
    assert.equal(median([1, 2, 3, 4]), 2.5);
    const t = theilSen([10, 12, 14, 16, 18]);
    assert.ok(Math.abs(t.slope - 2) < 1e-9);
    const noisy = theilSen([10, 12, 14, 16, 18, 20, 22, 100]);
    assert.ok(Math.abs(noisy.slope - 2) < 1.5, `slope ${noisy.slope}`);
  });
});

describe("parseBackup / merge", () => {
  test("odrzuca daty spoza kalendarza (miesiąc 13, 30 lutego), reszta zostaje", () => {
    const db = parseBackup({ entries: [{ type: "period_start", date: "2031-13-45" }, { type: "period_start", date: "2031-02-30" }, { type: "period_start", date: "2031-02-28" }] });
    assert.deepEqual(db.entries, [{ type: "period_start", date: "2031-02-28" }]);
    assert.throws(() => parseBackup({ entries: [{ type: "period_start", date: "2031-13-45" }] }), /Zero/);
    assert.ok(!isIsoDate("2031-13-45") && !isIsoDate("2031-02-30") && isIsoDate("2032-02-29") && !isIsoDate("2031-02-29"));
  });
  test("liczba mnoga wpisów", () => {
    assert.deepEqual([0, 1, 2, 4, 5, 12, 22, 25, 112].map((n) => plN(n, "wpis", "wpisy", "wpisów")), ["wpisów", "wpis", "wpisy", "wpisy", "wpisów", "wpisów", "wpisy", "wpisów", "wpisów"]);
  });
  test("akceptuje BOM i daty dd.mm.rrrr, odrzuca śmieci", () => {
    const db = parseBackup("﻿" + JSON.stringify({ entries: [{ type: "sex", date: "05.03.2031" }, { type: "x" }, null] }));
    assert.deepEqual(db.entries, [{ type: "sex", date: "2031-03-05" }]);
  });
  test("błędy: brak entries, zero wpisów", () => {
    assert.throws(() => parseBackup({ foo: 1 }), /entries/);
    assert.throws(() => parseBackup({ entries: [{ type: "sex" }] }), /Zero/);
  });
  test("merge jest idempotentny i dodaje tylko nowe", () => {
    const a = parseBackup({ entries: [{ type: "sex", date: "2031-01-01" }] });
    const b = parseBackup({ entries: [{ type: "sex", date: "2031-01-01" }, { type: "him", date: "2031-01-02" }] });
    const m = mergeDb(a, b);
    assert.equal(m.added, 1);
    assert.equal(mergeDb(m, b).added, 0);
  });
});

describe("edycja dnia", () => {
  test("zabezpieczenie dodaje stosunek, usunięcie stosunku usuwa zabezpieczenie", () => {
    let e = toggleEntry([], "2031-05-01", "protected");
    assert.deepEqual(e.map((x) => x.type).sort(), ["protected", "sex"]);
    e = toggleEntry(e, "2031-05-01", "sex");
    assert.equal(e.length, 0);
  });
  test("solo przełącza oba typy (him i masturb)", () => {
    const e = toggleEntry([{ type: "masturb", date: "2031-05-01" }], "2031-05-01", "him");
    assert.equal(e.length, 0);
  });
  test("zapis formularza zachowuje typ solo z importu i nieznane wpisy", () => {
    const base = [{ type: "masturb", date: "2031-05-01" }, { type: "custom_thing", date: "2031-05-01" }];
    const form: DayForm = { toggles: ["him", "spotting"], flow: 0, pain: 0, limits: false, pill: "", test: "", note: "", soloType: "masturb" };
    const out = saveDay(base, "2031-05-01", form);
    assert.ok(out.some((e) => e.type === "masturb"));
    assert.ok(out.some((e) => e.type === "custom_thing"));
    assert.ok(out.some((e) => e.type === "spotting"));
    assert.ok(!out.some((e) => e.type === "him"));
  });
  test("flagsFor odczytuje ból z limitem i notatkę", () => {
    const f = flagsFor([{ type: "pain", date: "d", value: 7, limits: true }, { type: "note", date: "d", text: "x" }], "d");
    assert.equal(f.pain, 7); assert.ok(f.limits); assert.equal(f.note, "x");
  });
  test("pusta baza", () => assert.equal(emptyDb().entries.length, 0));
});

describe("historia cykli", () => {
  test("długości cykli = odstępy między startami", () => {
    const h = buildHistory(makeDb("2030-09-14", PERI).entries);
    assert.deepEqual(h.cycles.map((c) => c.days), PERI);
    assert.equal(h.bleedLen, 5);
  });
  test("starty bliżej niż 15 dni to podwójny wpis: liczy się pierwszy", () => {
    const e = makeDb("2031-01-01", [28]).entries;
    e.push({ type: "period_start", date: "2031-01-04" });
    const h = buildHistory(e);
    assert.deepEqual(h.starts, ["2031-01-01", "2031-01-29"]);
    assert.deepEqual(h.cycles.map((c) => c.days), [28]);
  });
  test("późny koniec okresu nie maluje wstecz poprzednich miesięcy", () => {
    const h = buildHistory([
      { type: "period_start", date: "2031-08-29" }, { type: "period_end", date: "2031-09-03" },
      { type: "period_start", date: "2031-07-26" },
    ]);
    assert.ok(h.bleed.has("2031-09-03"));
    assert.ok(!h.bleed.has("2031-08-18"));
    assert.equal(h.eps.find((x) => x.start === "2031-07-26")!.end, null);
  });
  test("okres przez granicę miesiąca jest jednym odcinkiem", () => {
    const h = buildHistory([{ type: "period_start", date: "2031-08-29" }, { type: "period_end", date: "2031-09-03" }]);
    assert.equal(h.eps[0]!.days, 6);
    assert.ok(h.eps[0]!.crossesMonth);
  });
});

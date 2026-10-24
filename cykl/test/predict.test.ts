import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { cycleLengthPmf, lutealPmf, convolve, pmfQuantile, predict, levelOf, FERTILITY_BY_OFFSET } from "../src/predict.ts";
import { buildHistory } from "../src/history.ts";
import { analyze } from "../src/analysis.ts";
import { addDays } from "../src/dates.ts";
import { makeDb, PERI } from "./helpers.ts";

const sum = (m: Map<number, number>) => [...m.values()].reduce((a, b) => a + b, 0);

describe("rozkłady", () => {
  test("pmf sumuje się do 1 (także po splocie)", () => {
    const p = cycleLengthPmf([28, 29, 27, 30]);
    assert.ok(Math.abs(sum(p) - 1) < 1e-9);
    assert.ok(Math.abs(sum(convolve(p, lutealPmf())) - 1) < 1e-9);
  });
  test("faza lutealna 12–16 d, środek 14", () => {
    const l = lutealPmf();
    assert.deepEqual([...l.keys()].sort((a, b) => a - b), [12, 13, 14, 15, 16]);
    assert.ok(l.get(14)! > l.get(12)!);
  });
  test("mało cykli: łagodny rozkład a priori wokół 28", () => {
    const p = cycleLengthPmf([]);
    assert.ok(pmfQuantile(p, 0.5) >= 27 && pmfQuantile(p, 0.5) <= 29);
  });
  test("wagi płodności: szczyt w dniu owulacji, zero daleko przed", () => {
    assert.ok(FERTILITY_BY_OFFSET[0]! >= FERTILITY_BY_OFFSET[-5]!);
    assert.equal(FERTILITY_BY_OFFSET[-6], undefined);
  });
});

describe("prognoza dla regularnych cykli 28 d", () => {
  const db = makeDb("2031-01-01", [28, 28, 28, 28, 28, 28]);
  const last = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
  const today = addDays(last, 5);
  const a = analyze(db, today);
  test("następny okres za 28 dni, wąski przedział", () => {
    const n = a.pred.nextPeriods[0]!;
    assert.equal(n.date, addDays(last, 28));
    assert.ok(a.pred.confidence !== "low");
  });
  test("owulacja ok. dzień 14, okno z wysoką szansą", () => {
    assert.equal(a.pred.expectedOvulation, addDays(last, 14));
    assert.equal(a.info(addDays(last, 13)).level, "high");
    assert.equal(a.info(addDays(last, 3)).level, "low");
    assert.ok(a.pred.nextRun && a.pred.nextRun.from <= addDays(last, 12) && a.pred.nextRun.to >= addDays(last, 14));
  });
  test("owulacja nigdy w trakcie miesiączki", () => {
    const hist = buildHistory(db.entries);
    const p = predict(hist, db.entries, {}, today);
    assert.ok(p.expectedOvulation! > addDays(last, hist.bleedLen - 1));
  });
});

describe("prognoza dla nieregularnych cykli (przejście)", () => {
  const db = makeDb("2030-09-14", PERI);
  const a = analyze(db, "2031-10-22");
  test("pewność niska, wysoki poziom szansy nie jest nigdy obiecywany", () => {
    assert.equal(a.pred.confidence, "low");
    const levels = new Set<string>();
    for (let i = 0; i < 60; i++) levels.add(a.info(addDays("2031-10-22", i)).level);
    assert.ok(!levels.has("high"), "przy rozrzucie 27–42 dni żaden dzień nie powinien dostać 'high'");
    assert.ok(levels.has("mid"));
  });
  test("kolejne okresy rosną i przedziały się poszerzają", () => {
    const n = a.pred.nextPeriods;
    assert.ok(n[0]!.date < n[1]!.date && n[1]!.date < n[2]!.date);
    const w = (x: typeof n[number]) => Date.parse(x.to) - Date.parse(x.from);
    assert.ok(w(n[2]!) > w(n[0]!));
  });
  test("trwający okres: dzień cyklu i faza", () => {
    const during = analyze(db, "2031-10-21");
    assert.equal(during.cycleDay, 4);
    assert.equal(during.phase, "Miesiączka");
    assert.equal(a.cycleDay, 5);
  });
});

describe("aktualizacja gdy okres się spóźnia", () => {
  const db = makeDb("2031-01-01", [28, 28, 28, 28, 28]);
  const last = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
  test("po 40 dniach cyklu prognoza następnego okresu jest w przyszłości", () => {
    const today = addDays(last, 39);
    const a = analyze(db, today);
    assert.ok(a.pred.nextPeriods[0]!.date > today);
    assert.ok(a.pred.overdueDays > 0);
    assert.equal(a.phase, "Okres się spóźnia");
  });
});

describe("test owulacyjny LH", () => {
  test("dodatni LH przestawia owulację na dzień po teście i podnosi szansę", () => {
    const base = makeDb("2031-01-01", [33, 34, 31, 36, 29, 41]);
    const last = base.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const lh = addDays(last, 17);
    const withLh = { ...base, entries: [...base.entries, { type: "lh", date: lh, result: "pos" }] };
    const a = analyze(withLh, addDays(last, 18));
    assert.ok(a.pred.lhUsed);
    assert.equal(a.pred.expectedOvulation, addDays(lh, 1));
    assert.equal(a.info(addDays(lh, 1)).level, "high");
    const b = analyze(base, addDays(last, 18));
    assert.ok(a.info(addDays(lh, 1)).fertility > b.info(addDays(lh, 1)).fertility);
  });
  test("ujemny LH nie jest traktowany jak dodatni", () => {
    const base = makeDb("2031-01-01", [28, 28, 28, 28]);
    const last = base.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const a = analyze({ ...base, entries: [...base.entries, { type: "lh", date: addDays(last, 12), result: "neg" }] }, addDays(last, 13));
    assert.ok(!a.pred.lhUsed);
  });
});

test("poziomy: progi", () => {
  assert.equal(levelOf(0.2), "high");
  assert.equal(levelOf(0.06), "mid");
  assert.equal(levelOf(0.01), "low");
});

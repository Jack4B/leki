import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { cycleLengthPmf, lutealPmf, convolve, pmfQuantile, predict, windowLevel, calendarRule, profileOf, FERTILITY_BY_OFFSET } from "../src/predict.ts";
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
  test("mało cykli: łagodny, szeroki rozkład a priori wokół 29", () => {
    const p = cycleLengthPmf([]);
    assert.ok(pmfQuantile(p, 0.5) >= 28 && pmfQuantile(p, 0.5) <= 30);
    assert.ok(pmfQuantile(p, 0.9) - pmfQuantile(p, 0.1) >= 6, "prior ma być szeroki (rozrzut między osobami)");
  });
  test("po 45. roku życia rozkłady są szersze", () => {
    const l = [28, 28, 28, 28, 28, 28];
    const w = (b: "26-41" | "46-50" | "51+") => { const p = cycleLengthPmf(l, 28, profileOf(b)); return pmfQuantile(p, 0.9) - pmfQuantile(p, 0.1); };
    assert.ok(w("46-50") > w("26-41") && w("51+") >= w("46-50"));
    assert.ok([...lutealPmf(14, 3).keys()].length > [...lutealPmf(14, 2).keys()].length);
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
  test("po 40 dniach cyklu historia nie ma tak długich cykli: brak prognozy, bez „podwyższonej szansy” co dzień", () => {
    const today = addDays(last, 39);
    const a = analyze(db, today);
    assert.ok(a.pred.noForecast);
    assert.equal(a.pred.nextPeriods.length, 0);
    assert.equal(a.pred.expectedOvulation, null);
    assert.ok(a.pred.overdueDays > 0);
    assert.equal(a.phase, "Okres się spóźnia");
    assert.equal(a.info(addDays(today, 3)).level, "low");
  });
  test("spóźnienie liczy się od końca własnego zakresu, nie od mediany", () => {
    const irregular = makeDb("2031-01-01", [24, 41, 30, 33, 28, 38]);
    const l = irregular.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const a = analyze(irregular, addDays(l, 34));       // dzień 35: w środku zakresu tej osoby
    assert.equal(a.pred.overdueDays, 0, "dzień 35 przy cyklach 24–41 nie jest spóźnieniem");
    assert.ok(a.phase !== "Okres się spóźnia");
  });
  test("okres może zacząć się dziś: prognoza obejmuje dzisiejszy dzień", () => {
    const l = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const a = analyze(db, addDays(l, 28));               // dzień 29, typowa długość 28
    assert.equal(a.pred.nextPeriods[0]!.date <= addDays(l, 29), true);
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

describe("test LH: wiarygodność i wpływ na prognozę okresu", () => {
  const base = () => makeDb("2031-01-01", [28, 28, 28, 28, 28, 28]);
  const lastOf = (db: ReturnType<typeof base>) => db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
  test("zabłąkany dodatni test w trakcie miesiączki nie przestawia owulacji", () => {
    const db = base(); const l = lastOf(db);
    db.entries.push({ type: "lh", date: addDays(l, 1), result: "pos" });
    const a = analyze(db, addDays(l, 10));
    assert.ok(!a.pred.lhUsed);
    assert.equal(a.pred.expectedOvulation, addDays(l, 14));
  });
  test("wcześniejszy słaby test i prawdziwa seria: liczy się początek ostatniej serii", () => {
    const db = base(); const l = lastOf(db);
    for (const d of [8, 14, 15]) db.entries.push({ type: "lh", date: addDays(l, d), result: "pos" });
    const a = analyze(db, addDays(l, 17));
    assert.ok(a.pred.lhUsed);
    assert.equal(a.pred.expectedOvulation, addDays(l, 15));
  });
  test("dodatni test w ostatnich dniach zakończonego cyklu nie cofa owulacji na koniec cyklu", () => {
    const db = base();
    const starts = db.entries.filter((e) => e.type === "period_start").map((e) => e.date);
    const c = starts.at(-2)!, end = starts.at(-1)!;
    const withBad = { ...db, entries: [...db.entries, { type: "lh", date: addDays(end, -2), result: "pos" }] };
    const x = analyze(withBad, addDays(end, 3)), y = analyze(db, addDays(end, 3));
    assert.equal(x.info(addDays(c, 13)).fertility, y.info(addDays(c, 13)).fertility);
  });
  test("LH przesuwa prognozę następnego okresu (po 28 dniach nie ma „spóźnienia”)", () => {
    const db = base(); const l = lastOf(db);
    db.entries.push({ type: "lh", date: addDays(l, 20), result: "pos" });
    const a = analyze(db, addDays(l, 30));
    assert.ok(a.pred.nextPeriods[0]!.date >= addDays(l, 33), a.pred.nextPeriods[0]!.date);
    assert.equal(a.pred.overdueDays, 0);
  });
});

describe("pewność, poziomy i tryby prognoz", () => {
  test("„wysoka” pewność dopiero od 6 cykli; 3 równe cykle to średnia", () => {
    const p = (n: number) => analyze(makeDb("2031-01-01", Array(n).fill(28)), "2031-12-01").pred.confidence;
    assert.equal(p(3), "mid");
    assert.equal(p(6), "high");
    assert.equal(p(2), "low");
  });
  test("bez historii (tylko prior) żaden dzień nie dostaje poziomu „wysoka”", () => {
    const db = makeDb("2031-01-01", []);
    const a = analyze(db, "2031-01-05");
    for (let i = 0; i < 40; i++) assert.notEqual(a.info(addDays("2031-01-05", i)).level, "high");
  });
  test("po 45. roku życia pewność jest ograniczona, po 50. zawsze niska", () => {
    const db = makeDb("2031-01-01", Array(8).fill(28));
    db.settings.ageBand = "46-50";
    assert.equal(analyze(db, "2031-10-01").pred.confidence, "mid");
    db.settings.ageBand = "51+";
    assert.equal(analyze(db, "2031-10-01").pred.confidence, "low");
    db.settings.ageBand = "42-45";
    assert.equal(analyze(db, "2031-10-01").pred.confidence, "high");
  });
  test("starsze pasmo daje szerszy rozrzut następnego okresu i fazy lutealnej", () => {
    const db = makeDb("2031-01-01", Array(8).fill(28));
    const l = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const w = (band: "26-41" | "46-50" | "51+") => {
      const a = analyze({ ...db, settings: { ageBand: band } }, addDays(l, 5)).pred.nextPeriods[0]!;
      return Date.parse(a.to) - Date.parse(a.from);
    };
    assert.ok(w("46-50") > w("26-41") && w("51+") >= w("46-50"));
  });
  test("antykoncepcja hormonalna: okresy są prognozowane, szans poczęcia nie liczymy", () => {
    const db = makeDb("2031-01-01", Array(6).fill(28)); db.settings.mode = "hormonal";
    const a = analyze(db, "2031-07-01");
    assert.ok(a.pred.nextPeriods.length === 3);
    assert.equal(a.pred.fertility.size, 0);
    assert.equal(a.pred.expectedOvulation, null);
    assert.equal(a.pred.nextRun, null);
    for (let i = 0; i < 40; i++) assert.equal(a.info(addDays("2031-07-01", i)).level, "low");
  });
  test("ciąża / karmienie: żadnych prognoz ani spóźnień", () => {
    const db = makeDb("2031-01-01", Array(6).fill(28)); db.settings.mode = "pause";
    const a = analyze(db, "2031-12-01");
    assert.equal(a.pred.nextPeriods.length, 0);
    assert.equal(a.pred.fertility.size, 0);
    assert.equal(a.pred.overdueDays > 0 || a.phase === "Okres się spóźnia", false);
    assert.equal(a.phase, "Prognozy wyłączone");
  });
});

describe("okno płodne: poziomy z prawdopodobieństwa, że dzień jest w oknie", () => {
  const levels = (lens: number[], band?: "26-41" | "46-50" | "51+", off = 5, days = 45): string => {
    const db = makeDb("2031-01-01", lens); if (band) db.settings.ageBand = band;
    const l = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const a = analyze(db, addDays(l, off));
    return Array.from({ length: days }, (_, i) => { const x = a.info(addDays(l, i)); return x.bleed ? "B" : x.predBleed ? "p" : x.level === "high" ? "H" : x.level === "mid" ? "m" : "."; }).join("");
  };
  test("regularne cykle 28 d: okno bardzo prawdopodobne w dniach 10–16 (zgodnie z metodą kalendarzową 10–17), poza nim nic", () => {
    const s = levels([28, 28, 28, 28, 28, 28, 28]).slice(0, 28);
    const high = [...s].flatMap((c, i) => (c === "H" ? [i + 1] : []));
    assert.ok(high[0]! >= 9 && high[0]! <= 11 && high.at(-1)! >= 15 && high.at(-1)! <= 17, s);
    assert.ok(!s.slice(20, 27).includes("H") && !s.slice(20, 27).includes("m"), "faza lutealna bez oznaczeń");
  });
  test("nieregularne cykle: brak „bardzo prawdopodobnych” dni, a „możliwe” nie zalewają całego miesiąca ani cyklu po prognozowanym okresie", () => {
    for (const band of ["26-41", "46-50", "51+"] as const) {
      const s = levels(PERI, band);
      assert.ok(!s.includes("H"), `${band}: ${s}`);
      const mid = [...s].filter((c) => c === "m").length;
      assert.ok(mid >= 8 && mid <= 20, `${band}: ${mid} dni „możliwe” (${s})`);
      const afterPred = s.slice(s.indexOf("p"));
      assert.ok(!afterPred.includes("m"), `${band}: oznaczenia po prognozowanym okresie (${s})`);
    }
  });
  test("poziomy mają progi 50% i 20%", () => {
    assert.equal(windowLevel(0.5), "high"); assert.equal(windowLevel(0.49), "mid");
    assert.equal(windowLevel(0.2), "mid"); assert.equal(windowLevel(0.19), "low");
  });
  test("prawdopodobieństwo okna dla jednego cyklu nie przekracza 1 i sumuje się do ok. 6–7 dni", () => {
    const db = makeDb("2031-01-01", [28, 28, 28, 28, 28, 28, 28]);
    const l = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const a = analyze(db, addDays(l, 5));
    const first = [...a.pred.window].filter(([d]) => d >= l && d < addDays(l, 28));
    assert.ok(first.every(([, q]) => q <= 1 + 1e-9));
    const sum = first.reduce((x, [, q]) => x + q, 0);
    assert.ok(Math.abs(sum - 7) < 0.1, `suma ${sum}`);   // okno = 7 dni: owulacja −5 … +1
  });
  test("metoda kalendarzowa: najkrótszy − 18 do najdłuższy − 11, od 6 cykli", () => {
    assert.deepEqual(calendarRule([28, 28, 28, 28, 28, 31]), { from: 10, to: 20, n: 6, standardDays: true });
    assert.equal(calendarRule([28, 28, 28, 28, 28]), null);
    assert.equal(calendarRule([28, 28, 28, 28, 28, 33])!.standardDays, false);
    assert.deepEqual(calendarRule(PERI.slice(0, 7)), { from: 9, to: 31, n: 7, standardDays: false });
  });
});

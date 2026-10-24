import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { analyze } from "../src/analysis.ts";
import { trendOf, strawHint, regularityLimit } from "../src/health.ts";
import { buildHistory } from "../src/history.ts";
import { intimacyOf, lanesOf, buildBrief, monthlyOf } from "../src/report.ts";
import { makeDb, PERI } from "./helpers.ts";
import { addDays } from "../src/dates.ts";

describe("trend", () => {
  test("narastające cykle → longer, malejące → shorter, stałe → stable", () => {
    assert.equal(trendOf([28, 30, 32, 34, 36, 38]).direction, "longer");
    assert.equal(trendOf([38, 36, 34, 32, 30, 28]).direction, "shorter");
    assert.equal(trendOf([28, 28, 28, 28, 28]).direction, "stable");
    assert.equal(trendOf([28, 30]).direction, "unknown");
  });
  test("realistyczny przebieg przejścia ma dodatnie nachylenie", () => {
    const t = trendOf(PERI);
    assert.ok(t.slope > 0, `slope ${t.slope}`);
    assert.ok(t.recent3! > t.prev3!);
  });
});

describe("STRAW+10", () => {
  test("dwa skoki ≥7 d w 10 cyklach → wskazówka wczesnej fazy", () => {
    const h = buildHistory(makeDb("2030-09-14", PERI).entries);
    const s = strawHint(h, "2031-10-22");
    assert.equal(s.stage, "early-hint");
    assert.equal(s.bigJumps.length, 2);
  });
  test("regularne cykle → none", () => {
    const h = buildHistory(makeDb("2031-01-01", [28, 29, 28, 27, 28]).entries);
    assert.equal(strawHint(h, "2031-06-01").stage, "none");
  });
  test("przerwa ≥60 d → późna faza", () => {
    const h = buildHistory(makeDb("2031-01-01", [28, 70]).entries);
    assert.equal(strawHint(h, "2031-06-01").stage, "late-hint");
  });
});

describe("FIGO", () => {
  test("próg regularności zależy od wieku", () => {
    assert.equal(regularityLimit("26-41"), 7);
    assert.equal(regularityLimit("42-45"), 9);
  });
  test("15 dni wahania → nieregularny, cykle poza 24–38 w liście do gabinetu", () => {
    const a = analyze(makeDb("2030-09-14", PERI), "2031-10-22");
    assert.ok(!a.figo.regOk);
    assert.equal(a.figo.variation, 15);
    assert.ok(a.figo.clinic.some((x) => x.includes("42 dni")));
    assert.ok(a.figo.freqOk);
  });
  test("dodatni test ciążowy jest oznaczony jako pilny", () => {
    const db = makeDb("2031-01-01", [28, 28, 28]);
    db.entries.push({ type: "preg_test", date: "2031-03-20", result: "pos" });
    assert.ok(analyze(db, "2031-03-21").figo.urgent);
  });
});

describe("raporty", () => {
  const sex = [
    { type: "sex", date: "2030-09-22" }, { type: "sex", date: "2030-10-25" },
    { type: "protected", date: "2030-10-25" }, { type: "him", date: "2030-10-26" },
  ];
  const db = makeDb("2030-09-14", PERI, sex);
  db.settings.health = { conditions: "choroba A, choroba B", meds: "lek X" };
  const a = analyze(db, "2031-10-22");
  test("intymność: liczby", () => {
    const i = intimacyOf(a);
    assert.equal(i.sex, 2); assert.equal(i.protected, 1); assert.equal(i.solo, 1); assert.equal(i.pct, 50);
  });
  test("raport tekstowy nigdy nie zawiera wpisów solo, także z datami", () => {
    const withDates = buildBrief(a, db, true);
    assert.ok(!/solo|masturb|him\b/i.test(withDates), "wpisy solo wyciekły do raportu");
    assert.ok(withDates.includes("## Daty") && withDates.includes("2030-09-22: sex"));
    assert.ok(!/solo|masturb|him\b/i.test(buildBrief(a, db, false)));
  });
  test("miesięcznie: dni okresu liczone w całości", () => {
    const m = monthlyOf(a);
    assert.ok(m.find((r) => r.month === "2030-09")!.bleed >= 5);
  });
  test("oś cykli: rząd na cykl + otwarty, okno wstecz od następnego okresu", () => {
    const lanes = lanesOf(a);
    assert.equal(lanes.length, PERI.length + 1);
    assert.ok(lanes.at(-1)!.open);
    const first = lanes[0]!;
    assert.deepEqual(first.win, [33 - 14 - 5, 33 - 14 + 1]);
    assert.equal(first.events[0]!.day, 8);
  });
  test("raport zawiera kontekst zdrowotny, trend i STRAW", () => {
    const text = buildBrief(a, db);
    assert.match(text, /Choroby: choroba A, choroba B/);
    assert.match(text, /Trend długości cykli/);
    assert.match(text, /STRAW\+10/);
    assert.ok(!/Daty/.test(text));
    assert.match(buildBrief(a, db, true), /## Daty/);
  });
  test("poprawne daty cykli dla dużego dziennika (wydajność)", () => {
    const big = makeDb("2000-01-01", Array.from({ length: 300 }, (_, i) => 26 + (i % 7)));
    const t0 = performance.now();
    analyze(big, addDays("2000-01-01", 300 * 29));
    assert.ok(performance.now() - t0 < 1500);
  });
});

describe("anemia", () => {
  test("anemia + skrzepy → dodatkowa flaga do gabinetu; bez anemii jej nie ma", () => {
    const db = makeDb("2031-01-01", [28, 28, 28, 28], [{ type: "clot", date: "2031-01-02" }]);
    const without = analyze(db, "2031-05-10");
    assert.ok(!without.figo.anemia);
    assert.ok(!without.figo.clinic.some((x) => x.startsWith("anemia")));
    db.settings.health = { conditions: "choroba A, anemia" };
    const withA = analyze(db, "2031-05-10");
    assert.ok(withA.figo.anemia);
    assert.ok(withA.figo.clinic.some((x) => x.startsWith("anemia")));
  });
  test("rozpoznaje warianty: niedokrwistość, niedobór żelaza", () => {
    for (const c of ["niedokrwistość", "niedobór żelaza", "Anemia"]) {
      const db = makeDb("2031-01-01", [28, 28, 28]); db.settings.health = { conditions: c };
      assert.ok(analyze(db, "2031-04-01").figo.anemia, c);
    }
  });
  test("raport zawiera wyniki morfologii/ferrytyny", () => {
    const db = makeDb("2031-01-01", [28, 28, 28]); db.settings.health = { conditions: "anemia", labs: "ferrytyna 12 (09.2026)" };
    assert.match(buildBrief(analyze(db, "2031-04-01"), db), /Morfologia \/ ferrytyna: ferrytyna 12/);
  });
});

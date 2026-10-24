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
    assert.equal(trendOf([28, 28, 28, 28, 28, 28]).direction, "stable");
    assert.equal(trendOf([28, 30]).direction, "unknown");
    // poniżej 6 cykli kierunku się nie podaje (zwykły rozrzut wyglądałby jak trend)
    assert.equal(trendOf([28, 30, 32, 34, 36]).direction, "unknown");
  });
  test("szum bez kierunku nie jest nazywany trendem", () => {
    assert.equal(trendOf([28, 33, 27, 31, 29, 32, 28, 30]).direction, "stable");
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
    assert.equal(regularityLimit("18-25"), 9);
    // po 45. roku życia FIGO nie podaje progu: regularność nie jest oceniana
    assert.equal(regularityLimit("46-50"), null);
    assert.equal(regularityLimit("51+"), null);
  });
  test("15 dni wahania → nieregularny, cykle poza 24–38 w liście do gabinetu", () => {
    const a = analyze(makeDb("2030-09-14", PERI), "2031-10-22");
    assert.ok(!a.figo.regOk);
    assert.equal(a.figo.variation, 15);
    assert.ok(a.figo.clinic.some((x) => x.includes("wahanie długości cykli 15 dni")));
    // pojedynczy cykl 42 d nie jest osobną flagą: liczy się powtarzanie się albo mediana poza zakresem
    assert.ok(!a.figo.clinic.some((x) => x.startsWith("cykle poza")));
    assert.ok(a.figo.freqOk);
  });
  test("dodatni test ciążowy jest oznaczony jako pilny", () => {
    const db = makeDb("2031-01-01", [28, 28, 28]);
    db.entries.push({ type: "preg_test", date: "2031-03-20", result: "pos" });
    assert.ok(analyze(db, "2031-03-21").figo.urgent);
  });
  test("dodatni test sprzed ponad 100 dni nie jest już flagą", () => {
    const db = makeDb("2031-01-01", [28, 28, 28]);
    db.entries.push({ type: "preg_test", date: "2031-01-20", result: "pos" });
    assert.ok(!analyze(db, "2031-06-01").figo.urgent);
  });
  test("pojedynczy cykl poza 24–38 i krótka historia nie dają alarmu", () => {
    const one = analyze(makeDb("2031-01-01", [23, 28, 28, 28]), "2031-05-10");
    assert.ok(!one.figo.clinic.some((x) => x.startsWith("cykle poza")), one.figo.clinic.join("|"));
    assert.ok(!one.figo.enoughReg);
    const two = analyze(makeDb("2031-01-01", [23, 41, 28, 28]), "2031-05-10");
    assert.ok(two.figo.clinic.some((x) => x.startsWith("cykle poza")));
  });
  test("po 45. roku życia regularność nie jest oceniana i nie daje flagi", () => {
    const db = makeDb("2030-09-14", PERI);
    db.settings.ageBand = "46-50";
    const a = analyze(db, "2031-10-22");
    assert.equal(a.figo.lim, null);
    assert.ok(!a.figo.enoughReg);
    assert.ok(!a.figo.clinic.some((x) => x.includes("wahanie")));
  });
  test("domyślne pasmo wieku to 26–41 (próg 7 dni)", () => {
    assert.equal(analyze(makeDb("2031-01-01", [28, 28, 28]), "2031-04-01").figo.band, "26-41");
  });
  test("krwawienie po ≥12 miesiącach bez miesiączki idzie do oceny lekarza", () => {
    const db = makeDb("2030-01-01", [28, 28]);
    db.entries.push({ type: "period_start", date: "2031-06-01" });
    const a = analyze(db, "2031-06-10");
    assert.ok(a.figo.clinic.some((x) => x.includes("po ponad 12 miesiącach")), a.figo.clinic.join("|"));
  });
  test("tryb ciąży/karmienia wyłącza flagi braku miesiączki", () => {
    const db = makeDb("2031-01-01", [28, 28, 28]);
    db.settings.mode = "pause";
    const a = analyze(db, "2031-09-01");
    assert.ok(!a.figo.urgent);
    assert.equal(a.pred.nextPeriods.length, 0);
    assert.equal(a.straw.stage, "off");
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
  test("oś cykli: start okresu w przyszłości nie daje rzędu z ujemną szerokością", () => {
    const fut = analyze(makeDb("2030-09-14", PERI, [{ type: "period_start", date: "2031-12-01" }]), "2031-10-22");
    assert.ok(lanesOf(fut).every((r) => r.length >= 1));
  });
  test("raport tekstowy nigdy nie zawiera wpisów solo, także z datami", () => {
    const withDates = buildBrief(a, db, true);
    assert.ok(!/solo|masturb|him\b/i.test(withDates), "wpisy solo wyciekły do raportu");
    assert.ok(withDates.includes("## Stosunki") && withDates.includes("2030-09-22: stosunek"));
    assert.ok(!/solo|masturb|him\b/i.test(buildBrief(a, db, false)));
  });
  test("bez zgody raport nie zawiera żadnej informacji o stosunkach (ani liczb)", () => {
    const text = buildBrief(a, db, false);
    assert.ok(!/stosunk|zabezpiecz|intymn|protected|\bsex\b/i.test(text), "informacje o stosunkach wyciekły do raportu");
    // liczba wpisów nie zdradza intymnych (4 z nich są intymne)
    assert.equal(Number(/wpisów: (\d+)/.exec(text)![1]), db.entries.filter((e) => !["sex", "protected", "him", "masturb"].includes(e.type)).length);
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
    assert.match(text, /trendu długości cykli|Trend długości cykli/);
    assert.match(text, /STRAW\+10/);
    assert.ok(!/Stosunki/.test(text));
    assert.match(buildBrief(a, db, true), /## Stosunki/);
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
    const db = makeDb("2031-01-01", [28, 28, 28, 28], [{ type: "clot", date: "2031-01-02" }, { type: "clot", date: "2031-01-30" }]);
    const without = analyze(db, "2031-05-10");
    assert.ok(!without.figo.anemia);
    assert.ok(!without.figo.clinic.some((x) => x.startsWith("anemia")));
    db.settings.health = { conditions: "choroba A, anemia" };
    const withA = analyze(db, "2031-05-10");
    assert.ok(withA.figo.anemia);
    assert.ok(withA.figo.clinic.some((x) => x.startsWith("anemia")));
  });
  test("zaprzeczenie „brak anemii” nie jest anemią", () => {
    for (const c of ["brak anemii, ferrytyna w normie", "bez niedokrwistości", "anemia wykluczona"]) {
      const db = makeDb("2031-01-01", [28, 28, 28]); db.settings.health = { conditions: c };
      assert.ok(!analyze(db, "2031-04-01").figo.anemia, c);
    }
  });
  test("jeden skrzep nie jest sygnałem obfitego krwawienia", () => {
    const db = makeDb("2031-01-01", [28, 28, 28, 28], [{ type: "clot", date: "2031-01-02" }]);
    assert.ok(!analyze(db, "2031-05-10").figo.hmb);
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

describe("STRAW+10 zależnie od wieku i trybu", () => {
  const jumpy = () => buildHistory(makeDb("2030-09-14", PERI).entries);
  test("młodsza osoba dostaje neutralny opis, nie kryterium STRAW", () => {
    const s = strawHint(jumpy(), "2031-10-22", "26-41");
    assert.equal(s.stage, "early-hint");
    assert.ok(!/STRAW/.test(s.text) && /PCOS|tarczyca/.test(s.text));
  });
  test("od 42 lat: kryterium STRAW; od 46 lat dopisek o częstym obrazie okołomenopauzy", () => {
    assert.match(strawHint(jumpy(), "2031-10-22", "42-45").text, /STRAW\+10/);
    assert.match(strawHint(jumpy(), "2031-10-22", "46-50").text, /okołomenopauzy/);
  });
  test("jeden wyjątkowo długi cykl to jedno zdarzenie, nie dwa skoki", () => {
    const h = buildHistory(makeDb("2031-01-01", [28, 28, 56, 28, 28, 28]).entries);
    assert.equal(strawHint(h, "2031-07-25", "46-50").bigJumps.length, 1);
    assert.equal(strawHint(h, "2031-07-25", "46-50").stage, "possible-early");
  });
  test("brak miesiączki ponad 12 miesięcy: kryterium menopauzy (≥46) albo neutralna prośba o ocenę lekarza", () => {
    const h = buildHistory(makeDb("2030-01-01", [28, 28]).entries);
    const old = strawHint(h, "2031-06-01", "51+");
    assert.equal(old.stage, "menopause-hint");
    assert.match(old.text, /menopauzy/);
    assert.ok(!/menopauz/.test(strawHint(h, "2031-06-01", "18-25").text));
  });
  test("ciąża, połóg, karmienie albo hormony wyłączają wskazówkę", () => {
    assert.equal(strawHint(jumpy(), "2031-10-22", "46-50", "hormonal").stage, "off");
    assert.equal(strawHint(jumpy(), "2031-10-22", "46-50", "pause").stage, "off");
  });
});

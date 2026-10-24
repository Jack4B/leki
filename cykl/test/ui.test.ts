import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { html, esc, raw } from "../src/html.ts";
import { renderTab, sheetDay, sheetImport, sheetWipe, shiftMonth } from "../src/views.ts";
import type { UiState, Tab } from "../src/views.ts";
import { analyze } from "../src/analysis.ts";
import { cycleChart, lanes } from "../src/charts.ts";
import { lanesOf } from "../src/report.ts";
import { KEY, UndoStack, loadDb, saveDb, loadTheme, saveTheme, nextTheme } from "../src/store.ts";
import type { StorageLike } from "../src/store.ts";
import { makeDb, PERI } from "./helpers.ts";
import { emptyDb } from "../src/model.ts";

const XSS = `</textarea><img src=x onerror=alert(1)>"'&`;
const mkState = (tab: Tab, db = makeDb("2030-09-14", PERI, [{ type: "note", date: "2031-10-20", text: XSS }, { type: "sex", date: "2030-10-25" }])): UiState => {
  db.settings.health = { conditions: XSS, meds: XSS, tsh: XSS };
  return { db, tab, month: "2031-10", range: 12, selCycle: null, today: "2031-10-22", theme: "auto", withDates: true };
};

describe("html``", () => {
  test("escapuje wartości, przepuszcza SafeHtml, składa tablice", () => {
    assert.equal(html`<b>${"<i>"}</b>`.value, "<b>&lt;i&gt;</b>");
    assert.equal(html`${raw("<i>")}`.value, "<i>");
    assert.equal(html`${[html`<a>`, "<b>"]}`.value, "<a>&lt;b&gt;");
    assert.equal(html`${null}${false}${undefined}`.value, "");
    assert.equal(esc(`"'&<>`), "&quot;&#39;&amp;&lt;&gt;");
  });
});

describe("widoki (czyste renderery)", () => {
  for (const tab of ["dzis", "kal", "wyk", "dane"] as Tab[]) {
    test(`zakładka ${tab}: renderuje bez NaN/undefined i bez wstrzyknięcia HTML`, () => {
      const out = renderTab(mkState(tab)).value;
      assert.ok(out.length > 500);
      assert.ok(!/NaN|undefined|\[object/.test(out), "wyciekła wartość techniczna");
      assert.ok(!out.includes("<img src=x"), "XSS przeszedł");
    });
  }
  test("pusta baza: ekran startowy zamiast wyjątku", () => {
    for (const tab of ["dzis", "kal", "wyk", "dane"] as Tab[]) {
      assert.doesNotThrow(() => renderTab({ ...mkState(tab), db: emptyDb() }));
    }
  });
  test("w aplikacji nadrzędnej: bez wyboru motywu i bez wzmianek o przeglądarce, tekst bez formy rodzajowej", () => {
    const own = renderTab(mkState("dane")).value;
    assert.ok(own.includes("Wygląd") && own.includes("przeglądarce"));
    assert.ok(!own.includes("wpisałeś"));
    const host = renderTab({ ...mkState("dane"), host: true }).value;
    assert.ok(!host.includes("Wygląd") && !host.includes('data-act="theme"') && !host.includes("przeglądarce"));
    assert.ok(host.includes("Kopie zapasowe i dane"));
  });
  test("Dziś: po zapisaniu startu okresu kafelek startu zostaje (jego dotknięcie cofa pomyłkę), obok jest koniec okresu", () => {
    const st = mkState("dzis");
    const today = st.today;
    const db = { ...st.db, entries: [...st.db.entries.filter((e) => !(e.date === today && e.type === "period_start")), { type: "period_start", date: today }] };
    const out = renderTab({ ...st, db }).value;
    assert.ok(out.includes('data-type="period_start"') && out.includes('data-type="period_end"'));
  });
  test("Dziś: ostatni początek okresu sprzed ponad 90 dni nie daje „dnia cyklu 700+” ani zalecenia testu ciążowego", () => {
    const old = { ...mkState("dzis"), today: "2036-01-01" };
    const out = renderTab(old).value;
    assert.ok(out.includes("Brak świeżych wpisów") && !out.includes("test ciążowy") && !/dzień cyklu/.test(out));
    assert.ok(renderTab(mkState("dzis")).value.includes("dzień cyklu"));
  });
  test("arkusz dnia i raport escapują notatkę i dane zdrowotne", () => {
    const s = mkState("dane");
    const sheet = sheetDay(analyze(s.db, s.today), "2031-10-20").value;
    assert.ok(!sheet.includes("<img src=x"));
    assert.ok(sheet.includes("&lt;/textarea&gt;"));
    assert.ok(!renderTab(s).value.includes("<img src=x"));
  });
  test("Dziś: pokazuje fazę, następny okres i pasek 21 dni", () => {
    const out = renderTab(mkState("dzis")).value;
    assert.match(out, /Miesiączka|Faza|Okno/);
    assert.match(out, /Następny okres/);
    assert.equal((out.match(/class="sb"/g) ?? []).length, 21);
  });
  test("Kalendarz: liczba komórek dni = dni miesiąca", () => {
    const out = renderTab(mkState("kal")).value;
    assert.equal((out.match(/data-act="day"/g) ?? []).length, 31);
  });
  test("zmiana motywu widoczna w nagłówku", () => {
    assert.match(renderTab({ ...mkState("dzis"), theme: "dark" }).value, /Motyw: Ciemny/);
  });
  test("shiftMonth przez granicę roku", () => {
    assert.equal(shiftMonth("2031-01", -1), "2030-12");
    assert.equal(shiftMonth("2031-12", 1), "2032-01");
  });
});

describe("wykresy", () => {
  const a = analyze(makeDb("2030-09-14", PERI), "2031-10-22");
  test("wykres cykli: słupek na cykl, przewijana szerokość rośnie z liczbą cykli", () => {
    const small = cycleChart(a.hist.cycles.slice(-6), 6, null).value;
    const big = cycleChart(a.hist.cycles, 0, null).value;
    assert.equal((big.match(/class="bar/g) ?? []).length, PERI.length);
    const w = (s: string) => Number(/<svg width="(\d+)"/.exec(s)![1]);
    assert.ok(w(big) > w(small));
    assert.match(big, /trendline/);
  });
  test("cykle poza 24–38 mają klasę bad", () => {
    const out = cycleChart(a.hist.cycles, 0, null).value;
    assert.equal((out.match(/class="bar bad"/g) ?? []).length, 2);
  });
  test("oś cykli: rząd na cykl", () => {
    assert.equal((lanes(lanesOf(a)).value.match(/class="lane"/g) ?? []).length, PERI.length + 1);
  });
});

describe("store", () => {
  const fake = (): StorageLike & { m: Map<string, string> } => {
    const m = new Map<string, string>();
    return { m, getItem: (k) => m.get(k) ?? null, setItem: (k, v) => { m.set(k, v); }, removeItem: (k) => { m.delete(k); } };
  };
  test("zapis i odczyt, ten sam klucz co poprzednia wersja", () => {
    const s = fake();
    assert.ok(saveDb(s, makeDb("2031-01-01", [28, 28])));
    assert.ok(s.m.has(KEY));
    assert.equal(loadDb(s).entries.length, 6);
  });
  test("uszkodzone dane nie wywracają aplikacji", () => {
    const s = fake(); s.setItem(KEY, "{nie json");
    assert.equal(loadDb(s).entries.length, 0);
  });
  test("błąd zapisu (pełna pamięć) zwraca false", () => {
    const s: StorageLike = { getItem: () => null, setItem: () => { throw new Error("quota"); }, removeItem: () => {} };
    assert.equal(saveDb(s, emptyDb()), false);
  });
  test("motyw: zapis, auto kasuje klucz, cykl auto→jasny→ciemny", () => {
    const s = fake();
    saveTheme(s, "dark"); assert.equal(loadTheme(s), "dark");
    saveTheme(s, "auto"); assert.equal(loadTheme(s), "auto"); assert.equal(s.m.size, 0);
    assert.deepEqual(["auto", "light", "dark"].map((t) => nextTheme(t as never)), ["light", "dark", "auto"]);
  });
  test("cofnij: stos ograniczony do 20", () => {
    const u = new UndoStack();
    for (let i = 0; i < 25; i++) u.push([{ type: "x", date: String(i) }]);
    assert.equal(u.size, 20);
    assert.equal(u.pop()![0]!.date, "24");
  });
});

test("anemia w czasie okresu: Dziś podpowiada zapis obfitości, dopóki go nie ma", () => {
  const db = makeDb("2030-09-14", PERI); db.settings.health = { conditions: "anemia" };
  const s: UiState = { db, tab: "dzis", month: "2031-10", range: 12, selCycle: null, today: "2031-10-20", theme: "auto", withDates: false };
  assert.match(renderTab(s).value, /Zapisz obfitość dzisiaj/);
  db.entries.push({ type: "flow", date: "2031-10-20", value: 3 });
  assert.ok(!/Zapisz obfitość dzisiaj/.test(renderTab(s).value));
});

describe("pasma wieku, tryb prognoz i komunikaty", () => {
  test("Wykresy: lista wieku ma 5 pasm (46–50 i powyżej 50), tryb prognoz ma 3 opcje", () => {
    const out = renderTab(mkState("wyk")).value;
    for (const b of ["18-25", "26-41", "42-45", "46-50", "51+"]) assert.ok(out.includes(`value="${b}"`), b);
    const dane = renderTab(mkState("dane")).value;
    for (const m of ["normal", "hormonal", "pause"]) assert.ok(dane.includes(`value="${m}"`), m);
  });
  test("Wykresy: bez cykli jest stan „Za mało danych”, a nie zielone „bez flag”", () => {
    const out = renderTab({ ...mkState("wyk"), db: emptyDb() }).value;
    assert.ok(out.includes("Za mało danych") && !out.includes("Osie FIGO bez flag"));
  });
  test("Dziś i Kalendarz zawierają zastrzeżenie, że prognoza nie jest antykoncepcją ani dowodem ciąży", () => {
    const today = makeDb("2031-01-01", [28, 28, 28, 28, 28, 28]);
    const st = { ...mkState("dzis", today), today: "2031-07-10", month: "2031-07" };
    assert.match(renderTab(st).value, /nie jest metodą antykoncepcji ani dowodem ciąży/);
    assert.match(renderTab({ ...st, tab: "kal" }).value, /nie jest antykoncepcją ani dowodem ciąży/);
  });
  test("Dziś: osoba 46–50 dostaje uwagę o mniejszej pewności i o antykoncepcji do 12 miesięcy bez miesiączki", () => {
    const db = makeDb("2031-01-01", [28, 28, 28, 28, 28, 28]); db.settings.ageBand = "46-50";
    const out = renderTab({ ...mkState("dzis", db), today: "2031-07-10" }).value;
    assert.match(out, /bezowulacyjne i nieregularne/);
    assert.match(out, /12 miesięcy bez miesiączki/);
    const young = renderTab({ ...mkState("dzis", makeDb("2031-01-01", [28, 28, 28, 28, 28, 28])), today: "2031-07-10" }).value;
    assert.ok(!young.includes("bezowulacyjne"));
  });
  test("Dziś: spóźniony okres bez dzisiejszego testu podpowiada test przy stosunku, a po dodatnim teście wizytę", () => {
    const db = makeDb("2031-01-01", [28, 28, 28, 28, 28, 28]);
    const l = db.entries.filter((e) => e.type === "period_start").at(-1)!.date;
    const late = { ...mkState("dzis", db), today: "2031-08-30" };
    assert.ok(l < late.today);
    assert.match(renderTab(late).value, /Jeśli był stosunek, rozważ test ciążowy/);
    const pos = { ...late, db: { ...db, entries: [...db.entries, { type: "preg_test", date: late.today, result: "pos" }] } };
    assert.match(renderTab(pos).value, /umów wizytę/);
  });
  test("okna importu i usuwania w aplikacji nie mówią o przeglądarce i wskazują przywracanie", () => {
    assert.ok(!sheetImport(3, 1, true).value.includes("przeglądarki") && sheetImport(3, 1, true).value.includes("Moduł Cykl"));
    assert.ok(sheetImport(3, 1, false).value.includes("przeglądarki"));
    assert.ok(!sheetWipe(true).value.includes("nie da się cofnąć") && sheetWipe(true).value.includes("Moduł Cykl"));
  });
  test("formularz dnia: grupy przycisków mają nazwy i stan dla czytnika ekranu", () => {
    const out = sheetDay(analyze(makeDb("2031-01-01", [28]), "2031-02-01"), "2031-01-05").value;
    assert.ok(out.includes('role="group"') && out.includes('aria-pressed="true"') && out.includes('aria-label="Notatka dnia"'));
  });
});

describe("okno płodne: spójność etykiet z procentami", () => {
  test("widok dnia nie podaje procentu ≥50% przy etykiecie „możliwe” (obniżony poziom to „prognoza niepewna”)", () => {
    const db = makeDb("2030-09-14", PERI);
    const a = analyze(db, "2031-10-22");
    for (let i = 0; i < 30; i++) {
      const d = new Date(Date.parse("2031-10-22T12:00:00Z") + i * 864e5).toISOString().slice(0, 10);
      const out = sheetDay(a, d).value;
      const m = /okno płodne możliwe \(ok\. (\d+)%\)/.exec(out);
      if (m) assert.ok(Number(m[1]) < 50, `${d}: ${m[0]}`);
      assert.ok(!/bardzo prawdopodobne \(ok\. (\d+)%\)/.test(out) || a.info(d).window >= 0.5);
    }
  });
  test("prawdopodobieństwo okna nigdy nie przekracza 100%", () => {
    const db = makeDb("2031-01-01", [17, 18, 19, 17, 20, 18, 21]);
    db.entries.push({ type: "lh", date: "2031-04-30", result: "pos" }); db.settings.ageBand = "51+";
    const a = analyze(db, "2031-05-05");
    for (let i = 0; i < 90; i++) {
      const d = new Date(Date.parse("2031-05-05T12:00:00Z") + i * 864e5).toISOString().slice(0, 10);
      assert.ok(a.info(d).window <= 1);
    }
  });
});

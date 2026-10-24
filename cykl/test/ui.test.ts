import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { html, esc, raw } from "../src/html.ts";
import { renderTab, sheetDay, shiftMonth } from "../src/views.ts";
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

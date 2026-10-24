import type { Db } from "./types.ts";
import { html, raw } from "./html.ts";
import type { SafeHtml } from "./html.ts";
import { diffDays, iso, plN } from "./dates.ts";
import { analyze } from "./analysis.ts";
import type { Analysis } from "./analysis.ts";
import { flagsFor } from "./model.ts";
import { buildBrief, intimacyOf, lanesOf, monthlyOf } from "./report.ts";
import { cycleChart, lanes, monthChart, ring, strip } from "./charts.ts";
import type { Theme } from "./store.ts";

export type Tab = "dzis" | "kal" | "wyk" | "dane";
export interface UiState {
  db: Db; tab: Tab; month: string; range: number; selCycle: number | null; today: string; theme: Theme; withDates: boolean;
  /** Moduł działa w aplikacji Asystent leków: motyw i kopie zapasowe obsługuje aplikacja. */
  host?: boolean;
}

const dfmt = (date: string, o: Intl.DateTimeFormatOptions = { day: "numeric", month: "short" }): string =>
  new Date(`${date}T12:00:00`).toLocaleDateString("pl-PL", o);
const range = (a: string, b: string): string => (a === b ? dfmt(a) : `${dfmt(a)} – ${dfmt(b)}`);
const inDays = (date: string, today: string): string => {
  const n = diffDays(today, date);
  return n === 0 ? "dziś" : n === 1 ? "jutro" : n > 0 ? `za ${n} dni` : `${-n} dni temu`;
};
const THEME_LABEL: Record<Theme, string> = { auto: "Auto", light: "Jasny", dark: "Ciemny" };

const top = (title: string, sub: string, theme: Theme): SafeHtml => html`
  <header class="top"><div><h1>${title}</h1>${sub ? html`<div class="sub">${sub}</div>` : ""}</div>
  <button class="theme" data-act="theme-cycle" aria-label="Motyw: ${THEME_LABEL[theme]}. Dotknij, aby zmienić">
    <span aria-hidden="true">${theme === "dark" ? "☾" : theme === "light" ? "☀" : "◐"}</span> ${THEME_LABEL[theme]}</button></header>`;

const quick = (type: string, label: string, on: boolean): SafeHtml =>
  html`<button class="q${on ? " on" : ""}" data-act="flip" data-type="${type}" aria-pressed="${on}"><i></i>${label}</button>`;

export const viewToday = (a: Analysis, s: UiState): SafeHtml => {
  const t = s.today;
  const head = top("Dziś", dfmt(t, { weekday: "long", day: "numeric", month: "long" }), s.theme);
  if (!a.hist.lastStart) {
    return html`${head}<div class="card hero"><div class="phase">Zacznij od startu okresu</div>
      <p class="mute">Dodaj datę ostatniego okresu albo wczytaj plik z dziennikiem cyklu (zakładka Dane).</p>
      <button class="btn" data-act="flip" data-type="period_start">Dodaj start okresu dziś</button>
      <button class="btn sec" data-act="tab" data-tab="dane">Importuj kopię</button></div>`;
  }
  // Ostatni zapisany początek okresu sprzed ponad 90 dni: prognoza byłaby wprowadzająca w błąd (dzień cyklu 700+, „okres spóźniony”)
  const gap = diffDays(a.hist.lastStart, t);
  if (gap > 90) {
    return html`${head}<div class="card hero"><div class="phase">Brak świeżych wpisów</div>
      <p class="mute">Ostatni zapisany początek okresu: ${dfmt(a.hist.lastStart, { day: "numeric", month: "long", year: "numeric" })} (${gap} dni temu). Prognoza potrzebuje niedawnych danych.</p>
      <button class="btn" data-act="flip" data-type="period_start">Dodaj start okresu dziś</button>
      <button class="btn sec" data-act="tab" data-tab="kal">Wskaż datę w kalendarzu</button></div>`;
  }
  const inf = a.info(t), f = flagsFor(a.entries, t), p = a.pred;
  const t0 = f.test;
  const chip = inf.bleed ? ["per", "Miesiączka trwa"] : p.mode === "pause" ? ["low", "Prognozy wyłączone w ustawieniach"] : p.overdueDays > 0
    ? ["mid", t0 === "pos" ? "Okres spóźniony, test dodatni: umów wizytę u lekarza lub położnej" : `Okres spóźniony o ${p.overdueDays} d, poza własnym typowym zakresem`]
    : p.noForecast ? ["low", "Brak prognozy: okres spóźniony bardziej niż w dotychczasowych cyklach"]
    : p.mode === "hormonal" ? ["low", "Szansa poczęcia: prognoza wyłączona (antykoncepcja hormonalna)"]
    : inf.level === "high" ? ["high", "Wysoka szansa poczęcia dziś"]
    : inf.level === "mid" ? ["mid", "Podwyższona szansa (prognoza niepewna)"] : ["low", "Mniejsza szansa wg prognozy (nigdy zerowa)"];
  const age = a.settings.ageBand ?? "26-41";
  const conf = p.confidence === "high" ? "Pewność prognozy: wysoka, cykle są regularne."
    : p.confidence === "mid" ? `Pewność prognozy: średnia. Zwykle ${p.lengthMedian} d, w 80% przypadków ${p.lengthLow}–${p.lengthHigh} d.`
    : `Pewność prognozy: niska (${a.hist.cycles.length < 3 ? "mało zapisanych cykli" : "cykle bardzo się różnią"}). Zwykle ${p.lengthMedian} d, w 80% przypadków ${p.lengthLow}–${p.lengthHigh} d, więc szansa jest rozłożona szeroko. Test LH da pewniejszy sygnał.`;
  const ageNote = age === "46-50" || age === "51+"
    ? html`<p class="note left">W tym wieku cykle bywają bezowulacyjne i nieregularne, więc prognoza jest mniej pewna, a poczęcie nadal możliwe. Wytyczne zwykle zalecają stosowanie antykoncepcji do 12 miesięcy bez miesiączki po 50. roku życia (do 24 miesięcy przed 50.): omów to z lekarzem.</p>` : "";
  const np = p.nextPeriods[0], run = p.nextRun;
  const rangeTxt = !np || np.from === np.to ? "" : html`<br>80%: ${range(np.from, np.to)}`;
  const overdueHint = p.overdueDays > 0 && t0 !== "pos" && t0 !== "neg" ? html`<p class="note left">Jeśli był stosunek, rozważ test ciążowy. Przy ujemnym teście i dalszym braku okresu powtórz test po kilku dniach lub skontaktuj się z lekarzem.</p>` : "";
  return html`${head}
    <section class="card hero" aria-label="Stan cyklu">
      <div class="ringbox">${ring(a)}<div class="ringc"><b>${a.cycleDay}</b><span>dzień cyklu</span></div></div>
      <div class="phase">${a.phase}</div>
      <div class="mute">typowo ${p.lengthMedian} d (w 80% przypadków ${p.lengthLow}–${p.lengthHigh})</div>
      <span class="chip ${chip[0]}">${chip[1]}</span>
      <p class="note left">${conf}</p>${overdueHint}${ageNote}
      <p class="note left">Prognoza z dziennika nie jest metodą antykoncepcji ani dowodem ciąży lub jej braku.</p>
    </section>
    <div class="grid2">
      <div class="stat"><small>Następny okres</small><strong>${np ? dfmt(np.date) : "—"}</strong><span>${np ? html`${inDays(np.date, t)}${rangeTxt}` : "brak prognozy"}</span></div>
      <div class="stat"><small>Okno płodne</small><strong>${run ? range(run.from, run.to) : "—"}</strong>
        <span>${run ? html`${run.from <= t ? "trwa" : inDays(run.from, t)}<br>szczyt ok. ${dfmt(run.peak)}` : p.mode !== "normal" || p.noForecast ? "prognoza wyłączona lub niedostępna" : "brak w 90 dniach"}</span></div>
    </div>
    ${inf.bleed && a.figo.anemia && f.flow === 0 ? html`<div class="warn"><b>Zapisz obfitość dzisiaj.</b> Przy anemii lekarz zapyta o ilość krwawienia, a zapis z dnia jest dokładniejszy niż pamięć.
      <button class="btn sec" data-act="day" data-date="${t}">Zapisz obfitość</button></div>` : ""}
    <section class="card"><h2>Najbliższe 21 dni</h2>${strip(a)}
      <div class="legend"><span><i class="sw lv-high"></i>wysoka</span><span><i class="sw lv-mid"></i>podwyższona</span><span><i class="sw lv-low"></i>niska</span><span><i class="sw lv-bleed"></i>okres</span></div>
      <p class="note">Wysokość słupka to względna szansa poczęcia przy stosunku w danym dniu, liczona z rozkładu możliwych owulacji. Płaskie, niskie słupki oznaczają, że prognoza jest niepewna.</p></section>
    <section class="card"><h2>Zapisz dziś</h2><div class="quick">
      ${quick("sex", "Stosunek", f.sex)}${quick("protected", "Zabezpieczone", f.prot)}${quick("him", "Solo — partner/ka", f.solo)}
      ${quick("spotting", "Plamienie", f.spot)}${quick("lh", "Test LH +", f.lh)}
      ${f.start ? quick("period_start", "Start okresu", true) : ""}${inf.bleed || f.start ? quick("period_end", "Koniec okresu", f.end) : quick("period_start", "Start okresu", false)}</div>
      <button class="btn sec" data-act="day" data-date="${t}">Więcej: ból, obfitość, notatka</button></section>
    <p class="note">Prognoza statystyczna (owulacja ≈ 14 dni przed okresem, wagi dni wg badań Wilcox i wsp. 1995). Nie jest antykoncepcją, dowodem ciąży ani diagnozą.</p>`;
};

export const viewCalendar = (a: Analysis, s: UiState): SafeHtml => {
  const [y, m] = s.month.split("-").map(Number) as [number, number];
  const first = (new Date(y, m - 1, 1).getDay() + 6) % 7, n = new Date(y, m, 0).getDate();
  const title = new Date(y, m - 1, 1).toLocaleDateString("pl-PL", { month: "long", year: "numeric" });
  const cells: SafeHtml[] = Array.from({ length: first }, () => html`<span class="day empty"></span>`);
  for (let d = 1; d <= n; d++) {
    const date = iso(new Date(y, m - 1, d)), i = a.info(date), f = flagsFor(a.entries, date);
    const cls = ["day", date === a.today ? "today" : "", i.bleed ? "bleed" : i.predBleed ? "pred" : `lv-${i.level}`].filter(Boolean).join(" ");
    cells.push(html`<button class="${cls}" data-act="day" data-date="${date}" aria-label="${date}">
      ${i.ovulation ? html`<span class="ov" aria-hidden="true">★</span>` : ""}<span>${d}</span>
      <span class="dots">${f.sex ? html`<i class="dot sex"></i>` : ""}${f.solo ? html`<i class="dot solo"></i>` : ""}${f.spot ? html`<i class="dot spot"></i>` : ""}${f.lh ? html`<i class="dot lh"></i>` : ""}</span></button>`);
  }
  return html`${top("Kalendarz", "", s.theme)}<section class="card cal-card" id="cal">
    <div class="nav"><button class="icon" data-act="prev" aria-label="Poprzedni miesiąc">‹</button><strong>${title}</strong><button class="icon" data-act="next" aria-label="Następny miesiąc">›</button></div>
    <div class="wd"><span>Pn</span><span>Wt</span><span>Śr</span><span>Cz</span><span>Pt</span><span>So</span><span>Nd</span></div>
    <div class="cal">${cells}</div>
    <div class="legend"><span><i class="sw lv-bleed"></i>okres</span><span><i class="sw dashed"></i>prognoza okresu</span>
      <span><i class="sw lv-high"></i>wysoka szansa</span><span><i class="sw lv-mid"></i>podwyższona</span><span>★ owulacja (szac.)</span>
      <span><i class="dot sex"></i>stosunek</span><span><i class="dot solo"></i>solo</span><span><i class="dot spot"></i>plamienie</span><span><i class="dot lh"></i>test LH+</span></div>
    <p class="note">Przesuń palcem w bok, aby zmienić miesiąc. Dla przeszłych cykli okno liczone jest wstecz od faktycznego następnego okresu. Prognoza nie jest antykoncepcją ani dowodem ciąży lub jej braku.</p></section>`;
};

const pill = (ok: boolean, na: boolean): SafeHtml => html`<span class="pill ${na ? "na" : ok ? "ok" : "bad"}">${na ? "za mało danych" : ok ? "w normie" : "poza normą"}</span>`;

export const viewCharts = (a: Analysis, s: UiState): SafeHtml => {
  const all = a.hist.cycles;
  if (!all.length) {
    return html`${top("Wykresy", "", s.theme)}<section class="card hero"><div class="phase">Za mało danych</div>
      <p class="mute">Wykresy i oceny pojawią się po zapisaniu co najmniej dwóch początków okresu.</p>
      <button class="btn" data-act="tab" data-tab="dzis">Przejdź do Dziś</button></section>`;
  }
  const offset = s.range >= all.length ? 0 : all.length - s.range;
  const shown = all.slice(offset);
  const sel = s.selCycle != null && all[s.selCycle] ? s.selCycle : all.length - 1;
  const c = all[sel], prev = all[sel - 1];
  const f = a.figo, i = intimacyOf(a), tr = a.trend;
  const box = f.urgent ? html`<div class="warn"><b>Skontaktuj się z lekarzem:</b> ${f.urgentWhy}. Przy silnym bólu brzucha lub obfitym krwawieniu pilnie (gabinet lub SOR), nie aplikacja.</div>`
    : f.clinic.length ? html`<div class="warn"><b>Do omówienia w gabinecie</b><ul>${f.clinic.map((x) => html`<li>${x}</li>`)}</ul></div>`
    : html`<div class="okbox"><b>Osie FIGO bez flag.</b> To obserwacja z dziennika, nie rozpoznanie.</div>`;
  const chips = [6, 12, 999].map((r) => html`<button class="chipb${s.range === r ? " on" : ""}" data-act="range" data-n="${r}">${r === 999 ? "Wszystko" : `${r} cykli`}</button>`);
  const dirTxt = tr.direction === "unknown" ? "Za mało cykli na trend (potrzeba ≥6)."
    : tr.direction === "stable" ? "Bez wyraźnego trendu długości cykli."
    : `Cykle ${tr.direction === "longer" ? "wydłużają się" : "skracają się"} o ok. ${Math.abs(Math.round(tr.slope * 10) / 10)} d na cykl.`;
  const cmp = tr.recent3 != null && tr.prev3 != null ? ` Ostatnie 3 cykle: średnio ${Math.round(tr.recent3)} d, poprzednie 3: ${Math.round(tr.prev3)} d.` : "";
  const mo = monthlyOf(a);
  const lanesRows = lanesOf(a);
  return html`${top("Wykresy", "", s.theme)}${box}
    <section class="card"><h2>Długość cykli</h2><div class="chips">${chips}</div>
      <div class="scroll" id="cscroll" tabindex="0" aria-label="Wykres przewijany">${cycleChart(shown, offset, sel)}</div>
      <p class="note">Przesuń w bok. Zielone pasmo = norma 24–38 dni, linia ciągła = średnia krocząca (3 cykle), przerywana = trend (Theil–Sen).</p>
      ${c ? html`<div class="detail"><b>Cykl ${dfmt(c.start)} → ${dfmt(c.end)}</b>: ${c.days} dni${c.bleed ? `, krwawienie ${c.bleed} d` : ""}${prev ? `, ${c.days - prev.days >= 0 ? "+" : ""}${c.days - prev.days} d względem poprzedniego` : ""}.</div>` : ""}</section>
    <section class="card"><h2>Trend i faza przejściowa</h2><p>${dirTxt}${cmp}</p>
      <div class="${a.straw.stage === "none" ? "okbox" : "warn"}">${a.straw.text}</div>
      <p class="note">STRAW+10 to kryteria stadiów starzenia reprodukcyjnego (Harlow i wsp., 2012). Tu tylko wskazówka z dziennika, ocena należy do lekarza.</p></section>
    <section class="card"><h2>FIGO System 1</h2>
      <div class="axis"><b>Częstość</b>${f.avg} dni ${pill(f.freqOk, !f.nCycles)}<div class="mute">${f.freq}</div></div>
      <div class="axis"><b>Regularność</b>wahanie ${f.variation} dni ${f.lim == null ? html`<span class="pill na">nie oceniana</span>` : pill(f.regOk, !f.enoughReg)}<div class="mute">${f.lim == null ? `w wieku ${f.band} FIGO nie podaje progu, zmienność cykli jest częsta` : `próg ${f.lim} dni (wiek ${f.band}); ocena od 6 cykli`}</div></div>
      <div class="axis"><b>Czas trwania</b>${f.bMin == null ? "brak końców okresu" : (f.bMin === f.bMax ? `${f.bMin} dni` : `${f.bMin}–${f.bMax} dni`)} ${pill(f.durOk, f.bMin == null)}</div>
      <div class="axis"><b>Plamienie poza okresem</b>${f.imb.length} ${pill(!f.imb.length, false)}</div>
      <label class="lab" for="age">Wiek (próg regularności i opis fazy przejściowej)</label>
      <select id="age" data-change="age">${(["18-25", "26-41", "42-45", "46-50", "51+"] as const).map((b) => html`<option value="${b}"${b === f.band ? raw(" selected") : ""}>${b === "51+" ? "powyżej 50 lat" : `${b} lat`}</option>`)}</select></section>
    <section class="card"><h2>Oś cykli</h2>${lanes(lanesRows)}
      <div class="legend"><span><i class="sw lv-bleed"></i>okres</span><span><i class="sw lv-high"></i>okno płodne (wstecz)</span><span><i class="dot sex"></i>stosunek z zabezp.</span><span><i class="dot hollow"></i>bez flagi</span></div>
      <p class="note">Widać, czy stosunki bez flagi zabezpieczenia wypadały w zielonym oknie.</p></section>
    <section class="card"><h2>Intymność</h2>
      <div class="grid2 tight"><div class="stat"><small>Stosunki</small><strong>${i.sex}</strong><span>${i.pct}% z zabezpieczeniem</span></div>
      <div class="stat"><small>Solo (partner/ka)</small><strong>${i.solo}</strong></div>
      <div class="stat wide2"><small>W dniach podwyższonej szansy</small><strong>${i.atRiskTotal}</strong><span>w tym bez zabezpieczenia: ${i.unprotectedAtRisk}</span></div></div>
      <div class="scroll" id="mscroll">${monthChart(mo)}</div>
      <div class="legend"><span><i class="sw sexc"></i>stosunki</span><span><i class="sw soloc"></i>solo</span></div></section>
    <section class="card"><h2>Cykle</h2><table><thead><tr><th>Od</th><th>Do</th><th>Dni</th><th>Krwaw.</th></tr></thead><tbody>
      ${[...all].reverse().map((x) => html`<tr><td>${x.start}</td><td>${x.end}</td><td>${x.days < 24 || x.days > 38 ? html`<b>${x.days}</b>` : x.days}</td><td>${x.bleed ? `${x.bleed} d` : "—"}</td></tr>`)}
      </tbody></table></section>`;
};

export const viewData = (a: Analysis, s: UiState): SafeHtml => {
  const last = s.db.settings.lastExport, stale = !last || diffDays(last, a.today) > 30, hl = s.db.settings.health ?? {};
  const brief = buildBrief(a, s.db, s.withDates);
  return html`${top("Dane", "", s.theme)}
    ${s.host ? html`<p class="mute">Kopię całej aplikacji razem z tym modułem zrobisz w Ustawieniach → Kopie zapasowe i dane.</p>`
      : stale ? html`<div class="warn"><b>Zrób kopię zapasową.</b> Dane są tylko w tej przeglądarce. ${last ? `Ostatnia kopia: ${last}.` : "Nie robiono jeszcze kopii z tej wersji."} Przeglądarki potrafią czyścić dane stron.</div>` : html`<p class="mute">Ostatnia kopia: ${last}.</p>`}
    ${s.host ? "" : html`<section class="card"><h2>Wygląd</h2><div class="chips">${(["auto", "light", "dark"] as Theme[]).map((t) => html`<button class="chipb${s.theme === t ? " on" : ""}" data-act="theme" data-theme="${t}">${THEME_LABEL[t]}</button>`)}</div>
      <p class="note">Auto podąża za ustawieniem telefonu.</p></section>`}
    <section class="card"><h2>Kopia</h2><button class="btn" data-act="export">Pobierz kopię (JSON)</button>
      <p class="note">To cały dziennik cyklu w formacie JSON; można go wczytać z powrotem w tym module. Kopia zawiera też dane o zdrowiu, jeśli zostały wpisane.</p></section>
    <section class="card"><h2>Import</h2><input type="file" id="file" accept=".json,application/json" data-change="file">
      <textarea id="paste" rows="3" aria-label="Wklej JSON z dziennikiem cyklu" placeholder="…albo wklej JSON"></textarea><button class="btn sec" data-act="import">Wczytaj</button>
      <p class="note">Po wczytaniu wybierasz: połączyć z istniejącymi wpisami albo zastąpić.</p></section>
    <section class="card"><h2>Leki i choroby (do raportu)</h2>
      <label class="lab" for="hc">Choroby rozpoznane</label><input type="text" id="hc" placeholder="np. choroba A, choroba B" value="${hl.conditions ?? ""}">
      <label class="lab" for="hm">Leki</label><input type="text" id="hm" placeholder="np. lek X" value="${hl.meds ?? ""}">
      <label class="lab" for="ht">Ostatnie TSH</label><input type="text" id="ht" placeholder="wynik i data" value="${hl.tsh ?? ""}">
      <label class="lab" for="hl">Morfologia / ferrytyna</label><input type="text" id="hl" placeholder="np. Hb, ferrytyna, data" value="${hl.labs ?? ""}">
      <label class="lab" for="mode">Tryb prognoz</label>
      <select id="mode" data-change="mode">${([["normal", "Zwykły cykl"], ["hormonal", "Antykoncepcja hormonalna (bez prognoz płodności)"], ["pause", "Ciąża, połóg lub karmienie (bez prognoz)"]] as const).map(([v, l]) => html`<option value="${v}"${v === (s.db.settings.mode ?? "normal") ? raw(" selected") : ""}>${l}</option>`)}</select>
      <label class="lab" for="lut">Faza lutealna (dni)</label>
      <select id="lut" data-change="luteal">${[12, 13, 14, 15, 16].map((d) => html`<option value="${d}"${d === (s.db.settings.lutealDays ?? 14) ? raw(" selected") : ""}>${d}${d === 14 ? " (typowo)" : ""}</option>`)}</select>
      <button class="btn sec" data-act="health">Zapisz</button></section>
    <section class="card"><h2>Raport dla lekarza / AI</h2>
      <label class="check"><input type="checkbox" id="wd" data-change="dates"${s.withDates ? raw(" checked") : ""}> Dołącz informacje o stosunkach (domyślnie wyłączone)</label>
      <textarea id="brief" rows="10" readonly>${brief}</textarea>
      <button class="btn" data-act="copy">Kopiuj raport</button><button class="btn sec" data-act="dlb">Pobierz .txt</button></section>
    <section class="card"><h2>Zarządzanie</h2><button class="btn danger" data-act="wipe">Usuń cały dziennik cyklu</button></section>`;
};

const tog = (t: string, label: string, on: boolean): SafeHtml => html`<button type="button" class="tog${on ? " on" : ""}" data-tog="${t}" aria-pressed="${on}">${label}</button>`;
const seg = (name: string, max: number, val: number): SafeHtml => html`<span class="lab" id="seg-${name}">${name}</span><div class="seg" data-seg="${name}" role="group" aria-labelledby="seg-${name}">
  ${Array.from({ length: max + 1 }, (_, n) => html`<button type="button" data-v="${n}" aria-pressed="${val === n}" class="${val === n ? "on" : ""}">${n}</button>`)}</div>`;

export const sheetDay = (a: Analysis, date: string): SafeHtml => {
  const f = flagsFor(a.entries, date), i = a.info(date);
  const tags = [i.bleed && "okres", i.predBleed && "prognoza okresu", i.level === "high" && "wysoka szansa", i.level === "mid" && "podwyższona szansa", i.ovulation && "owulacja (szac.)"].filter(Boolean).join(" · ");
  return html`<h2>${dfmt(date, { weekday: "long", day: "numeric", month: "long", year: "numeric" })}</h2>${tags ? html`<p class="mute">${tags}</p>` : ""}
    <input type="hidden" id="solotype" value="${f.soloType}">
    <div class="togs">${tog("period_start", "Start okresu", f.start)}${tog("period_end", "Koniec okresu", f.end)}${tog("spotting", "Plamienie", f.spot)}${tog("clot", "Skrzepy", f.clot)}
      ${tog("sex", "Stosunek", f.sex)}${tog("protected", "Zabezpieczenie", f.prot)}${tog("him", "Solo — partner/ka", f.solo)}${tog("lh", "Test LH +", f.lh)}</div>
    ${seg("Obfitość", 5, f.flow)}${seg("Ból", 10, f.pain)}
    <label class="check"><input type="checkbox" id="lim"${f.limits ? raw(" checked") : ""}> Ból psuje sen albo pracę</label>
    <input type="text" id="pill" aria-label="Lek, dawka" placeholder="Lek, dawka" value="${f.pill}">
    <span class="lab" id="seg-test">Test ciążowy</span><div class="seg" data-seg="test" role="group" aria-labelledby="seg-test">
      <button type="button" data-v="" aria-pressed="${!f.test}" class="${!f.test ? "on" : ""}">brak</button><button type="button" data-v="neg" aria-pressed="${f.test === "neg"}" class="${f.test === "neg" ? "on" : ""}">ujemny</button><button type="button" data-v="pos" aria-pressed="${f.test === "pos"}" class="${f.test === "pos" ? "on" : ""}">dodatni</button></div>
    <textarea id="note" rows="3" aria-label="Notatka dnia" placeholder="Notatka dnia">${f.note}</textarea>
    <button class="btn" data-act="save-day" data-date="${date}">Zapisz</button><button class="btn sec" data-act="close">Anuluj</button>`;
};
export const sheetImport = (incoming: number, current: number, host = false): SafeHtml => html`<h2>Wczytać ${incoming} ${plN(incoming, "wpis", "wpisy", "wpisów")}?</h2>
  <p class="mute">Teraz w aplikacji: ${current} ${plN(current, "wpis", "wpisy", "wpisów")}.</p><button class="btn" data-act="merge">Połącz (dodaj brakujące)</button>
  <button class="btn sec" data-act="replace">Zastąp wszystko</button><button class="btn sec" data-act="close">Anuluj</button>
  <p class="note">${host ? "Przed zastąpieniem aplikacja zachowuje poprzednią wersję dziennika: można ją przywrócić w Ustawienia → Moduł Cykl." : "Przed zastąpieniem aplikacja zachowuje poprzednią wersję danych w pamięci przeglądarki."}</p>`;
export const sheetWipe = (host = false): SafeHtml => html`<h2>Usunąć dziennik cyklu?</h2><p class="mute">${host ? "Przed usunięciem zostaje poprzednia wersja dziennika: można ją przywrócić w Ustawienia → Moduł Cykl. Zrób też kopię w Ustawienia → Kopie zapasowe i dane." : "Tej operacji nie da się cofnąć. Najpierw pobierz kopię."}</p>
  <button class="btn danger" data-act="wipe-yes">Tak, usuń dane</button><button class="btn sec" data-act="close">Anuluj</button>`;

export const renderTab = (s: UiState): SafeHtml => {
  const a = analyze(s.db, s.today);
  return s.tab === "dzis" ? viewToday(a, s) : s.tab === "kal" ? viewCalendar(a, s) : s.tab === "wyk" ? viewCharts(a, s) : viewData(a, s);
};
export const shiftMonth = (month: string, by: number): string => {
  const [y, m] = month.split("-").map(Number) as [number, number];
  const d = new Date(y, m - 1 + by, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};

import type { Db, DayForm, Entry } from "./types.ts";
import { todayIso, plN } from "./dates.ts";
import { mergeDb, parseBackup, saveDay, toggleEntry, emptyDb } from "./model.ts";
import { analyze } from "./analysis.ts";
import { buildBrief } from "./report.ts";
import { KEY, UndoStack, keepPrevious, loadDb, loadTheme, nextTheme, saveDb, saveTheme } from "./store.ts";
import type { Theme } from "./store.ts";
import { renderTab, shiftMonth, sheetDay, sheetImport, sheetWipe } from "./views.ts";
import type { UiState } from "./views.ts";

const $ = (id: string): HTMLElement | null => document.getElementById(id);

export const start = (): void => {
  const app = $("app")!, sheet = $("sheet")!, mask = $("mask")!, toastEl = $("toast")!;
  const store = window.localStorage;
  const today = todayIso();
  const state: UiState = {
    db: loadDb(store), tab: "dzis", month: today.slice(0, 7), range: 12, selCycle: null,
    today, theme: loadTheme(store), withDates: false, host: document.documentElement.getAttribute("data-host") === "leki",
  };
  const undo = new UndoStack();
  let pending: Db | null = null;
  let pickedFile: File | null = null;
  let forceEnd = false;

  /* ---------- motyw ---------- */
  const applyTheme = (): void => {
    const root = document.documentElement;
    if (state.theme === "auto") root.removeAttribute("data-theme"); else root.setAttribute("data-theme", state.theme);
    const bg = getComputedStyle(root).getPropertyValue("--bg").trim();
    $("tc")?.setAttribute("content", bg || "#f6f1ea");
  };
  const embedded = window.parent !== window;
  const setTheme = (t: Theme, fromHost = false): void => {
    state.theme = t; saveTheme(store, t); applyTheme(); render();
    if (embedded && !fromHost) window.parent.postMessage({ type: "cykl-theme-changed", theme: t }, "*");
  };
  /* osadzenie w aplikacji nadrzędnej: przyjmujemy tylko motyw i tylko od rodzica */
  window.addEventListener("message", (e: MessageEvent) => {
    const d = e.data as { type?: string; theme?: string } | null;
    if (e.source !== window.parent || !d || d.type !== "cykl-theme") return;
    if (d.theme === "auto" || d.theme === "light" || d.theme === "dark") setTheme(d.theme, true);
  });

  /* ---------- UI pomocnicze ---------- */
  let toastTimer = 0;
  const toast = (msg: string, withUndo = false): void => {
    toastEl.textContent = msg;
    if (withUndo && undo.size) {
      const b = document.createElement("button");
      b.textContent = "Cofnij"; b.setAttribute("data-act", "undo"); b.className = "toast-btn";
      toastEl.appendChild(b);
    }
    toastEl.className = "toast show";
    clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => { toastEl.className = "toast"; }, withUndo ? 9000 : 2600);
  };
  const showSheet = (h: { value: string }): void => { sheet.innerHTML = `<div class="grab"></div>${h.value}`; sheet.hidden = false; mask.hidden = false; };
  const closeSheet = (): void => { sheet.hidden = true; mask.hidden = true; };
  const persist = (): boolean => { const ok = saveDb(store, state.db); if (!ok) toast("Nie udało się zapisać danych. Zrób kopię."); return ok; };
  const download = (name: string, text: string, mime: string): void => {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([text], { type: mime }));
    a.download = name; a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
  };

  const render = (): void => {
    state.today = todayIso();
    const before: Record<string, number | undefined> = { cscroll: $("cscroll")?.scrollLeft, mscroll: $("mscroll")?.scrollLeft };
    app.innerHTML = renderTab(state).value;
    for (const id of ["cscroll", "mscroll"]) {
      const el = $(id);
      if (el) el.scrollLeft = forceEnd || before[id] === undefined ? el.scrollWidth : before[id]!;
    }
    forceEnd = false;
    document.querySelectorAll<HTMLElement>(".tabs button").forEach((b) => {
      const on = b.dataset.tab === state.tab;
      b.classList.toggle("on", on);
      if (on) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
    });
  };
  const mutate = (entries: Entry[], msg: string): void => {
    undo.push(state.db.entries);
    state.db = { ...state.db, entries };
    const ok = persist(); render(); if (ok) toast(msg, true);
  };
  const setSettings = (patch: Record<string, unknown>): void => {
    state.db = { ...state.db, settings: { ...state.db.settings, ...patch } };
    persist(); render();
  };
  const val = (id: string): string => ($(id) as HTMLInputElement | null)?.value.trim() ?? "";
  const segVal = (name: string): string => sheet.querySelector<HTMLElement>(`[data-seg="${name}"] .on`)?.dataset.v ?? "";

  /* ---------- akcje ---------- */
  const actions: Record<string, (el: HTMLElement) => void> = {
    tab: (el) => { state.tab = el.dataset.tab as UiState["tab"]; render(); window.scrollTo(0, 0); },
    "theme-cycle": () => setTheme(nextTheme(state.theme)),
    theme: (el) => setTheme(el.dataset.theme as Theme),
    prev: () => { state.month = shiftMonth(state.month, -1); render(); },
    next: () => { state.month = shiftMonth(state.month, 1); render(); },
    day: (el) => showSheet(sheetDay(analyze(state.db, state.today), el.dataset.date!)),
    close: closeSheet,
    flip: (el) => mutate(toggleEntry(state.db.entries, state.today, el.dataset.type!), "Zapisane"),
    range: (el) => { state.range = Number(el.dataset.n); state.selCycle = null; forceEnd = true; render(); },
    cyc: (el) => { state.selCycle = Number(el.dataset.i); render(); },
    undo: () => {
      const prev = undo.pop();
      if (!prev) return;
      state.db = { ...state.db, entries: prev }; const ok = persist(); render(); if (ok) toast("Cofnięto");
    },
    "save-day": (el) => {
      const form: DayForm = {
        toggles: [...sheet.querySelectorAll<HTMLElement>(".tog.on")].map((b) => b.dataset.tog!),
        flow: Number(segVal("Obfitość") || 0), pain: Number(segVal("Ból") || 0),
        limits: ($("lim") as HTMLInputElement).checked, pill: val("pill"), test: segVal("test"), note: val("note"),
        soloType: val("solotype") === "masturb" ? "masturb" : "him",
      };
      closeSheet();
      mutate(saveDay(state.db.entries, el.dataset.date!, form), "Zapisane");
    },
    export: () => {
      setSettings({ lastExport: todayIso() });
      download(`luna_backup_${todayIso()}.json`, JSON.stringify(state.db, null, 2), "application/json");
      toast("Kopia pobrana");
    },
    import: () => {
      const go = (text: string): void => {
        try { pending = parseBackup(text); showSheet(sheetImport(pending.entries.length, state.db.entries.length)); }
        catch (err) { toast(`Import: ${(err as Error).message}`); }
      };
      if (pickedFile) { const r = new FileReader(); r.onload = () => go(String(r.result)); r.readAsText(pickedFile); }
      else if (val("paste")) go(($("paste") as HTMLTextAreaElement).value);
      else toast("Wskaż plik albo wklej JSON");
    },
    merge: () => {
      if (!pending) return;
      const r = mergeDb(state.db, pending);
      undo.push(state.db.entries);
      state.db = { entries: r.entries, cycles: r.cycles, settings: r.settings };
      pending = null; const ok = persist(); closeSheet(); render(); if (ok) toast(`Dodano ${r.added} ${plN(r.added, "nowy wpis", "nowe wpisy", "nowych wpisów")}`, true);
    },
    replace: () => {
      if (!pending) return;
      keepPrevious(store, state.db);
      state.db = pending; pending = null; const ok = persist(); closeSheet(); render(); if (ok) toast(`Wczytano ${state.db.entries.length} ${plN(state.db.entries.length, "wpis", "wpisy", "wpisów")}`);
    },
    health: () => setSettings({ health: { conditions: val("hc"), meds: val("hm"), tsh: val("ht"), labs: val("hl") } }),
    copy: async () => {
      const text = buildBrief(analyze(state.db, state.today), state.db, state.withDates);
      try { await navigator.clipboard.writeText(text); toast("Skopiowano"); } catch { ($("brief") as HTMLTextAreaElement).select(); toast("Zaznacz i skopiuj"); }
    },
    dlb: () => download(`cykl-raport-${todayIso()}.txt`, buildBrief(analyze(state.db, state.today), state.db, state.withDates), "text/plain"),
    wipe: () => showSheet(sheetWipe()),
    "wipe-yes": () => { keepPrevious(store, state.db); state.db = emptyDb(); const ok = persist(); closeSheet(); render(); if (ok) toast("Usunięto"); },
  };

  document.addEventListener("click", (e) => {
    const target = e.target as Element;
    if (target.closest("#mask")) { closeSheet(); return; }
    const tog = target.closest<HTMLElement>("[data-tog]");
    if (tog) { const on = tog.classList.toggle("on"); tog.setAttribute("aria-pressed", String(on)); return; }
    const segBtn = target.closest<HTMLElement>(".seg button");
    if (segBtn) {
      const group = segBtn.parentElement!;
      const was = segBtn.classList.contains("on");
      group.querySelectorAll("button").forEach((b) => b.classList.remove("on"));
      if (!(was && group.dataset.seg !== "test")) segBtn.classList.add("on");
      return;
    }
    const el = target.closest<HTMLElement>("[data-act]");
    if (el) actions[el.dataset.act!]?.(el);
  });
  document.addEventListener("keydown", (e) => {
    if ((e.key === "Enter" || e.key === " ") && (e.target as Element).closest?.("g[data-act]")) { e.preventDefault(); ((e.target as Element).closest("g[data-act]") as unknown as HTMLElement).dispatchEvent(new MouseEvent("click", { bubbles: true })); }
    if (e.key === "Escape") closeSheet();
  });
  document.addEventListener("change", (e) => {
    const el = e.target as HTMLInputElement;
    switch (el.dataset.change) {
      case "age": setSettings({ ageBand: el.value }); break;
      case "luteal": setSettings({ lutealDays: Number(el.value) }); break;
      case "dates": state.withDates = el.checked; render(); break;
      case "file": pickedFile = el.files?.[0] ?? null; break;
    }
  });

  /* przesunięcie palcem w kalendarzu */
  let sx = 0, sy = 0, swiping = false;
  document.addEventListener("touchstart", (e) => {
    swiping = Boolean((e.target as Element).closest("#cal"));
    if (swiping) { sx = e.touches[0]!.clientX; sy = e.touches[0]!.clientY; }
  }, { passive: true });
  document.addEventListener("touchend", (e) => {
    if (!swiping) return;
    swiping = false;
    const dx = e.changedTouches[0]!.clientX - sx, dy = e.changedTouches[0]!.clientY - sy;
    if (Math.abs(dx) > 60 && Math.abs(dy) < 40) { state.month = shiftMonth(state.month, dx < 0 ? 1 : -1); render(); }
  });

  applyTheme();
  render();
  if (embedded) window.parent.postMessage({ type: "cykl-ready" }, "*");
  try { void navigator.storage?.persist?.(); } catch { /* brak wsparcia */ }
  void KEY;
};

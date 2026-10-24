/* Most między modułem „Cykl” a aplikacją. Wstawiany na początku <head> strony modułu (kod modułu pozostaje bez zmian).
   Strona jest otwarta bez dostępu do sieci; wszystko, co trwałe, idzie przez obiekt LekiHost (Kotlin). */
(function () {
  var H = window.LekiHost;
  if (!H) return;
  var KEYS = { luna_db_v6: 1, luna_db_v6_prev: 1 };
  document.documentElement.setAttribute("data-host", "leki");   // moduł wie, że działa w aplikacji (kopie i motyw obsługuje ona)
  var theme = H.theme() === "dark" ? "dark" : "light";

  // localStorage zastąpiony pamięcią aplikacji: dane są w jednym miejscu, wchodzą do kopii zapasowej i nie zależą od danych przeglądarki
  var store = {
    getItem: function (k) {
      if (k === "cykl_theme") return theme;     // motyw wybiera aplikacja
      if (!KEYS[k]) return null;
      var v = H.get(k);
      return v === "" ? null : v;
    },
    setItem: function (k, v) {
      if (k === "cykl_theme") return;
      if (!KEYS[k] || !H.set(k, String(v))) throw new Error("storage");
    },
    removeItem: function (k) { if (KEYS[k]) H.remove(k); },
    clear: function () {},
    key: function () { return null; },
    get length() { return 0; }
  };
  try { Object.defineProperty(window, "localStorage", { value: store, configurable: true }); } catch (e) {}

  // „pobieranie” pliku: moduł tworzy odnośnik z blobem, a aplikacja zapisuje go przez systemowe okno wyboru miejsca
  function say(msg) {
    var t = document.getElementById("toast");
    if (t) { t.textContent = msg; t.className = "toast show"; setTimeout(function () { t.className = "toast"; }, 2600); }
  }
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var a = this;
    if (a.download && /^blob:/.test(a.href)) {
      fetch(a.href).then(function (r) { return r.blob(); }).then(function (b) {
        var fr = new FileReader();
        fr.onload = function () {
          var s = String(fr.result), i = s.indexOf(",");
          if (!H.save(a.download, b.type || "application/octet-stream", s.slice(i + 1))) say("Plik jest za duży, aby go zapisać");
        };
        fr.readAsDataURL(b);
      }).catch(function () { say("Nie udało się zapisać pliku"); });
      return;
    }
    return click.call(a);
  };

  // schowek przez system (w osadzonym widoku API przeglądarki bywa zablokowane)
  try {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: function (t) { H.copy(String(t)); return Promise.resolve(); } }, configurable: true
    });
  } catch (e) {}

  // błędy skryptu strony widać w aplikacji (inaczej awaria w module jest niewidoczna)
  window.addEventListener("error", function (e) { H.error("wiersz " + (e.lineno || "?") + ":" + (e.colno || "?")); });
  window.addEventListener("unhandledrejection", function (e) { H.error("odrzucona obietnica"); });

  // Klawiatura zmienia rozmiar okna strony. Elementy position:fixed (arkusz, dolny pasek) potrafią wtedy zostać na starym miejscu,
  // więc (1) dotknięcie przycisku najpierw zabiera fokus polu tekstowemu, a (2) po każdej zmianie rozmiaru układ jest wymuszany ponownie.
  document.addEventListener("click", function (e) {
    var a = document.activeElement;
    if (a && (a.tagName === "TEXTAREA" || a.tagName === "INPUT" || a.tagName === "SELECT") && e.target.closest && e.target.closest("[data-act]")) a.blur();
  }, true);
  var settle;
  function relayout() {
    var tabs = document.querySelector(".tabs");
    if (tabs) { tabs.style.top = "0.5px"; requestAnimationFrame(function () { tabs.style.top = ""; }); }
  }
  window.addEventListener("resize", function () { clearTimeout(settle); settle = setTimeout(relayout, 120); });

  // okno szczegółów (dzień, import, usuwanie) jest zwykłą stroną w przepływie dokumentu (patrz host.css): reszta strony się chowa,
  // a po zamknięciu wraca przewinięcie. Przycisk Wstecz ma je zamknąć, a nie wychodzić z zakładki.
  document.addEventListener("DOMContentLoaded", function () {
    var sheet = document.getElementById("sheet");
    if (!sheet || !window.MutationObserver) return;
    var savedY = 0, wasOpen = false, backTo = "";
    new MutationObserver(function () {
      var open = !sheet.hidden;
      H.sheet(open);
      if (open && !wasOpen) {
        savedY = window.pageYOffset || 0;
        var ae = document.activeElement;
        backTo = ae && ae.getAttribute ? (ae.getAttribute("data-date") || "") : "";
      }
      document.documentElement.classList.toggle("sheet-open", open);
      if (open) {
        // pasek na górze: powrót i (przy dniu) szybki zapis, bo formularz jest długi
        if (!sheet.querySelector(".sheet-bar")) {
          var bar = document.createElement("div");
          bar.className = "sheet-bar";
          var back = document.createElement("button");
          back.type = "button"; back.className = "btn sec"; back.setAttribute("data-act", "close"); back.textContent = "‹ Wróć";
          bar.appendChild(back);
          var save = sheet.querySelector("[data-act=save-day]");
          if (save) {
            var top = document.createElement("button");
            top.type = "button"; top.className = "btn"; top.setAttribute("data-act", "save-day");
            top.setAttribute("data-date", save.getAttribute("data-date") || ""); top.textContent = "Zapisz";
            bar.appendChild(top);
          }
          sheet.insertBefore(bar, sheet.firstChild);
        }
        window.scrollTo(0, 0);
        var h = sheet.querySelector("h2"); if (h) { h.tabIndex = -1; h.focus({ preventScroll: true }); }
      } else if (wasOpen) {
        window.scrollTo(0, savedY);
        // fokus wraca na dzień, z którego otwarto okno (klawiatura i czytnik ekranu nie tracą miejsca)
        if (backTo) { var el = document.querySelector('[data-date="' + backTo + '"]'); if (el && el.focus) el.focus({ preventScroll: true }); }
      }
      wasOpen = open;
    }).observe(sheet, { attributes: true, attributeFilter: ["hidden"] });
    // menu u góry zamyka otwarte okno, zanim zmieni kartę
    document.addEventListener("click", function (e) {
      if (!sheet.hidden && e.target.closest && e.target.closest(".tabs [data-act=tab]")) document.getElementById("mask").click();
    }, true);
  });

  // Dostępność: strona przerysowuje całą zawartość, więc nie jest „na żywo” (czytnik ekranu nie czyta jej od nowa),
  // a kafelki dni dostają opis po polsku (data, okres, prognoza, szansa, zapisy)
  document.addEventListener("DOMContentLoaded", function () {
    var app = document.getElementById("app");
    if (!app) return;
    app.removeAttribute("aria-live");
    // menu jest wizualnie na górze, więc ma być też pierwsze w kolejności fokusu
    var tabsNav = document.querySelector(".tabs");
    if (tabsNav) document.body.insertBefore(tabsNav, app);
    var LV = { high: "okno płodne bardzo prawdopodobne", mid: "okno płodne możliwe", low: "poza oknem płodnym", bleed: "okres" };
    function labels() {
      document.querySelectorAll(".sb[aria-label]").forEach(function (b) {
        var l = b.getAttribute("aria-label").replace(/szansa: (\w+)$/, function (_, k) { return LV[k] || k; });
        l = l.replace(/^(\d{4}-\d{2}-\d{2})/, function (d) { return new Date(d + "T12:00:00").toLocaleDateString("pl-PL", { weekday: "long", day: "numeric", month: "long" }); });
        b.setAttribute("aria-label", l);
      });
      document.querySelectorAll(".day[data-date]").forEach(function (b) {
        var d = new Date(b.getAttribute("data-date") + "T12:00:00").toLocaleDateString("pl-PL", { weekday: "long", day: "numeric", month: "long" });
        var s = [b.classList.contains("bleed") && "okres", b.classList.contains("pred") && "prognoza okresu", b.classList.contains("lv-high") && "okno płodne bardzo prawdopodobne",
          b.classList.contains("lv-mid") && "okno płodne możliwe", b.querySelector(".dot.sex") && "stosunek", b.querySelector(".dot.spot") && "plamienie",
          b.querySelector(".dot.lh") && "test LH", b.querySelector(".ov") && "owulacja (szacunkowo)"].filter(Boolean).join(", ");
        b.setAttribute("aria-label", s ? d + ": " + s : d);
      });
    }
    if (window.MutationObserver) new MutationObserver(labels).observe(app, { childList: true });
    labels();
  });
})();

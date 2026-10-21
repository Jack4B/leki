/* Odświeża www/vendor (fonty Geist i ikony Phosphor), żeby aplikacja nie pobierała niczego z internetu.
   Pliki w www/vendor są w repozytorium, więc zwykłe budowanie tego nie wymaga. Uruchom tylko przy zmianie ikon lub wersji:

     npm i --no-save @phosphor-icons/web@2.1.1 @fontsource-variable/geist @fontsource-variable/geist-mono
     node scripts/vendor-assets.js
*/
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const nm = path.join(root, 'node_modules');
const out = path.join(root, 'www/vendor');
const app = ['www/index.html', 'www/js/app.js'].map(f => fs.readFileSync(path.join(root, f), 'utf8')).join('\n');

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

/* Fonty: tylko zakresy łaciński i łaciński rozszerzony (polskie znaki) */
let fontCss = '/* Geist i Geist Mono (SIL OFL 1.1), zmienna waga. Źródło: @fontsource-variable. */\n';
const faces = [
  ['geist', 'Geist Variable', 'latin', 'U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD'],
  ['geist', 'Geist Variable', 'latin-ext', 'U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF'],
  ['geist-mono', 'Geist Mono Variable', 'latin', 'U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD'],
  ['geist-mono', 'Geist Mono Variable', 'latin-ext', 'U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF']
];
for (const [pkg, family, set, range] of faces) {
  const file = `${pkg}-${set}-wght-normal.woff2`;
  copy(path.join(nm, '@fontsource-variable', pkg, 'files', file), path.join(out, 'fonts', file));
  fontCss += `@font-face{font-family:'${family}';font-style:normal;font-display:swap;font-weight:100 900;src:url(./${file}) format('woff2-variations');unicode-range:${range}}\n`;
}
fs.writeFileSync(path.join(out, 'fonts/fonts.css'), fontCss);
copy(path.join(nm, '@fontsource-variable/geist/LICENSE'), path.join(out, 'fonts/LICENSE-OFL.txt'));

/* Ikony: tylko te, których używa aplikacja, w trzech wagach (ph, ph-bold, ph-fill) */
const used = { ph: new Set(), 'ph-bold': new Set(), 'ph-fill': new Set() };
for (const m of app.matchAll(/\b(ph|ph-bold|ph-fill)\s+(ph-[a-z0-9-]+)/g)) used[m[1]].add(m[2]);
for (const m of app.matchAll(/'(ph-[a-z0-9-]+)'/g)) used.ph.add(m[1]); /* ikony wybierane w kodzie, np. pora dnia */
const weights = [['ph', 'regular', 'Phosphor'], ['ph-bold', 'bold', 'Phosphor-Bold'], ['ph-fill', 'fill', 'Phosphor-Fill']];
let icoCss = '/* Phosphor Icons 2.1.1 (MIT), tylko używane ikony. Źródło: @phosphor-icons/web. */\n';
for (const [cls, dir, font] of weights) {
  const src = path.join(nm, '@phosphor-icons/web/src', dir);
  const woff = fs.readdirSync(src).find(f => f.endsWith('.woff2'));
  copy(path.join(src, woff), path.join(out, 'phosphor', woff));
  icoCss += `@font-face{font-family:"${font}";src:url("./${woff}") format("woff2");font-weight:normal;font-style:normal;font-display:block}\n`
    + `.${cls}{font-family:"${font}"!important;speak:never;font-style:normal;font-weight:normal;font-variant:normal;text-transform:none;line-height:1;letter-spacing:0;-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale}\n`;
  const css = fs.readFileSync(path.join(src, 'style.css'), 'utf8');
  for (const name of [...used[cls]].sort()) {
    const m = css.match(new RegExp('\\.' + cls + '\\.' + name + ':before\\s*\\{\\s*content:\\s*"([^"]+)";'));
    if (!m) throw new Error('Brak ikony ' + cls + ' ' + name);
    icoCss += `.${cls}.${name}:before{content:"${m[1]}"}\n`;
  }
}
fs.writeFileSync(path.join(out, 'phosphor/phosphor.css'), icoCss);
copy(path.join(nm, '@phosphor-icons/web/LICENSE'), path.join(out, 'phosphor/LICENSE-MIT.txt'));
console.log('Zaktualizowano www/vendor:', Object.fromEntries(Object.entries(used).map(([k, v]) => [k, v.size])));

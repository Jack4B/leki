/* Generuje ikony Androida (PNG) z assets/*.svg do native/res. Pliki są w repozytorium, więc zwykłe budowanie tego nie wymaga.
   Uruchom tylko po zmianie rysunku ikony:

     npm i --no-save playwright && node scripts/make-icons.js

   Wymaga przeglądarki Chromium (PLAYWRIGHT_BROWSERS_PATH albo `npx playwright install chromium`). */
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const root = path.join(__dirname, '..');
const res = path.join(root, 'native/res');
const read = f => fs.readFileSync(path.join(root, 'assets', f), 'utf8');
const fg = read('icon-foreground.svg'), full = read('icon.svg');
const COLOR = '#0B6B58';

const dens = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const splash = { mdpi: [320, 480], hdpi: [480, 800], xhdpi: [720, 1280], xxhdpi: [960, 1600], xxxhdpi: [1280, 1920] };

(async () => {
  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage();
  async function png(html, w, h, file) {
    await page.setViewportSize({ width: w, height: h });
    await page.setContent('<style>html,body{margin:0;background:transparent}svg{display:block;width:' + w + 'px;height:' + h + 'px}</style>' + html);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await page.screenshot({ path: file, omitBackground: true, clip: { x: 0, y: 0, width: w, height: h } });
  }
  const round = full.replace('<rect width="108" height="108" fill="' + COLOR + '"/>', '<circle cx="54" cy="54" r="54" fill="' + COLOR + '"/>');
  const square = full.replace('<rect width="108" height="108" fill="' + COLOR + '"/>', '<rect width="108" height="108" rx="24" fill="' + COLOR + '"/>');
  for (const [d, k] of Object.entries(dens)) {
    const dir = path.join(res, 'mipmap-' + d);
    await png(square, Math.round(48 * k), Math.round(48 * k), path.join(dir, 'ic_launcher.png'));
    await png(round, Math.round(48 * k), Math.round(48 * k), path.join(dir, 'ic_launcher_round.png'));
    await png(fg, Math.round(108 * k), Math.round(108 * k), path.join(dir, 'ic_launcher_foreground.png'));
    const [w, h] = splash[d];
    for (const o of [['port', w, h], ['land', h, w]]) {
      const f = path.join(res, 'drawable-' + o[0] + '-' + d, 'splash.png');
      await png('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1" preserveAspectRatio="none"><rect width="1" height="1" fill="' + COLOR + '"/></svg>', o[1], o[2], f);
    }
  }
  await png('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1" preserveAspectRatio="none"><rect width="1" height="1" fill="' + COLOR + '"/></svg>', 480, 800, path.join(res, 'drawable', 'splash.png'));
  await browser.close();
  console.log('Ikony zapisane w native/res');
})();

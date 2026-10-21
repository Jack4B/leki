/* Generuje ikony launchera (PNG) z assets/*.svg do app/src/main/res. Pliki są w repozytorium, więc zwykłe budowanie tego nie wymaga.
   Uruchom tylko po zmianie rysunku ikony:

     npm i --no-save playwright && node scripts/make-icons.js

   Wymaga przeglądarki Chromium (PLAYWRIGHT_BROWSERS_PATH albo `npx playwright install chromium`). */
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const root = path.join(__dirname, '..');
const res = path.join(root, 'app/src/main/res');
const read = f => fs.readFileSync(path.join(root, 'assets', f), 'utf8');
const fg = read('icon-foreground.svg'), full = read('icon.svg');
const COLOR = '#0B6B58';

const dens = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

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
  }
  await browser.close();
  console.log('Ikony zapisane w app/src/main/res');
})();

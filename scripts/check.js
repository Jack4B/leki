/* Szybka kontrola przed budowaniem: składnia skryptów i zgodność numeru wersji w kodzie aplikacji i w package.json. */
const fs = require('fs'), path = require('path'), vm = require('vm');
const root = path.join(__dirname, '..');
let bad = 0;
for (const f of ['www/js/app.js', 'scripts/patch-android.js', 'scripts/vendor-assets.js', 'scripts/make-icons.js', 'scripts/check.js']) {
  try { new vm.Script(fs.readFileSync(path.join(root, f), 'utf8'), { filename: f }); }
  catch (e) { console.error('Błąd składni w ' + f + ': ' + e.message); bad++; }
}
const ver = require('../package.json').version;
const m = /APP_VERSION='([^']+)'/.exec(fs.readFileSync(path.join(root, 'www/js/app.js'), 'utf8'));
if (!m || m[1] !== ver) { console.error('Wersja w www/js/app.js (' + (m && m[1]) + ') różni się od package.json (' + ver + ').'); bad++; }
if (!fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8').includes('## ' + ver)) { console.error('CHANGELOG.md nie ma wpisu dla wersji ' + ver + '.'); bad++; }
if (bad) process.exit(1);
console.log('Kontrola OK, wersja ' + ver);

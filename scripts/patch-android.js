/* Uruchamiane po `cap add android` (i przed każdym lokalnym budowaniem):
   kopiuje własny kod Java, dopisuje uprawnienia, wyłącza kopię zapasową Androida i ustawia wersję aplikacji. */
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const pkg = require('../package.json');

/* Kod natywny: druk/PDF raportu */
const javaDir = path.join(root, 'android/app/src/main/java/pl/asystent/lekow');
fs.mkdirSync(javaDir, { recursive: true });
for (const f of ['PrintPlugin.java', 'MainActivity.java'])
  fs.copyFileSync(path.join(root, 'native/java', f), path.join(javaDir, f));

/* Manifest: uprawnienia przypomnień, dane tylko lokalnie (bez kopii do chmury Google) */
const mf = path.join(root, 'android/app/src/main/AndroidManifest.xml');
let x = fs.readFileSync(mf, 'utf8');
const perms = [
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  'android.permission.USE_EXACT_ALARM',
  'android.permission.SCHEDULE_EXACT_ALARM',
  'android.permission.VIBRATE'
];
let add = '';
for (const p of perms) if (!x.includes(p)) add += '    <uses-permission android:name="' + p + '" />\n';
if (add) x = x.replace('</manifest>', add + '</manifest>');
x = x.replace(/android:allowBackup="true"/, 'android:allowBackup="false"');
if (!/android:allowBackup="false"/.test(x)) throw new Error('Nie udało się wyłączyć allowBackup w AndroidManifest.xml');
fs.writeFileSync(mf, x);

/* Wersja: versionName z package.json, versionCode z ANDROID_VERSION_CODE (CI) albo z numeru wersji */
const m = /^(\d+)\.(\d+)\.(\d+)/.exec(pkg.version);
if (!m) throw new Error('package.json: wersja musi mieć postać x.y.z[-opis]');
const code = parseInt(process.env.ANDROID_VERSION_CODE, 10) || (+m[1] * 10000 + +m[2] * 100 + +m[3]);
const gr = path.join(root, 'android/app/build.gradle');
let g = fs.readFileSync(gr, 'utf8');
if (!/versionCode\s+\d+/.test(g) || !/versionName\s+"[^"]*"/.test(g)) throw new Error('Nie znaleziono versionCode/versionName w build.gradle');
g = g.replace(/versionCode\s+\d+/, 'versionCode ' + code).replace(/versionName\s+"[^"]*"/, 'versionName "' + pkg.version + '"');
fs.writeFileSync(gr, g);

console.log('Android: PrintPlugin, uprawnienia i wersja ' + pkg.version + ' (kod ' + code + ') ustawione.');

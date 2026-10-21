/* Uruchamiane po `cap add android` (i przed każdym lokalnym budowaniem):
   kopiuje własny kod Java i ikony, dopisuje uprawnienia, wyłącza kopię zapasową Androida i ustawia wersję aplikacji. */
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

/* Ikony: launcher (adaptacyjna i klasyczna), ekran startowy i mała ikona powiadomień */
const resSrc = path.join(root, 'native/res'), resDst = path.join(root, 'android/app/src/main/res');
(function copyDir(from, to) {
  fs.mkdirSync(to, { recursive: true });
  for (const e of fs.readdirSync(from, { withFileTypes: true })) {
    if (e.isDirectory()) copyDir(path.join(from, e.name), path.join(to, e.name));
    else fs.copyFileSync(path.join(from, e.name), path.join(to, e.name));
  }
})(resSrc, resDst);
fs.writeFileSync(path.join(resDst, 'values/ic_launcher_background.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0B6B58</color>\n</resources>\n');
fs.writeFileSync(path.join(resDst, 'drawable/ic_stat_pill.xml'),
  '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
  + '    <group android:rotation="-45" android:pivotX="12" android:pivotY="12">\n'
  + '        <path android:fillColor="#FFFFFF" android:pathData="M6,8.5 H18 A3.5,3.5 0 0 1 18,15.5 H6 A3.5,3.5 0 0 1 6,8.5 Z"/>\n'
  + '    </group>\n</vector>\n');

/* Wersja: versionName z package.json, versionCode z ANDROID_VERSION_CODE (CI) albo z numeru wersji */
const m = /^(\d+)\.(\d+)\.(\d+)/.exec(pkg.version);
if (!m) throw new Error('package.json: wersja musi mieć postać x.y.z[-opis]');
const code = parseInt(process.env.ANDROID_VERSION_CODE, 10) || (+m[1] * 10000 + +m[2] * 100 + +m[3]);
const gr = path.join(root, 'android/app/build.gradle');
let g = fs.readFileSync(gr, 'utf8');
if (!/versionCode\s+\d+/.test(g) || !/versionName\s+"[^"]*"/.test(g)) throw new Error('Nie znaleziono versionCode/versionName w build.gradle');
g = g.replace(/versionCode\s+\d+/, 'versionCode ' + code).replace(/versionName\s+"[^"]*"/, 'versionName "' + pkg.version + '"');
fs.writeFileSync(gr, g);

console.log('Android: PrintPlugin, ikony, uprawnienia i wersja ' + pkg.version + ' (kod ' + code + ') ustawione.');

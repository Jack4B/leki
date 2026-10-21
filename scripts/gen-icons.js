/* Generuje app/src/main/kotlin/pl/asystent/leki/ui/PhIcons.kt z ikon Phosphor (MIT) jako wektory Compose.
   Pliki są w repozytorium, więc zwykłe budowanie tego nie wymaga. Uruchom po zmianie listy ikon:

     npm i --no-save @phosphor-icons/core && node scripts/gen-icons.js
*/
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const assets = path.join(root, 'node_modules/@phosphor-icons/core/assets');
/* nazwa w kodzie -> [waga, nazwa pliku Phosphor] */
const icons = {
  Pill: ['regular', 'pill'], Heartbeat: ['regular', 'heartbeat'], Flask: ['regular', 'flask'], FileText: ['regular', 'file-text'],
  GearSix: ['regular', 'gear-six'], Notebook: ['regular', 'notebook'], CalendarCheck: ['regular', 'calendar-check'], CaretRight: ['regular', 'caret-right'],
  CaretLeft: ['regular', 'caret-left'], Copy: ['regular', 'copy'], DownloadSimple: ['regular', 'download-simple'], UploadSimple: ['regular', 'upload-simple'],
  LockKey: ['regular', 'lock-key'], Printer: ['regular', 'printer'], Plus: ['bold', 'plus'], Check: ['bold', 'check'], CheckCircle: ['fill', 'check-circle'],
  Drop: ['fill', 'drop'], Warning: ['fill', 'warning'], SunHorizon: ['regular', 'sun-horizon'], Sun: ['regular', 'sun'], CloudMoon: ['regular', 'cloud-moon'],
  MoonStars: ['regular', 'moon-stars'], Camera: ['regular', 'camera'], Image: ['regular', 'image'], FilePdf: ['regular', 'file-pdf'], Trash: ['regular', 'trash'],
  X: ['regular', 'x'], Bell: ['regular', 'bell'], Clock: ['regular', 'clock'], Info: ['regular', 'info'], FloppyDisk: ['regular', 'floppy-disk'],
  User: ['regular', 'user'], Sliders: ['regular', 'sliders-horizontal'], CalendarBlank: ['regular', 'calendar-blank'], Scan: ['regular', 'scan'],
  ArrowLeft: ['regular', 'arrow-left'], ListChecks: ['regular', 'list-checks'], Stethoscope: ['regular', 'stethoscope'], Table: ['regular', 'table'], Sparkle: ['regular', 'sparkle'], ShareNetwork: ['regular', 'share-network'], Pencil: ['regular', 'pencil-simple'],
};
let out = `package pl.asystent.leki.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/* Plik wygenerowany przez scripts/gen-icons.js z ikon Phosphor (licencja MIT). Nie edytuj ręcznie. */
object Ph {
    private fun icon(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 256f, 256f)
            .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
            .build()

`;
for (const [k, [w, n]] of Object.entries(icons)) {
  const file = path.join(assets, w, w === 'regular' ? n + '.svg' : n + '-' + w + '.svg');
  const svg = fs.readFileSync(file, 'utf8');
  const paths = [...svg.matchAll(/<path d="([^"]+)"/g)].map(m => m[1]);
  if (paths.length !== 1) throw new Error(k + ': oczekiwano jednej ścieżki, jest ' + paths.length);
  out += `    val ${k}: ImageVector by lazy { icon("${k}", "${paths[0]}") }\n`;
}
out += '}\n';
fs.writeFileSync(path.join(root, 'app/src/main/kotlin/pl/asystent/leki/ui/PhIcons.kt'), out);
console.log('Zapisano PhIcons.kt:', Object.keys(icons).length, 'ikon');

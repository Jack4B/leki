package pl.asystent.leki.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Ecg
import pl.asystent.leki.core.EcgAnalysis
import pl.asystent.leki.core.EcgDigitizer
import pl.asystent.leki.core.EcgInfo
import pl.asystent.leki.core.EcgMeta
import pl.asystent.leki.core.EcgResult
import pl.asystent.leki.core.EcgSeverity
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.deleteEcg
import pl.asystent.leki.core.hhmm
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.saveEcg
import pl.asystent.leki.ocr.OcrEngine
import pl.asystent.leki.ocr.ScanImages
import java.time.LocalDate
import java.time.LocalTime

private const val DPI = 200

/** Lista zapisów EKG w zakładce Badania. */
fun LazyListScope.ecgSection(s: AppState, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    item {
        Column(Modifier.padding(top = 14.dp)) {
            BodyText("Zapisy EKG z zegarka lub opaski (jedno odprowadzenie). Wczytaj wydruk PDF z aplikacji zegarka: aplikacja odtworzy przebieg, policzy tętno i odstępy między uderzeniami oraz wskaże ewentualne nieregularności do omówienia z lekarzem.")
            LekiButton("Dodaj EKG z pliku PDF", onAdd, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true, icon = Ph.Heartbeat)
        }
    }
    item { EcgMethods() }
    if (s.ecgs.isEmpty()) item { EmptyBox("Brak zapisów EKG.", Modifier.padding(top = 16.dp)) }
    else item {
        LekiCard(Modifier.padding(top = 16.dp)) {
            s.ecgs.sortedWith(compareByDescending<Ecg> { it.date }.thenByDescending { it.time }).forEachIndexed { i, e ->
                if (i > 0) Divider()
                val r = remember(e) { EcgAnalysis.analyze(e) }
                val attention = r.flags.count { it.severity == EcgSeverity.Attention }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(e.id) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(plDate(e.date) + ", " + e.time.hhmm(), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        val hr = r.hrMedian?.let { "tętno ${Math.round(it)}/min" } ?: (if (e.watchHr > 0) "tętno ${e.watchHr}/min" else "")
                        Text(listOf(hr, e.watchNote).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "zapis EKG" }, style = TextStyle(fontSize = 13.sp, color = pal.ink3))
                        if (attention > 0) Text("Do omówienia z lekarzem: $attention", style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.warn), modifier = Modifier.padding(top = 2.dp))
                    }
                    PhIcon(Ph.CaretRight, size = 18.dp, tint = pal.ink3)
                }
            }
        }
    }
}

/** Wczytywanie PDF z zegarka: render strony, odtworzenie przebiegu, analiza i odczyt daty oraz tętna z nagłówka (OCR na urządzeniu). */
@Composable
fun rememberEcgImporter(onDraft: (Ecg) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val draft = withContext(Dispatchers.Default) {
                    val bmp = ScanImages.renderFirstPdfPage(ctx, uri, DPI)
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val dig = EcgDigitizer.digitize(px, w, h, DPI / 25.4)
                    if (dig == null) { bmp.recycle(); null }
                    else {
                        // nagłówek wydruku: data, tętno i ocena rytmu zegarka (tekst jest obrócony razem ze stroną)
                        val meta = runCatching {
                            val upright = if (dig.vertical) Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(-90f) }, true) else bmp
                            OcrEngine().use { eng -> EcgMeta.parse(LabScan.rows(eng.recognize(upright))) }
                        }.getOrNull()
                        bmp.recycle()
                        val now = java.time.LocalDateTime.now()
                        Ecg(
                            newId(), meta?.date ?: now.toLocalDate(), meta?.time ?: now.toLocalTime().withSecond(0).withNano(0),
                            watchHr = meta?.hr ?: 0, watchNote = meta?.note.orEmpty(), fs = 125, cmv = EcgAnalysis.toRecord(dig.mv, dig.fs),
                        )
                    }
                }
                if (draft == null) toast("Nie znaleziono przebiegu EKG w tym pliku. Wczytaj PDF wyeksportowany z aplikacji zegarka.") else onDraft(draft)
            } catch (e: ScanImages.PdfException) {
                toast(e.message ?: "Nie można otworzyć PDF.")
            } catch (e: OutOfMemoryError) {
                toast("Za mało pamięci, żeby przetworzyć ten plik.")
            } catch (e: Exception) {
                toast("Nie udało się przetworzyć pliku EKG.")
            } finally {
                busy = false
            }
        }
    }
    if (busy) AlertDialog(
        onDismissRequest = {}, containerColor = pal.surface,
        title = { Text("Odczytuję EKG…", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)) },
        text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { CircularProgressIndicator(color = pal.accent); BodyText("Plik jest przetwarzany na telefonie.") } },
        confirmButton = {},
    )
    return { launcher.launch(arrayOf("application/pdf")) }
}

/** Przebieg w trzech pasach po 10 s, na tle siatki jak na papierze; trójkąty to wykryte uderzenia, szare tło to pominięte zakłócenia. */
@Composable
fun EcgWaveView(e: Ecg, r: EcgResult, modifier: Modifier = Modifier) {
    val sig = remember(e) { e.signalMv() }
    val grid = pal.line; val ink = pal.ink; val accent = pal.accent; val warn = pal.warn
    val rows = ((e.cmv.size + e.fs * 10 - 1) / (e.fs * 10)).coerceIn(1, 3)
    Canvas(modifier.fillMaxWidth().height((rows * 104).dp).semantics { contentDescription = "Przebieg EKG w ${rows} pasach po 10 sekund" }) {
        val rowH = size.height / rows
        val perRow = e.fs * 10
        val mvPx = rowH / 3.6f
        for (k in 0 until rows) {
            val top = k * rowH; val base = top + rowH * 0.62f
            var t = 0.0
            while (t <= 10.0) { val x = (t / 10.0 * size.width).toFloat(); drawLine(grid.copy(alpha = if ((t * 5).toInt() % 5 == 0) .9f else .45f), Offset(x, top), Offset(x, top + rowH - 2f), strokeWidth = 1f); t += 0.2 }
            var mv = -1.5f
            while (mv <= 2.0f) { val y = base - mv * mvPx; if (y in top..(top + rowH)) drawLine(grid.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f); mv += 0.5f }
            for (a in r.artifacts) {
                val a0 = a.start - k * 10.0; val a1 = a.endInclusive - k * 10.0
                if (a1 < 0 || a0 > 10) continue
                val x0 = (maxOf(0.0, a0) / 10 * size.width).toFloat(); val x1 = (minOf(10.0, a1) / 10 * size.width).toFloat()
                drawRect(warn.copy(alpha = .14f), Offset(x0, top), Size(x1 - x0, rowH - 2f))
            }
            val path = Path()
            val from = k * perRow; val to = minOf(sig.size, from + perRow)
            for (i in from until to) {
                val x = (i - from).toFloat() / perRow * size.width
                val y = base - sig[i].coerceIn(-1.9, 2.4).toFloat() * mvPx
                if (i == from) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink, style = Stroke(width = 1.6f))
            for (b in r.beats) {
                val t2 = b.tSec - k * 10.0
                if (t2 < 0 || t2 >= 10) continue
                val x = (t2 / 10 * size.width).toFloat()
                val tri = Path().apply { moveTo(x - 6f, top + 4f); lineTo(x + 6f, top + 4f); lineTo(x, top + 15f); close() }
                drawPath(tri, accent)
            }
        }
    }
}

/** Wskaźniki i oznaczenia z analizy zapisu, w czytelnych blokach. */
@Composable
fun EcgFindings(r: EcgResult) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(r.hrMedian?.let { "${Math.round(it)}" } ?: "brak", "tętno, /min", Modifier.weight(1f))
            Stat(r.hrMin?.let { "${Math.round(it)}–${Math.round(r.hrMax ?: it)}" } ?: "brak", "zakres tętna", Modifier.weight(1f))
            Stat(r.quality, "jakość zapisu", Modifier.weight(1f), mono = false)
        }
        if (r.sdnnMs != null) Hint("Zmienność odstępów RR: SDNN ${Math.round(r.sdnnMs ?: 0.0)} ms, RMSSD ${Math.round(r.rmssdMs ?: 0.0)} ms. Wykryte uderzenia: ${r.beats.size}.")
        Column {
            Overline("Co wykryto")
            LekiCard {
                r.flags.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    val attention = f.severity == EcgSeverity.Attention
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhIcon(if (attention) Ph.Warning else Ph.Info, size = 22.dp, tint = if (attention) pal.warn else pal.accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(f.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (attention) pal.warn else pal.ink))
                            Text(f.text, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(CardShape).background(pal.accentSoft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ważne", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
            listOf(
                "To narzędzie pomocnicze, a nie diagnoza.",
                "Aplikacja nie mierzy odstępu QT ani odcinka ST i nie rozpoznaje niedokrwienia ani zawału. Zegarek zapisuje tylko jedno odprowadzenie.",
                "Przy bólu w klatce piersiowej, duszności, omdleniu lub kołataniu nie czekaj na analizę: skontaktuj się z lekarzem lub zadzwoń pod 112.",
                "Przy lekach wydłużających odstęp QT albo zaburzeniach potasu poproś lekarza o pełne EKG.",
            ).forEach { t ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = TextStyle(fontSize = 13.5.sp, color = pal.accent, fontWeight = FontWeight.Bold))
                    Text(t, style = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, color = pal.ink2), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Rozwijany opis algorytmów i obliczeń (ten sam tekst jest w README i w skróconej formie w raportach). */
@Composable
fun EcgMethods() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        LinkButton((if (open) "▾ " else "▸ ") + "Jak to jest liczone (algorytmy)", { open = !open })
        if (open) LekiCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EcgInfo.STEPS.forEachIndexed { i, st ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${i + 1}. ${st.title}", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
                        Text(st.text, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2))
                    }
                }
                Hint("Obliczenia wykonuje sama aplikacja na telefonie: bez bibliotek zewnętrznych, bez internetu i bez modeli AI. Kod: core/…/Ecg.kt.")
            }
        }
    }
}

/** Edytor nowego zapisu (po wczytaniu PDF) albo istniejącego (zmiana daty, godziny, notatki). */
@Composable
fun EcgEditor(initial: Ecg, isNew: Boolean, store: Store, onClose: () -> Unit) {
    val toast = LocalToast.current
    var date by remember { mutableStateOf(initial.date) }
    var time by remember { mutableStateOf(initial.time) }
    var hr by remember { mutableStateOf(if (initial.watchHr > 0) initial.watchHr.toString() else "") }
    var watchNote by remember { mutableStateOf(initial.watchNote) }
    var note by remember { mutableStateOf(initial.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val result = remember(initial) { EcgAnalysis.analyze(initial) }
    FullScreenDialog(
        if (isNew) "Nowe EKG z zegarka" else "EKG z ${plDate(initial.date)}",
        onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiButton("Anuluj", onClose, Modifier.weight(1f))
                LekiButton("Zapisz", {
                    store.update { it.saveEcg(initial.copy(date = date, time = time, watchHr = hr.toIntOrNull()?.coerceIn(0, 300) ?: 0, watchNote = watchNote.trim(), note = note.trim())) }
                    toast("Zapisano EKG")
                    onClose()
                }, Modifier.weight(1f), primary = true)
            }
        },
    ) {
        EcgWaveView(initial, result)
        Hint("Przebieg odtworzony z wydruku. Sprawdź, czy wygląda tak jak na wydruku z zegarka; jeśli nie, nie opieraj się na analizie.")
        EcgFindings(result)
        EcgMethods()
        Overline("Dane zapisu", Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField("Data", date, { date = it }, Modifier.weight(1f))
            TimeField("Godzina", time, { time = it }, Modifier.weight(1f))
        }
        LekiField("Tętno wg zegarka, /min (opcjonalnie)", hr, { hr = it.filter { c -> c.isDigit() }.take(3) }, keyboard = KeyboardType.Number, mono = true)
        LekiField("Ocena zegarka (opcjonalnie)", watchNote, { watchNote = it.take(120) }, placeholder = "np. Rytm zatokowy")
        LekiField("Notatka (opcjonalnie)", note, { note = it.take(500) }, singleLine = false, minLines = 2, placeholder = "np. po spacerze, odczuwane kołatanie")
        if (!isNew) LinkButton("Usuń to EKG", { confirmDelete = true }, danger = true)
    }
    if (confirmDelete) ConfirmDialog("Usunąć EKG?", "Zapis zniknie z aplikacji, raportów i przyszłych kopii zapasowych.", "Usuń",
        onConfirm = { store.update { it.deleteEcg(initial.id) }; toast("Usunięto EKG"); onClose() }, onDismiss = { confirmDelete = false }, danger = true)
}

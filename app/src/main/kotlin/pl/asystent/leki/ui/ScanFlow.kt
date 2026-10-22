package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import pl.asystent.leki.Platform
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Candidate
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.Marker
import pl.asystent.leki.core.lnum
import pl.asystent.leki.core.parseNum
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.rangeText
import pl.asystent.leki.ocr.OcrEngine
import pl.asystent.leki.ocr.ScanImages
import java.io.File
import java.time.LocalDate

/** Stan ekranu sprawdzania wyników rozpoznanych ze skanu; użytkownik poprawia wszystko przed zapisem. */
class ReviewState(s: AppState, detectedDate: LocalDate?, cands: List<Candidate>, val rawText: String, val pages: Int) {
    val dateDetected = detectedDate != null
    var date by mutableStateOf(detectedDate ?: LocalDate.now())
    val items = mutableStateListOf<Candidate>().also { it.addAll(cands) }
    val included = mutableStateMapOf<Int, Boolean>().also { m -> cands.forEach { m[it.index] = LabScan.defaultInclude(it, s, detectedDate ?: LocalDate.now()) } }
    val valueText = mutableStateMapOf<Int, String>().also { m -> cands.forEach { m[it.index] = lnum(it.value) } }
    /** Propozycje nowych parametrów z dokumentu (żeby dało się wrócić do "Nowy parametr" po zmianie wyboru). */
    val proposals: Map<Int, Marker> = cands.mapNotNull { c -> c.proposed?.let { c.index to it } }.toMap()

    fun chosen(): List<Candidate> = items.filter { included[it.index] == true }
}

private sealed class Stage {
    object Pick : Stage()
    data class Working(val message: String) : Stage()
    data class Failed(val message: String, val rawText: String) : Stage()
    class Review(val state: ReviewState) : Stage()
}

/** Dodawanie wyników badań ze zdjęcia (aparat lub galeria) albo z pliku PDF: wybór źródła, rozpoznanie tekstu, sprawdzenie i zapis. */
@Composable
fun ScanDialog(s: AppState, store: Store, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Pick) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun finish(rows: List<String>, pages: Int) {
        val raw = rows.joinToString("\n").trim()
        val parsed = LabScan.parseRows(rows)
        stage = if (parsed.isEmpty()) {
            Stage.Failed("Nie znalazłem w tym dokumencie wyników badań w układzie tabeli (nazwa, wynik, jednostka). Spróbuj zrobić wyraźniejsze zdjęcie całej tabeli albo wybierz inny plik.", raw)
        } else {
            val date = LabScan.parseDate(rows, LocalDate.now())
            Stage.Review(ReviewState(s, date, LabScan.candidates(parsed, s), raw, pages))
        }
    }

    fun run(work: suspend (OcrEngine, MutableList<String>) -> Int) {
        stage = Stage.Working("Rozpoznaję tekst…")
        scope.launch {
            val engine = OcrEngine()
            val rows = ArrayList<String>()
            try {
                val pages = work(engine, rows)
                finish(rows, pages)
            } catch (e: ScanImages.PdfException) {
                stage = Stage.Failed(e.message ?: "Nie można otworzyć PDF.", "")
            } catch (e: OutOfMemoryError) {
                stage = Stage.Failed("Zdjęcie lub plik jest za duży dla pamięci telefonu. Zrób zdjęcie w niższej rozdzielczości albo skanuj mniej stron naraz.", "")
            } catch (e: Exception) {
                stage = Stage.Failed("Nie udało się rozpoznać tekstu (${e.javaClass.simpleName}). Spróbuj ponownie.", "")
            } finally {
                engine.close()
                cameraFile?.delete(); cameraFile = null
            }
        }
    }

    fun runImages(uris: List<android.net.Uri>) = run { engine, rows ->
        var n = 0
        for ((i, uri) in uris.withIndex()) {
            stage = Stage.Working("Rozpoznaję tekst (${i + 1} z ${uris.size})…")
            val bmp = ScanImages.load(ctx, uri) ?: continue
            rows += LabScan.rows(engine.recognize(bmp)); rows += ""
            bmp.recycle(); n++
        }
        n
    }

    fun runPdf(uri: android.net.Uri) = run { engine, rows ->
        val limit = 12
        val total = ScanImages.forEachPdfPage(ctx, uri, limit) { i, t, bmp ->
            stage = Stage.Working("Rozpoznaję stronę ${i + 1} z ${minOf(t, limit)}…")
            rows += LabScan.rows(engine.recognize(bmp)); rows += ""
        }
        minOf(total, limit)
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) runImages(listOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))) else { f?.delete(); cameraFile = null; stage = Stage.Pick }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris -> if (uris.isNotEmpty()) runImages(uris) }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runPdf(uri) }

    fun startCamera() {
        val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "skan-${System.currentTimeMillis()}.jpg")
        cameraFile = f
        try { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) } catch (e: Exception) { toast("Nie znaleziono aplikacji aparatu."); cameraFile = null }
    }

    when (val st = stage) {
        is Stage.Review -> {
            val rv = st.state
            FullScreenDialog("Sprawdź wyniki", onClose, footer = { ReviewFooter(rv, onCancel = onClose, onSave = {
                val chosen = rv.chosen()
                store.update { LabScan.apply(it, rv.date, chosen) }
                toast("Zapisano ${chosen.size} ${plN(chosen.size, "wynik", "wyniki", "wyników")}")
                onClose()
            }) }) { ReviewBody(s, rv) }
        }
        else -> FullScreenDialog("Dodaj wyniki ze skanu", onClose) {
            when (st) {
                is Stage.Pick -> PickBody(onCamera = ::startCamera, onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, onPdf = { pdf.launch(arrayOf("application/pdf")) })
                is Stage.Working -> Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    CircularProgressIndicator(color = pal.accent)
                    Text(st.message, style = TextStyle(fontSize = 15.sp, color = pal.ink2))
                    Hint("Tekst jest rozpoznawany na telefonie, nic nie jest wysyłane.")
                }
                is Stage.Failed -> {
                    AlertBox("Nie udało się odczytać wyników", body = st.message)
                    if (st.rawText.isNotEmpty()) RawTextBlock(st.rawText)
                    LekiButton("Spróbuj ponownie", { stage = Stage.Pick }, Modifier.fillMaxWidth(), primary = true)
                }
                is Stage.Review -> {}
            }
        }
    }
}

@Composable
internal fun PickBody(onCamera: () -> Unit, onGallery: () -> Unit, onPdf: () -> Unit) {
    BodyText("Wybierz, skąd mam odczytać wyniki badań. Rozpoznam parametry, wartości i jednostki, a Ty sprawdzisz je przed zapisem.")
    SourceCard(Ph.Camera, "Zrób zdjęcie", "Aparat telefonu. Ułóż kartkę płasko, przy dobrym świetle, z całą tabelą w kadrze.", onCamera)
    SourceCard(Ph.Image, "Wybierz z galerii", "Jedno lub kilka zdjęć albo zrzutów ekranu z wynikami.", onGallery)
    SourceCard(Ph.FilePdf, "Wybierz plik PDF", "Wyniki pobrane z laboratorium. Działa też ze skanami w PDF.", onPdf)
    Row(Modifier.fillMaxWidth().clip(CardShape).background(pal.accentSoft).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhIcon(Ph.LockKey, size = 22.dp, tint = pal.accent)
        Column {
            Text("Prywatnie", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.accent))
            Text("Tekst jest rozpoznawany na telefonie. Zdjęcia i pliki nie są wysyłane ani zapisywane w aplikacji, a aplikacja nie ma dostępu do internetu.", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun SourceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).border(1.dp, pal.line, CardShape).background(pal.surface).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(subtitle, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink3), modifier = Modifier.padding(top = 2.dp))
        }
        PhIcon(Ph.CaretRight, size = 16.dp)
    }
}

@Composable
private fun RawTextBlock(raw: String) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var open by remember { mutableStateOf(false) }
    Column {
        LinkButton((if (open) "▾ " else "▸ ") + "Rozpoznany tekst", { open = !open })
        if (open) {
            LekiField("", raw, {}, readOnly = true, singleLine = false, minLines = 6, mono = true)
            LinkButton("Kopiuj rozpoznany tekst", { Platform.copyText(ctx, "Rozpoznany tekst", raw); toast("Skopiowano rozpoznany tekst") })
        }
    }
}

@Composable
fun ReviewBody(s: AppState, rv: ReviewState) {
    val shown = rv.items
    Row(Modifier.fillMaxWidth().clip(CardShape).background(pal.warnBg).border(1.dp, pal.warnLine, CardShape).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhIcon(Ph.Warning, size = 22.dp, tint = pal.warn)
        Column {
            Text("Sprawdź każdy wynik z oryginałem", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.warn))
            Text("Rozpoznawanie tekstu bywa niedokładne: pomyłka w przecinku lub cyfrze zmienia wynik. Popraw wartości i odznacz to, czego nie chcesz zapisać.", style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = pal.ink2), modifier = Modifier.padding(top = 2.dp))
        }
    }
    Column {
        DateField("Data badania", rv.date, { rv.date = it })
        Hint(if (rv.dateDetected) "Data rozpoznana z dokumentu. Sprawdź, czy to data pobrania materiału." else "Nie rozpoznałem daty w dokumencie. Wybierz ją ręcznie.", Modifier.padding(top = 6.dp))
    }
    val selected = rv.chosen()
    val multi = selected.groupBy { it.markerId }.filter { it.value.size > 1 }.keys
    Text("Rozpoznano ${shown.size} ${plN(shown.size, "wynik", "wyniki", "wyników")}, do zapisu: ${selected.size}", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink2))
    shown.forEachIndexed { i, c ->
        val on = rv.included[c.index] == true
        val warns = LabScan.warnings(c, s, rv.date) + (if (on && c.markerId in multi) listOf(pl.asystent.leki.core.ScanWarning(pl.asystent.leki.core.WarningKind.Duplicate, "Ten parametr jest zaznaczony kilka razy. Zapisany zostanie ostatni.")) else emptyList())
        val marker = s.marker(c.markerId) ?: c.proposed ?: rv.proposals[c.index]
        val blocking = warns.any { it.blocking }
        Column(
            Modifier.fillMaxWidth().clip(CardShape).border(1.dp, if (blocking && on) pal.warn else pal.line, CardShape).background(pal.surface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.padding(top = 14.dp).size(26.dp).clip(RoundedCornerShape(7.dp)).border(2.dp, if (on) pal.accent else pal.ink3, RoundedCornerShape(7.dp))
                        .background(if (on) pal.accent else androidx.compose.ui.graphics.Color.Transparent).toggleable(value = on, role = Role.Checkbox) { rv.included[c.index] = !on },
                    contentAlignment = Alignment.Center,
                ) { if (on) PhIcon(Ph.Check, size = 16.dp, tint = pal.accentInk) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val options = s.markers.map { it.id to it.name } + (if (s.marker(c.markerId) == null) listOf(c.markerId to "Nowy parametr: " + (marker?.name ?: c.rawName)) else emptyList())
                    DropdownField("", options, c.markerId, { id ->
                        val newProposal = rv.proposals[c.index]
                        rv.items[i] = if (s.marker(id) != null) c.copy(markerId = id, proposed = null) else c.copy(markerId = id, proposed = newProposal)
                    })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val text = rv.valueText[c.index] ?: lnum(c.value)
                        val bad = parseNum(text) == null
                        LekiField("", text, { t ->
                            rv.valueText[c.index] = t
                            parseNum(t)?.let { v -> rv.items[i] = rv.items[i].copy(value = v) }
                        }, Modifier.width(130.dp), keyboard = KeyboardType.Decimal, mono = true, error = bad)
                        Column {
                            Text(c.unit.ifEmpty { marker?.unit.orEmpty() }, style = TextStyle(fontSize = 14.sp, color = pal.ink2))
                            val rt = marker?.let { rangeText(it) }.orEmpty()
                            if (rt.isNotEmpty()) Hint("zakres $rt")
                        }
                    }
                }
            }
            warns.forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    PhIcon(Ph.Warning, size = 16.dp, tint = pal.warn, modifier = Modifier.padding(top = 2.dp))
                    Text(w.text, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, color = pal.warn))
                }
            }
            Text("z dokumentu: " + c.raw, style = TextStyle(fontSize = 11.5.sp, color = pal.ink3, fontFamily = Mono), maxLines = 2)
        }
    }
    RawTextBlock(rv.rawText)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun ReviewFooter(rv: ReviewState, onCancel: () -> Unit, onSave: () -> Unit) {
    val n = rv.chosen().size
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LekiButton("Anuluj", onCancel, Modifier.weight(1f), tonal = true)
        LekiButton(if (n > 0) "Zapisz $n ${plN(n, "wynik", "wyniki", "wyników")}" else "Nic do zapisu", onSave, Modifier.weight(2f), primary = true, enabled = n > 0)
    }
}

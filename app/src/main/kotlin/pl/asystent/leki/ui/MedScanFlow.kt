package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import pl.asystent.leki.core.LabScan
import pl.asystent.leki.core.MedDraft
import pl.asystent.leki.core.MedScan
import pl.asystent.leki.ocr.OcrEngine
import pl.asystent.leki.ocr.ScanImages
import pl.asystent.leki.ocr.ScanPhoto
import java.io.File

/**
 * Dodawanie leku ze zdjęcia opakowania (aparat albo galeria): tekst rozpoznawany jest na telefonie, a z niego odczytywane są nazwa, moc, substancja,
 * jednostka i liczba sztuk w opakowaniu. Wynik trafia do edytora leku jako propozycja; dawkowanie ustawia użytkownik.
 */
@Composable
fun MedScanDialog(onDraft: (MedDraft, ScanPhoto) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun run(uri: android.net.Uri) {
        working = "Rozpoznaję tekst z opakowania…"; failed = null
        scope.launch {
            val engine = OcrEngine()
            var handedOver = false
            val bmp = ScanImages.load(ctx, uri)
            try {
                if (bmp == null) { failed = "Nie udało się wczytać zdjęcia."; return@launch }
                val lines = engine.recognizeBest(bmp)
                // nawet gdy nic sensownego nie odczytano, edytor otwiera się z pustą propozycją i zdjęciem: nazwę i substancję można zaznaczyć ręcznie
                val draft = MedScan.parse(lines) ?: MedDraft(emptyList(), "", "", "tabl.", null, emptyList())
                handedOver = true
                onDraft(draft, ScanPhoto(bmp, lines))
            } catch (e: OutOfMemoryError) {
                failed = "Zdjęcie jest za duże dla pamięci telefonu. Zrób zdjęcie w niższej rozdzielczości."
            } catch (e: Exception) {
                failed = "Nie udało się rozpoznać tekstu (${e.javaClass.simpleName}). Spróbuj ponownie."
            } finally {
                engine.close(); cameraFile?.delete(); cameraFile = null; working = null
                if (!handedOver) bmp?.recycle()
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) run(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) else { f?.delete(); cameraFile = null }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) run(uri) }

    fun startCamera() {
        val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "lek-${System.currentTimeMillis()}.jpg")
        cameraFile = f
        try { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) } catch (e: Exception) { toast("Nie znaleziono aplikacji aparatu."); cameraFile = null }
    }

    FullScreenDialog("Dodaj lek ze zdjęcia", onClose) {
        val w = working
        if (w != null) {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CircularProgressIndicator(color = pal.accent)
                Text(w, style = TextStyle(fontSize = 15.sp, color = pal.ink2))
                Hint("Tekst jest rozpoznawany na telefonie, nic nie jest wysyłane.")
            }
            return@FullScreenDialog
        }
        BodyText("Sfotografuj przód opakowania leku. Odczytam nazwę, moc, substancję czynną i liczbę tabletek w opakowaniu, a Ty sprawdzisz to i ustawisz dawkowanie przed zapisem.")
        failed?.let { AlertBox("Nie udało się odczytać leku", body = it) }
        SourceCard(Ph.Camera, "Zrób zdjęcie", "Aparat telefonu. Ułóż opakowanie płasko, przy dobrym świetle, z nazwą w kadrze.", ::startCamera)
        SourceCard(Ph.Image, "Wybierz z galerii", "Zdjęcie opakowania, które już masz.") { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        Hint("Aplikacja nie ma bazy leków i nie łączy się z internetem, więc odczytuje tylko to, co jest wydrukowane na opakowaniu. Zawsze sprawdź nazwę i dawkowanie z zaleceniem lekarza.")
    }
}

package pl.asystent.leki.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.asystent.leki.Store
import pl.asystent.leki.core.AppState
import pl.asystent.leki.core.Studies
import pl.asystent.leki.core.Study
import pl.asystent.leki.core.StudyImage
import pl.asystent.leki.core.StudyKind
import pl.asystent.leki.core.StudySelection
import pl.asystent.leki.core.deleteStudy
import pl.asystent.leki.core.newId
import pl.asystent.leki.core.plDate
import pl.asystent.leki.core.plN
import pl.asystent.leki.core.restoreStudy
import pl.asystent.leki.core.saveStudy
import pl.asystent.leki.ocr.StudyImages
import java.time.LocalDate

/** Lista badań opisowych i obrazowych w zakładce Badania. */
fun LazyListScope.studiesSection(s: AppState, onAdd: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    item {
        Column(Modifier.padding(top = 14.dp)) {
            BodyText("Badania opisowe i obrazowe: USG, tomografia, rezonans, RTG i inne. Wpisz opis i wnioski albo wczytaj je ze zdjęcia lub PDF wyniku, dołącz zdjęcia z badania (pomniejszone). Które badania trafią do raportu dla lekarza i do tekstu dla AI, wybierzesz w zakładce Raport.")
            LekiButton("Dodaj badanie", onAdd, Modifier.fillMaxWidth().padding(top = 12.dp), primary = true, icon = Ph.Plus)
        }
    }
    val list = StudySelection.all(s)
    if (list.isEmpty()) item { EmptyBox("Brak badań opisowych i obrazowych.", Modifier.padding(top = 16.dp)) }
    else item {
        Column(Modifier.padding(top = 16.dp)) {
            LekiCard { list.forEachIndexed { i, st -> if (i > 0) Divider(); StudyRow(st, { onOpen(st.id) }, { onDelete(st.id) }) } }
            val kb = Studies.imageChars(s.studies) * 3 / 4 / 1024
            if (kb > 0) Hint("Zdjęcia badań zajmują ok. ${if (kb >= 1024) "%.1f".format(java.util.Locale.forLanguageTag("pl"), kb / 1024.0) + " MB" else "$kb KB"} z ${Studies.MAX_TOTAL_CHARS * 3 / 4 / 1024 / 1024} MB dostępnych na osobę.", Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun StudyRow(st: Study, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onEdit).padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val first = remember(st.images.firstOrNull()?.data) { st.images.firstOrNull()?.let { StudyImages.thumb(it.data, 160)?.asImageBitmap() } }
        if (first != null) Image(first, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
        else Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, pal.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { PhIcon(Ph.FileText, size = 22.dp) }
        Column(Modifier.weight(1f)) {
            Text(st.title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink))
            Text(
                listOf(plDate(st.date), st.kind.label, if (st.images.isNotEmpty()) "${st.images.size} ${plN(st.images.size, "zdjęcie", "zdjęcia", "zdjęć")}" else "").filter { it.isNotEmpty() }.joinToString(", "),
                style = TextStyle(fontSize = 12.5.sp, color = pal.ink3), modifier = Modifier.padding(top = 2.dp),
            )
            if (st.conclusions.isNotEmpty()) Text(st.conclusions.lines().first().take(90), style = TextStyle(fontSize = 13.sp, color = pal.ink2), maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LekiButton("Edytuj", onEdit, small = true, tonal = true)
                LekiButton("Usuń", onDelete, small = true, danger = true)
            }
        }
    }
}

/** Usuwa badanie razem ze zdjęciami i z wyboru do raportów oraz pokazuje „Cofnij”, które przywraca badanie i ten wybór. */
internal fun deleteStudyWithUndo(store: Store, st: Study, undo: (String, () -> Unit) -> Unit) {
    val wasPicked = store.state.value.settings.rp.studyIds?.contains(st.id) == true
    store.update { it.deleteStudy(st.id) }
    undo("Usunięto badanie: ${st.title}") { store.update { s2 -> s2.restoreStudy(st, wasPicked) } }
}

/** Edytor badania: rodzaj, data, tytuł, miejsce, opis, wnioski i zdjęcia; opis można wczytać ze zdjęcia lub PDF wyniku (numery PESEL są usuwane, resztę sprawdza osoba). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyEditor(initial: Study?, store: Store, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val id = remember { initial?.id ?: newId() }
    var kind by remember { mutableStateOf(initial?.kind ?: StudyKind.Usg) }
    var date by remember { mutableStateOf(initial?.date ?: LocalDate.now()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var place by remember { mutableStateOf(initial?.place.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var conclusions by remember { mutableStateOf(initial?.conclusions.orEmpty()) }
    var images by remember { mutableStateOf(initial?.images.orEmpty()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var viewId by remember { mutableStateOf("") }
    var pendingText by remember { mutableStateOf<String?>(null) }

    fun draft() = Study(id, date, kind, title, place, description, conclusions, images)

    val addImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Zmniejszam zdjęcie…"
        scope.launch {
            try {
                val data = StudyImages.fromUri(ctx, uri)
                if (data == null) toast("Nie udało się odczytać zdjęcia albo jest za duże nawet po zmniejszeniu.")
                else {
                    val all = store.state.value.studies.filter { it.id != id } + draft()
                    val why = Studies.cannotAddImage(all, draft(), data.length)
                    if (why != null) toast(why) else images = images + StudyImage(newId(), data)
                }
            } catch (e: OutOfMemoryError) {
                toast("Zdjęcie jest za duże dla pamięci telefonu.")
            } catch (e: Exception) {
                toast("Nie udało się dodać zdjęcia.")
            } finally {
                busy = null
            }
        }
    }
    val readText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Odczytuję tekst… Nic nie jest wysyłane."
        scope.launch {
            try {
                val text = Studies.stripIdentifiers(StudyImages.readText(ctx, uri))
                if (text.isBlank()) toast("Nie rozpoznano tekstu. Spróbuj zdjęcia z lepszym światłem albo wpisz opis ręcznie.")
                else if (description.isBlank()) { description = text.take(Studies.MAX_DESCRIPTION); toast("Wczytano opis. Sprawdź go przed zapisem.") }
                else pendingText = text
            } catch (e: pl.asystent.leki.ocr.ScanImages.PdfException) {
                toast(e.message ?: "Nie można otworzyć PDF.")
            } catch (e: OutOfMemoryError) {
                toast("Za mało pamięci, żeby przetworzyć ten plik.")
            } catch (e: Exception) {
                toast("Nie udało się odczytać tekstu.")
            } finally {
                busy = null
            }
        }
    }

    FullScreenDialog(
        if (initial == null) "Nowe badanie" else "Badanie",
        onClose,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LekiButton("Anuluj", onClose, Modifier.weight(1f))
                LekiButton("Zapisz", {
                    store.update { it.saveStudy(draft()) }
                    toast("Zapisano badanie")
                    onClose()
                }, Modifier.weight(1f), primary = true, enabled = title.isNotBlank() || description.isNotBlank() || conclusions.isNotBlank() || images.isNotEmpty())
            }
        },
    ) {
        val b = busy
        if (b != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = pal.accent, modifier = Modifier.size(22.dp))
                Text(b, style = TextStyle(fontSize = 14.sp, color = pal.ink2))
            }
        }
        Text("Rodzaj badania", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = pal.ink2))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudyKind.values().forEach { k -> ToggleChip(k.label, kind == k, Role.RadioButton) { kind = k } }
        }
        DateField("Data badania", date, { date = it })
        LekiField("Tytuł", title, { title = it.take(Studies.MAX_TITLE) }, placeholder = "np. USG jamy brzusznej")
        LekiField("Miejsce (opcjonalnie)", place, { place = it.take(Studies.MAX_PLACE) }, placeholder = "np. nazwa przychodni")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LekiButton("Wczytaj opis ze zdjęcia lub PDF", { readText.launch(arrayOf("image/*", "application/pdf")) }, Modifier.weight(1f), tonal = true, icon = Ph.Scan, enabled = busy == null)
        }
        LekiField("Opis badania", description, { description = it.take(Studies.MAX_DESCRIPTION) }, singleLine = false, minLines = 6, placeholder = "Opis z wyniku badania")
        LekiField("Wnioski", conclusions, { conclusions = it.take(Studies.MAX_CONCLUSIONS) }, singleLine = false, minLines = 2, placeholder = "Wnioski z wyniku badania")
        Hint("Z wczytanego tekstu aplikacja usuwa wiersze z numerem PESEL (zwykle z imieniem i nazwiskiem). Sprawdź jednak tekst przed zapisem i nie dodawaj zdjęć strony z danymi osobowymi: zdjęcia z opisu nie są czyszczone.")
        Overline("Zdjęcia z badania (${images.size} z ${Studies.MAX_IMAGES})", Modifier.padding(top = 4.dp))
        if (images.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { im ->
                val bmp = remember(im.id) { StudyImages.thumb(im.data, 320)?.asImageBitmap() }
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, pal.line, RoundedCornerShape(8.dp)).clickable(role = Role.Button) { viewId = im.id }) {
                    if (bmp != null) Image(bmp, im.caption.ifEmpty { "Zdjęcie z badania" }, Modifier.size(96.dp), contentScale = ContentScale.Crop)
                }
            }
        }
        LekiButton("Dodaj zdjęcie z galerii", { addImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth(), tonal = true, icon = Ph.Image, enabled = busy == null && images.size < Studies.MAX_IMAGES)
        Hint("Zdjęcia są pomniejszane i zapisywane w aplikacji oraz w kopii zapasowej (do ${Studies.MAX_IMAGES} przy badaniu). Trafią do raportu dla lekarza, jeśli wybierzesz to badanie; do tekstu dla AI nie trafiają.")
        if (initial != null) LinkButton("Usuń to badanie", { deleteStudyWithUndo(store, initial, undo); onClose() }, danger = true)
    }
    images.firstOrNull { it.id == viewId }?.let { im ->
        FullScreenDialog("Zdjęcie z badania", { viewId = "" }) {
            val big = remember(im.id) { StudyImages.thumb(im.data, 1600)?.asImageBitmap() }
            if (big != null) Image(big, im.caption.ifEmpty { "Zdjęcie z badania" }, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            LekiField("Podpis (opcjonalnie)", im.caption, { c -> images = images.map { if (it.id == im.id) it.copy(caption = c.take(Studies.MAX_CAPTION)) else it } }, placeholder = "np. pęcherzyk żółciowy")
            LinkButton("Usuń to zdjęcie", { images = images.filter { it.id != im.id }; viewId = "" }, danger = true)
        }
    }
    pendingText?.let { t ->
        ConfirmDialog("Zastąpić wpisany opis?", "W polu jest już opis (${description.length} znaków). Wczytany tekst ma ${t.length} znaków i go zastąpi.", "Zastąp",
            onConfirm = { description = t.take(Studies.MAX_DESCRIPTION); pendingText = null }, onDismiss = { pendingText = null })
    }
}

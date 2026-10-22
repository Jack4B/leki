package pl.asystent.leki.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pl.asystent.leki.core.OcrLine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Rozpoznawanie tekstu na urządzeniu (ML Kit, model łaciński dołączony do aplikacji). Obrazy są przetwarzane w pamięci,
 * nigdzie nie są wysyłane (aplikacja nie ma uprawnienia INTERNET) ani zapisywane.
 */
class OcrEngine : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap): List<OcrLine> = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                cont.resume(text.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { b -> OcrLine(l.text, b.left, b.top, b.right, b.bottom) } })
            }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    override fun close() = recognizer.close()
}

object ScanImages {
    private const val MAX_SIDE = 3200

    /** Wczytuje obraz z pliku lub galerii, zmniejsza do rozsądnego rozmiaru i uwzględnia obrót z danych EXIF. */
    suspend fun load(ctx: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@withContext null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return@withContext bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    class PdfException(message: String) : Exception(message)

    /** Strona PDF jako obraz i faktyczna rozdzielczość (dpi względem rozmiaru strony w punktach): skala jest jedna dla obu osi, więc proporcje (a z nimi czas i napięcie) się nie zniekształcają. */
    class Rendered(val bitmap: Bitmap, val dpi: Double)

    /** Renderuje pierwszą stronę PDF w podanej rozdzielczości, z białym tłem; [maxW] i [maxH] ograniczają rozmiar obrazu (rozdzielczość spada wtedy proporcjonalnie). */
    suspend fun renderFirstPdfPageScaled(ctx: Context, uri: Uri, dpi: Int, maxW: Int = 3000, maxH: Int = 4200): Rendered {
        val pfd = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() } ?: throw PdfException("Nie można otworzyć pliku PDF.")
        return pfd.use {
            val renderer = try { PdfRenderer(it) } catch (e: Exception) { throw PdfException("Nie można odczytać PDF (plik może być uszkodzony lub zabezpieczony hasłem).") }
            renderer.use { r ->
                if (r.pageCount < 1) throw PdfException("Plik PDF nie ma stron.")
                withContext(Dispatchers.IO) {
                    r.openPage(0).use { page ->
                        val sc = minOf(dpi / 72f, maxW.toFloat() / page.width, maxH.toFloat() / page.height)
                        val w = (page.width * sc).toInt().coerceAtLeast(200)
                        val h = (page.height * sc).toInt().coerceAtLeast(200)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Rendered(b, w * 72.0 / page.width)
                    }
                }
            }
        }
    }

    suspend fun renderFirstPdfPage(ctx: Context, uri: Uri, dpi: Int): Bitmap = renderFirstPdfPageScaled(ctx, uri, dpi).bitmap

    /** Renderuje kolejne strony PDF do obrazów (po jednej naraz) i przekazuje je do [onPage]. Zwraca liczbę stron w pliku. */
    suspend fun forEachPdfPage(ctx: Context, uri: Uri, maxPages: Int, onPage: suspend (index: Int, total: Int, bmp: Bitmap) -> Unit): Int {
        val pfd = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() } ?: throw PdfException("Nie można otworzyć pliku PDF.")
        pfd.use {
            val renderer = try { PdfRenderer(it) } catch (e: Exception) { throw PdfException("Nie można odczytać PDF (plik może być uszkodzony lub zabezpieczony hasłem).") }
            renderer.use { r ->
                val total = r.pageCount
                for (i in 0 until minOf(total, maxPages)) {
                    val bmp = withContext(Dispatchers.IO) {
                        r.openPage(i).use { page ->
                            // szerokość 2200 px, ale wysokość nie większa niż 6600 px (bardzo długie strony nie mogą wyczerpać pamięci)
                            val scale = minOf(2200f / page.width, 6600f / page.height)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(b).drawColor(Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        }
                    }
                    onPage(i, total, bmp)
                    bmp.recycle()
                }
                return total
            }
        }
    }
}

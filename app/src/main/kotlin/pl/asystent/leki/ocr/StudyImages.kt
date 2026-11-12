package pl.asystent.leki.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.asystent.leki.Platform
import pl.asystent.leki.core.OcrLine
import pl.asystent.leki.core.Studies
import java.io.ByteArrayOutputStream

/** Zdjęcia badań obrazowych (zmniejszanie do zapisu, miniatury) i odczyt tekstu opisu ze zdjęcia lub PDF; wszystko na telefonie, bez sieci. */
object StudyImages {
    private const val MAX_SIDE = 1280
    private const val MAX_PDF_PAGES = 6

    /** Wczytuje zdjęcie z galerii, zmniejsza (dłuższy bok do 1280 px) i koduje jako JPEG w base64 w limicie [Studies.MAX_IMAGE_CHARS]; null, gdy nie da się go odczytać lub zmieścić. */
    suspend fun fromUri(ctx: Context, uri: Uri): String? {
        val src = ScanImages.load(ctx, uri) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
                val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, Math.round(src.width * scale).coerceAtLeast(1), Math.round(src.height * scale).coerceAtLeast(1), true) else src
                try { encode(bmp) } finally { if (bmp !== src) bmp.recycle() }
            } finally {
                src.recycle()
            }
        }
    }

    private fun encode(bmp: Bitmap): String? {
        var quality = 75
        while (quality >= 35) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (text.length <= Studies.MAX_IMAGE_CHARS) return text
            quality -= 10
        }
        return null
    }

    /** Miniatura zapisanego zdjęcia (dłuższy bok najwyżej [maxSide] px); null, gdy nie da się go odczytać. */
    fun thumb(data: String, maxSide: Int): Bitmap? = runCatching {
        val bytes = Base64.decode(data, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Czy plik to PDF: typ, nazwa i nagłówek (patrz [Studies.looksLikePdf]); selektor plików nie zawsze podaje typ. */
    private suspend fun isPdf(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val head = runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> ByteArray(5).let { b -> val n = s.read(b); if (n == 5) b else null } } }.getOrNull()
        Studies.looksLikePdf(ctx.contentResolver.getType(uri), Platform.displayName(ctx, uri), head)
    }

    /**
     * Wiersze tekstu ze zdjęcia lub PDF (do 6 stron) ze współrzędnymi, osobno dla każdej strony; kolejność i akapity ustala [pl.asystent.leki.core.StudyText.parse]. Pusta lista, gdy
     * nic nie rozpoznano. Błąd odczytu PDF zgłasza [ScanImages.PdfException]. Plik, którego nie da się odczytać jako zdjęcie, jest jeszcze raz próbowany jako PDF (bez typu i rozszerzenia).
     */
    suspend fun readPages(ctx: Context, uri: Uri): List<List<OcrLine>> {
        val engine = OcrEngine()
        try {
            val pages = ArrayList<List<OcrLine>>()
            suspend fun pdf() { ScanImages.forEachPdfPage(ctx, uri, MAX_PDF_PAGES) { _, _, bmp -> pages.add(engine.recognizeBest(bmp)) } }
            if (isPdf(ctx, uri)) pdf()
            else {
                val bmp = ScanImages.load(ctx, uri)
                if (bmp != null) {
                    try { pages.add(engine.recognizeBest(bmp)) } finally { bmp.recycle() }
                } else {
                    // to nie obraz: ostatnia próba jako PDF; „to nie PDF” nie jest tu błędem odczytu, tylko brakiem tekstu
                    try { pdf() } catch (e: ScanImages.PdfException) { pages.clear() }
                }
            }
            return pages.filter { it.isNotEmpty() }
        } finally {
            engine.close()
        }
    }
}

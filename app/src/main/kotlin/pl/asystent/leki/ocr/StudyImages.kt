package pl.asystent.leki.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Tekst ze zdjęcia lub PDF (do 6 stron), wiersz po wierszu; puste, gdy nic nie rozpoznano. Wyniku nie zapisuje: osoba sprawdza go przed zapisem. */
    suspend fun readText(ctx: Context, uri: Uri): String {
        val engine = OcrEngine()
        try {
            val isPdf = ctx.contentResolver.getType(uri) == "application/pdf"
            val parts = ArrayList<String>()
            if (isPdf) {
                ScanImages.forEachPdfPage(ctx, uri, MAX_PDF_PAGES) { _, _, bmp -> parts.add(engine.recognizeBest(bmp).joinToString("\n") { it.text }) }
            } else {
                val bmp = ScanImages.load(ctx, uri) ?: return ""
                try { parts.add(engine.recognizeBest(bmp).joinToString("\n") { it.text }) } finally { bmp.recycle() }
            }
            return parts.filter { it.isNotBlank() }.joinToString("\n\n")
        } finally {
            engine.close()
        }
    }
}

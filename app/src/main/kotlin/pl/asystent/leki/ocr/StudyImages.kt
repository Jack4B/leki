package pl.asystent.leki.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.asystent.leki.Platform
import pl.asystent.leki.core.Gray
import pl.asystent.leki.core.OcrLine
import pl.asystent.leki.core.PhotoFix
import pl.asystent.leki.core.Studies
import java.io.ByteArrayOutputStream

/** Zdjęcia badań obrazowych (zmniejszanie do zapisu, miniatury) i odczyt tekstu opisu ze zdjęcia lub PDF; wszystko na telefonie, bez sieci. */
object StudyImages {
    private const val MAX_SIDE = 1280
    private const val MAX_PDF_PAGES = 6

    /**
     * Wczytuje zdjęcie z galerii, opcjonalnie poprawia je automatycznie ([fix]: wyprostowanie, przycięcie do kadrów badania bez tła, rozciągnięcie kontrastu; patrz [PhotoFix]),
     * zmniejsza (dłuższy bok do 1280 px) i koduje jako JPEG w base64 w limicie [Studies.MAX_IMAGE_CHARS]; null, gdy nie da się go odczytać lub zmieścić.
     */
    suspend fun fromUri(ctx: Context, uri: Uri, fix: Boolean = true): String? {
        val src = ScanImages.load(ctx, uri) ?: return null
        return withContext(Dispatchers.Default) { finish(src, fix) }
    }

    /** Poprawia zapisane już zdjęcie (JPEG w base64) tak jak przy dodawaniu; null, gdy nie ma czego poprawiać (nie znaleziono tła do odcięcia) albo nie da się go odczytać. */
    suspend fun fixStored(data: String): String? = withContext(Dispatchers.Default) {
        val bytes = runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull() ?: return@withContext null
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val (fixed, changed) = autoFix(src)
        if (!changed) { if (fixed !== src) fixed.recycle(); src.recycle(); return@withContext null }
        src.recycle()
        finish(fixed, fix = false)
    }

    /** Zmniejsza do [MAX_SIDE] i koduje; [bmp] jest zwalniany. */
    private fun finish(bmp: Bitmap, fix: Boolean): String? {
        var cur = bmp
        try {
            if (fix) { val (fixed, _) = autoFix(cur); if (fixed !== cur) { cur.recycle(); cur = fixed } }
            val scale = MAX_SIDE.toFloat() / maxOf(cur.width, cur.height)
            val sized = if (scale < 1f) Bitmap.createScaledBitmap(cur, Math.round(cur.width * scale).coerceAtLeast(1), Math.round(cur.height * scale).coerceAtLeast(1), true) else cur
            try { return encode(sized) } finally { if (sized !== cur) sized.recycle() }
        } finally {
            cur.recycle()
        }
    }

    /**
     * Automatyczna korekta zdjęcia badania według [PhotoFix.plan] liczonego na małej kopii (256 px): obrót o ustalony kąt (płótno wypełnione kolorem tła), przycięcie do kadrów
     * i rozciągnięcie jasności. Zwraca nowy obraz i informację, czy coś zmieniono; gdy nie ma tła do odcięcia, zwraca ten sam obraz. [src] nie jest zwalniany.
     */
    internal fun autoFix(src: Bitmap): Pair<Bitmap, Boolean> {
        // praca na obrazie nie większym niż 1600 px, żeby obrót nie zajął za dużo pamięci
        val big = maxOf(src.width, src.height)
        val work = if (big > 1600) Bitmap.createScaledBitmap(src, Math.round(src.width * 1600f / big), Math.round(src.height * 1600f / big), true) else src
        try {
            val plan = PhotoFix.plan(gray(work, 256))
            if (plan.isIdentity) return src to false
            var rotated = work
            if (plan.rotateDeg != 0.0) rotated = rotate(work, plan.rotateDeg, borderColor(work))
            try {
                val c = plan.crop
                val w = rotated.width; val h = rotated.height
                val x0 = if (c != null) Math.floor(c.x0 * w).toInt().coerceIn(0, w - 1) else 0
                val y0 = if (c != null) Math.floor(c.y0 * h).toInt().coerceIn(0, h - 1) else 0
                val x1 = if (c != null) Math.ceil(c.x1 * w).toInt().coerceIn(x0 + 1, w) else w
                val y1 = if (c != null) Math.ceil(c.y1 * h).toInt().coerceIn(y0 + 1, h) else h
                val out = Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                if (plan.lo > 8 || plan.hi < 247) {
                    val scale = 255f / (plan.hi - plan.lo).coerceAtLeast(1)
                    val off = -plan.lo * scale
                    paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(scale, 0f, 0f, 0f, off, 0f, scale, 0f, 0f, off, 0f, 0f, scale, 0f, off, 0f, 0f, 0f, 1f, 0f)))
                }
                Canvas(out).drawBitmap(rotated, Rect(x0, y0, x1, y1), Rect(0, 0, x1 - x0, y1 - y0), paint)
                return out to true
            } finally {
                if (rotated !== work) rotated.recycle()
            }
        } finally {
            if (work !== src) work.recycle()
        }
    }

    /** Obraz w skali szarości o dłuższym boku najwyżej [side] px do analizy. */
    private fun gray(src: Bitmap, side: Int): Gray {
        val k = minOf(1f, side.toFloat() / maxOf(src.width, src.height))
        val w = Math.round(src.width * k).coerceAtLeast(1); val h = Math.round(src.height * k).coerceAtLeast(1)
        val small = if (k < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return Gray(w, h, IntArray(w * h) { i -> val c = px[i]; (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 })
    }

    /** Średni kolor brzegu zdjęcia (tło stołu), do wypełnienia rogów po obrocie. */
    private fun borderColor(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        val stepX = maxOf(1, b.width / 40); val stepY = maxOf(1, b.height / 40)
        fun add(x: Int, y: Int) { val c = b.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++ }
        for (x in 0 until b.width step stepX) { add(x, 0); add(x, b.height - 1) }
        for (y in 0 until b.height step stepY) { add(0, y); add(b.width - 1, y) }
        return if (n == 0) Color.WHITE else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    }

    /** Obrót o [deg] stopni zgodnie z ruchem wskazówek zegara na powiększonym płótnie (ten sam wzór co [PhotoFix.rotate]). */
    private fun rotate(src: Bitmap, deg: Double, fill: Int): Bitmap {
        val a = Math.toRadians(deg); val s = Math.abs(Math.sin(a)); val c = Math.abs(Math.cos(a))
        val nw = Math.ceil(src.width * c + src.height * s).toInt().coerceAtLeast(1); val nh = Math.ceil(src.width * s + src.height * c).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out); canvas.drawColor(fill)
        val m = Matrix().apply { postTranslate(-src.width / 2f, -src.height / 2f); postRotate(deg.toFloat()); postTranslate(nw / 2f, nh / 2f) }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
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

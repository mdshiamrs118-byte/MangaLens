package app.mangalens

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** One image of the strip. [file] is a private copy of the picked image; [translated] is the rendered result. */
class Page(val name: String, val file: File, val w: Int, val h: Int) {
    var translated: File? = null
}

object PageIO {

    fun pagesDir(context: Context) = File(context.cacheDir, "pages")
    fun translatedDir(context: Context) = File(context.cacheDir, "translated")

    /**
     * Copies the picked images into the app cache (so they stay readable), reads their size and
     * returns them in natural file-name order, which is the reading order for numbered webtoon files.
     */
    suspend fun importUris(context: Context, uris: List<Uri>, onProgress: (Int, Int) -> Unit): List<Page> =
        withContext(Dispatchers.IO) {
            val dir = pagesDir(context)
            dir.deleteRecursively()
            translatedDir(context).deleteRecursively()
            dir.mkdirs()
            translatedDir(context).mkdirs()

            val resolver = context.contentResolver
            val named = uris
                .map { (displayName(resolver, it) ?: it.lastPathSegment ?: "image") to it }
                .sortedWith { a, b -> PageMath.naturalCompare(a.first, b.first) }

            val stamp = System.currentTimeMillis()
            val pages = ArrayList<Page>()
            for ((i, pair) in named.withIndex()) {
                ensureActive()
                onProgress(i, named.size)
                val (name, uri) = pair
                val f = File(dir, "s${stamp}_p" + i.toString().padStart(3, '0'))
                val input = resolver.openInputStream(uri) ?: continue
                input.use { src -> FileOutputStream(f).use { src.copyTo(it) } }
                val page = prepare(name, f)
                if (page != null) pages.add(page) else f.delete()
            }
            pages
        }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }

    /** Reads the size; photos with an EXIF rotation are rewritten upright. Returns null if it is not an image. */
    private fun prepare(name: String, f: File): Page? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = try {
            ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return Page(name, f, bounds.outWidth, bounds.outHeight)

        val bmp = decodeForOcr(f) ?: return null
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        FileOutputStream(f).use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val page = Page(name, f, rotated.width, rotated.height)
        if (rotated !== bmp) bmp.recycle()
        rotated.recycle()
        return page
    }

    /** Decodes a page for OCR, kept within about 16 megapixels and 3000 px width. */
    fun decodeForOcr(f: File): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        var sample = 1
        while ((b.outWidth.toLong() / sample) * (b.outHeight.toLong() / sample) > 16_000_000L || b.outWidth / sample > 3000) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Decodes just one band of a big image (BitmapRegionDecoder), so a 30-page webtoon never has to
 * be in memory at once. All decoding runs on one thread because a decoder is not thread-safe.
 */
class RegionLoader {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val decoders = object : LinkedHashMap<String, BitmapRegionDecoder>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BitmapRegionDecoder>): Boolean {
            if (size > 6) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }

    /**
     * Rows [y0, y1) of a page whose logical height is [logicalH]; the file may have a different pixel
     * size (a translated copy of a downscaled page), so rows are mapped proportionally.
     * The result is at least [targetW] pixels wide when the file allows it.
     */
    suspend fun slice(file: File, logicalH: Int, y0: Int, y1: Int, targetW: Int): Bitmap? =
        withContext(dispatcher) {
            try {
                val dec = decoders.getOrPut(file.absolutePath) { BitmapRegionDecoder.newInstance(file.absolutePath, false) }
                val w = dec.width
                val h = dec.height
                val top = (y0.toLong() * h / logicalH).toInt().coerceIn(0, h - 1)
                val bottom = (y1.toLong() * h / logicalH).toInt().coerceIn(top + 1, h)
                var sample = 1
                while (w / (sample * 2) >= targetW) sample *= 2
                dec.decodeRegion(Rect(0, top, w, bottom), BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            }
        }

    suspend fun clear() = withContext(dispatcher) {
        for (d in decoders.values) d.recycle()
        decoders.clear()
    }

    fun close() {
        dispatcher.close()
    }
}

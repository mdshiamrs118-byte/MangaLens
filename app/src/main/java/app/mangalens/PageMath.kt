package app.mangalens

import kotlin.math.min

/** A horizontal band of one page; the strip list shows pages as bands so no single bitmap is too tall. */
data class Slice(val page: Int, val y0: Int, val y1: Int)

object PageMath {
    const val SLICE_HEIGHT = 1600
    private const val MIN_LAST = 400

    fun slices(heights: List<Int>): List<Slice> {
        val out = ArrayList<Slice>()
        for ((i, h) in heights.withIndex()) {
            var y = 0
            while (y < h) {
                var end = min(h, y + SLICE_HEIGHT)
                if (h - end < MIN_LAST) end = h // do not leave a tiny last band
                out.add(Slice(i, y, end))
                y = end
            }
        }
        return out
    }

    /** "page2.jpg" sorts before "page10.jpg". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object ImageNames {
    private val exts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "avif")

    /** True for image files; hidden files and non-images (thumbs, txt, json) are ignored. */
    fun isImage(name: String, mime: String?): Boolean {
        if (name.startsWith(".")) return false
        if (mime != null && mime.startsWith("image/")) return true
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase() in exts
    }
}

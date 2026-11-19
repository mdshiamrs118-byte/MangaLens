package app.mangalens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract

/** One file or folder inside the picked manga folder. */
data class DocEntry(val docId: String, val name: String, val isDir: Boolean, val isImage: Boolean)

/** A series: a folder directly inside the root folder. */
data class Title(val docId: String, val name: String, val chapters: Int)

/** A chapter: a sub-folder of a series, or the series folder itself when it holds the images directly. */
data class Chapter(val docId: String, val name: String)

/**
 * Reads the layout "Root / Manga Title / Chapter X / images" through the Storage Access Framework,
 * so the app only needs the one folder the user picked (works on Android 10 scoped storage).
 */
class Library(context: Context, private val tree: Uri) {

    private val resolver = context.contentResolver

    val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    fun uriOf(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun list(parentId: String): List<DocEntry> {
        val out = ArrayList<DocEntry>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out.add(DocEntry(id, name, dir, !dir && ImageNames.isImage(name, mime)))
                }
            }
        } catch (e: Exception) {
            // unreadable folder: treat as empty
        }
        return out.sortedWith { a, b -> PageMath.naturalCompare(a.name, b.name) }
    }

    fun nameOf(docId: String): String {
        try {
            resolver.query(
                uriOf(docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        } catch (e: Exception) {
            // fall through
        }
        return ""
    }

    /** Every sub-folder of the root that has chapters or images. A root that only holds images is one series. */
    fun titles(): List<Title> {
        val top = list(rootId)
        val out = ArrayList<Title>()
        for (d in top.filter { it.isDir }) {
            val inside = list(d.docId)
            val chapters = inside.count { it.isDir } + if (inside.any { it.isImage }) 1 else 0
            if (chapters > 0) out.add(Title(d.docId, d.name, chapters))
        }
        if (out.isEmpty() && top.any { it.isImage }) {
            out.add(Title(rootId, nameOf(rootId).ifBlank { "This folder" }, 1))
        }
        return out
    }

    fun chapters(title: Title): List<Chapter> {
        val inside = list(title.docId)
        val out = ArrayList<Chapter>()
        if (inside.any { it.isImage }) out.add(Chapter(title.docId, if (inside.any { it.isDir }) "Loose pages" else "All pages"))
        for (d in inside.filter { it.isDir }) out.add(Chapter(d.docId, d.name))
        return out
    }

    fun images(chapterDocId: String): List<DocEntry> = list(chapterDocId).filter { it.isImage }

    /** First image of the series, used as its cover. */
    fun coverUri(title: Title): Uri? {
        val inside = list(title.docId)
        inside.firstOrNull { it.isImage }?.let { return uriOf(it.docId) }
        for (d in inside.filter { it.isDir }) {
            list(d.docId).firstOrNull { it.isImage }?.let { return uriOf(it.docId) }
        }
        return null
    }

    /** Small decoded copy of an image for the cover grid. */
    fun thumbnail(uri: Uri, maxWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (e: Exception) {
            null
        }
    }
}

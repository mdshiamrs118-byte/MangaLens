package app.mangalens

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Translated pages of a library chapter, kept in the app's private storage so they survive closing the
 * reader. One folder per chapter; each file is named by page position plus a short hash of the page's file name.
 */
object TranslationStore {

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun key(titleDoc: String, chapterDoc: String): String = "$titleDoc|$chapterDoc"

    private fun dir(ctx: Context, key: String): File =
        File(File(ctx.filesDir, "translations"), sha1(key).take(16))

    fun fileFor(ctx: Context, key: String, index: Int, pageName: String): File =
        File(dir(ctx, key), index.toString().padStart(4, '0') + "_" + sha1(pageName).take(6) + ".jpg")

    /** The red-outlined twin of a translated page file. */
    fun markedOf(f: File): File = File(f.path.removeSuffix(".jpg") + "_fb.jpg")

    fun has(ctx: Context, key: String): Boolean =
        dir(ctx, key).list()?.any { it.endsWith(".jpg") } == true

    fun delete(ctx: Context, key: String) {
        dir(ctx, key).deleteRecursively()
    }
}

package app.mangalens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads and stores the files of the offline ONNX engines. */
class ModelStore(context: Context) {
    private val root = File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "models")

    fun dir(info: EngineInfo): File = File(root, info.id)

    fun isDownloaded(info: EngineInfo): Boolean =
        info.files.isNotEmpty() && info.files.all {
            val f = File(dir(info), it.relPath)
            f.exists() && f.length() > 0
        }

    fun delete(info: EngineInfo) {
        dir(info).deleteRecursively()
    }

    /** Resumable download of every file of [info]. [onProgress] gets a short human-readable status. */
    suspend fun download(info: EngineInfo, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val d = dir(info)
        d.mkdirs()
        for ((index, rf) in info.files.withIndex()) {
            val target = File(d, rf.relPath)
            if (target.exists() && target.length() > 0) continue
            val part = File(d, rf.relPath + ".part")
            var existing = if (part.exists()) part.length() else 0L
            val label = "file ${index + 1}/${info.files.size}"

            val conn = (URL(rf.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val code = conn.responseCode
                if (code == 416 && existing > 0) {
                    // The .part file already holds the whole file.
                    if (!part.renameTo(target)) throw IOException("Could not finish ${rf.relPath}")
                    continue
                }
                if (code != 200 && code != 206) throw IOException("HTTP $code for ${rf.relPath}")

                val append = code == 206
                if (!append) existing = 0L
                val len = conn.contentLengthLong
                val total = if (len > 0) len + existing else -1L

                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = existing
                        var lastReport = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            ensureActive()
                            out.write(buf, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 400) {
                                lastReport = now
                                onProgress("Downloading $label: ${mb(done)}" + if (total > 0) " / ${mb(total)}" else "")
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Could not finish ${rf.relPath}")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1048576.0)
}

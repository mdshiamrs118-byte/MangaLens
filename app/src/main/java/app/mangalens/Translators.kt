package app.mangalens

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface Translator {
    /** Translates every text; the result has the same size and order as [texts]. */
    suspend fun translate(texts: List<String>, onProgress: (done: Int, total: Int) -> Unit): List<String>

    fun release() {}
}

/** Google ML Kit on-device translation (the Korean language pack is fetched by ML Kit). */
class MlKitTranslator : Translator {
    private val client = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.KOREAN)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )

    suspend fun ensureModel() {
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(texts: List<String>, onProgress: (Int, Int) -> Unit): List<String> {
        ensureModel()
        val out = ArrayList<String>(texts.size)
        for ((i, t) in texts.withIndex()) {
            onProgress(i, texts.size)
            out.add(client.translate(t).await())
        }
        return out
    }

    override fun release() {
        client.close()
    }

    companion object {
        private fun koreanModel() = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()

        suspend fun isDownloaded(): Boolean =
            RemoteModelManager.getInstance().isModelDownloaded(koreanModel()).await()

        suspend fun download() {
            val t = MlKitTranslator()
            try {
                t.ensureModel()
            } finally {
                t.release()
            }
        }

        suspend fun delete() {
            RemoteModelManager.getInstance().deleteDownloadedModel(koreanModel()).await()
        }
    }
}

/**
 * Any OpenAI-compatible chat completions endpoint. The whole page is sent as one request so
 * the model sees every bubble and can keep names, tone and context consistent.
 */
class OnlineLlmTranslator(private val cfg: OnlineConfig) : Translator {

    override suspend fun translate(texts: List<String>, onProgress: (Int, Int) -> Unit): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            onProgress(0, texts.size)

            val system = "You are a professional Korean to English translator for manga and webtoons. " +
                "Translate dialogue naturally and idiomatically, keep the speaker's tone, honorifics " +
                "intent, slang and emotion, and keep names consistent across items. " +
                "Items are speech bubbles in reading order from one page, so use them as context for each other. " +
                "Never explain anything."
            val user = "Translate each item to English. Reply with ONLY a JSON array of exactly " +
                "${texts.size} strings, in the same order.\n" + Gson().toJson(texts)

            val messages = JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
            }
            val body = JsonObject().apply {
                addProperty("model", cfg.model)
                addProperty("temperature", 0.2)
                add("messages", messages)
            }

            val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(300).orEmpty()
                    throw IOException("API error $code: $err")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val content = JsonParser.parseString(text).asJsonObject
                    .getAsJsonArray("choices")[0].asJsonObject
                    .getAsJsonObject("message").get("content").asString

                val start = content.indexOf('[')
                val end = content.lastIndexOf(']')
                if (start < 0 || end <= start) throw IOException("The model did not return a JSON array")
                val arr = JsonParser.parseString(content.substring(start, end + 1)).asJsonArray
                val result = arr.map { it.asString }
                if (result.size < texts.size) {
                    throw IOException("The model returned ${result.size} items, expected ${texts.size}")
                }
                onProgress(texts.size, texts.size)
                result.take(texts.size)
            } finally {
                conn.disconnect()
            }
        }
}

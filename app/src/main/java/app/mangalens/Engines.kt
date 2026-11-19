package app.mangalens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Creates the selected translator and keeps the heavy offline ones loaded between pages. */
object Engines {
    private val mutex = Mutex()
    private var cachedId: String? = null
    private var cached: Translator? = null

    suspend fun obtain(context: Context, info: EngineInfo): Translator {
        val app = context.applicationContext
        if (info.kind == EngineKind.ONLINE) {
            val cfg = Prefs(app).online()
            check(cfg.isComplete) { "Open Translators, then Online LLM > Configure, and fill in base URL, API key and model." }
            return OnlineLlmTranslator(cfg)
        }
        return mutex.withLock {
            val existing = cached
            if (existing != null && cachedId == info.id) return@withLock existing

            existing?.release()
            cached = null
            cachedId = null

            val t: Translator = when (info.kind) {
                EngineKind.MLKIT -> MlKitTranslator()
                EngineKind.ONNX -> {
                    val store = ModelStore(app)
                    check(store.isDownloaded(info)) { "${info.title} is not downloaded yet. Open Translators and download it." }
                    withContext(Dispatchers.IO) { OnnxSeq2Seq(store.dir(info), info.family).also { it.load() } }
                }
                EngineKind.ONLINE -> error("unreachable")
            }
            cached = t
            cachedId = info.id
            t
        }
    }

    /** Frees a loaded engine, e.g. before its files are deleted. */
    suspend fun release(id: String) {
        mutex.withLock {
            if (cachedId == id) {
                cached?.release()
                cached = null
                cachedId = null
            }
        }
    }
}

package app.mangalens

enum class EngineKind { MLKIT, ONNX, ONLINE }

data class RemoteFile(val url: String, val relPath: String) {
    /** The file name as it is on Hugging Face, which is what a browser saves it as. */
    val originalName: String get() = url.substringAfterLast('/')
}

data class EngineInfo(
    val id: String,
    val title: String,
    val description: String,
    val kind: EngineKind,
    val approxSize: String,
    /** "marian" or "nllb" for ONNX engines. */
    val family: String = "",
    val files: List<RemoteFile> = emptyList(),
)

object Catalog {
    private const val OPUS = "https://huggingface.co/Xenova/opus-mt-ko-en/resolve/main/"
    private const val NLLB = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main/"

    val engines: List<EngineInfo> = listOf(
        EngineInfo(
            id = "mlkit",
            title = "Google ML Kit (offline)",
            description = "Fast and tiny. Most literal output, weakest on slang and tone.",
            kind = EngineKind.MLKIT,
            approxSize = "~30 MB",
        ),
        EngineInfo(
            id = "opus",
            title = "OPUS-MT ko to en (offline)",
            description = "Small Korean-to-English model. Quick on most phones, fairly literal.",
            kind = EngineKind.ONNX,
            approxSize = "~120 MB",
            family = "marian",
            files = listOf(
                RemoteFile(OPUS + "onnx/encoder_model_quantized.onnx", "encoder.onnx"),
                RemoteFile(OPUS + "onnx/decoder_model_quantized.onnx", "decoder.onnx"),
                RemoteFile(OPUS + "tokenizer.json", "tokenizer.json"),
                RemoteFile(OPUS + "vocab.json", "vocab.json"),
                RemoteFile(OPUS + "config.json", "config.json"),
            ),
        ),
        EngineInfo(
            id = "nllb",
            title = "NLLB-200 600M (offline)",
            description = "Better quality than OPUS-MT, but large and slow. Needs a recent phone with 4 GB+ RAM.",
            kind = EngineKind.ONNX,
            approxSize = "~0.9 GB",
            family = "nllb",
            files = listOf(
                RemoteFile(NLLB + "onnx/encoder_model_quantized.onnx", "encoder.onnx"),
                RemoteFile(NLLB + "onnx/decoder_model_quantized.onnx", "decoder.onnx"),
                RemoteFile(NLLB + "tokenizer.json", "tokenizer.json"),
                RemoteFile(NLLB + "config.json", "config.json"),
            ),
        ),
        EngineInfo(
            id = "online",
            title = "Online LLM (OpenAI-compatible API)",
            description = "Best at context, tone and slang. Needs internet plus your own API key; text is sent to that provider.",
            kind = EngineKind.ONLINE,
            approxSize = "no download",
        ),
    )

    /** Finds which expected file a saved file name belongs to (browser suffixes like "(1)" are ignored). */
    fun matchFile(info: EngineInfo, savedName: String): RemoteFile? {
        val clean = savedName.trim().replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]*$)"), "")
        return info.files.firstOrNull {
            it.relPath.equals(clean, ignoreCase = true) || it.originalName.equals(clean, ignoreCase = true)
        }
    }

    fun byId(id: String): EngineInfo = engines.firstOrNull { it.id == id } ?: engines.first()
}

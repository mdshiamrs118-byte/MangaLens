package app.mangalens

import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

class LoadedTokenizer(
    val tokenizer: UnigramTokenizer,
    /** Added/special tokens by their text, e.g. "kor_Hang" -> id. */
    val idByContent: Map<String, Int>,
)

object TokenizerLoader {

    /**
     * Reads a Hugging Face tokenizer.json (Unigram model) with a streaming parser, so even the
     * large NLLB file does not need a full in-memory JSON tree.
     * For Marian models pass vocab.json too: its ids are the ones the ONNX model uses.
     */
    fun load(tokenizerJson: File, vocabJson: File?): LoadedTokenizer {
        val pieces = ArrayList<String>()
        val scoreList = ArrayList<Float>()
        val added = HashMap<String, Int>()
        val special = HashSet<Int>()

        JsonReader(InputStreamReader(FileInputStream(tokenizerJson).buffered(), Charsets.UTF_8)).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "added_tokens" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var id = -1
                            var content = ""
                            var isSpecial = false
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "id" -> id = r.nextInt()
                                    "content" -> content = r.nextString()
                                    "special" -> isSpecial = r.nextBoolean()
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (id >= 0) {
                                added[content] = id
                                if (isSpecial) special.add(id)
                            }
                        }
                        r.endArray()
                    }
                    "model" -> {
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "vocab" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        r.beginArray()
                                        pieces.add(r.nextString())
                                        scoreList.add(r.nextDouble().toFloat())
                                        r.endArray()
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }
        require(pieces.isNotEmpty()) { "tokenizer.json has no Unigram vocab" }

        val idOfPiece = HashMap<String, Int>(pieces.size * 2)
        if (vocabJson != null) {
            JsonReader(InputStreamReader(FileInputStream(vocabJson).buffered(), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    idOfPiece[k] = r.nextInt()
                }
                r.endObject()
            }
        } else {
            for (i in pieces.indices) if (!idOfPiece.containsKey(pieces[i])) idOfPiece[pieces[i]] = i
        }
        for ((k, v) in added) idOfPiece[k] = v

        val tokenOfId = HashMap<Int, String>(idOfPiece.size * 2)
        for ((k, v) in idOfPiece) tokenOfId[v] = k

        val skip = HashSet<Int>(special)
        for (name in listOf("</s>", "<s>", "<pad>", "<unk>")) idOfPiece[name]?.let { skip.add(it) }
        val unk = idOfPiece["<unk>"] ?: 1

        val scores = FloatArray(scoreList.size) { scoreList[it] }
        return LoadedTokenizer(UnigramTokenizer(pieces, scores, idOfPiece, tokenOfId, skip, unk), added)
    }
}

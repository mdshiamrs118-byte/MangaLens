package app.mangalens

import android.content.Context

class OnlineConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mangalens", Context.MODE_PRIVATE)

    /** The translator the user picked ("mlkit", "opus", "nllb" or "online"). */
    var engineKey: String
        get() = sp.getString("engine", "mlkit") ?: "mlkit"
        set(v) = sp.edit().putString("engine", v).apply()

    /** ISO code of the language of the text in the manga. */
    var sourceLang: String
        get() = sp.getString("lang", "ko") ?: "ko"
        set(v) = sp.edit().putString("lang", v).apply()

    var apiBase: String
        get() = sp.getString("api_base", "https://api.openai.com/v1") ?: ""
        set(v) = sp.edit().putString("api_base", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    var apiModel: String
        get() = sp.getString("api_model", "") ?: ""
        set(v) = sp.edit().putString("api_model", v).apply()

    /** The picked manga root folder (a persisted Storage Access Framework tree URI), or null. */
    var rootUri: String?
        get() = sp.getString("root", null)
        set(v) = sp.edit().putString("root", v).apply()

    fun markRead(titleDoc: String, chapterDoc: String) {
        val set = HashSet(sp.getStringSet("read", emptySet()) ?: emptySet())
        set.add("$titleDoc|$chapterDoc")
        sp.edit().putStringSet("read", set).putString("last_$titleDoc", chapterDoc).apply()
    }

    fun isRead(titleDoc: String, chapterDoc: String): Boolean =
        (sp.getStringSet("read", emptySet()) ?: emptySet()).contains("$titleDoc|$chapterDoc")

    fun lastChapter(titleDoc: String): String? = sp.getString("last_$titleDoc", null)

    fun online() = OnlineConfig(apiBase.trim(), apiKey.trim(), apiModel.trim())
}

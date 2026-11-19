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

    fun online() = OnlineConfig(apiBase.trim(), apiKey.trim(), apiModel.trim())
}

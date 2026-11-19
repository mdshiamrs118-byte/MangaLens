package app.mangalens

import android.content.Context

class OnlineConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mangalens", Context.MODE_PRIVATE)

    var engineId: String
        get() = sp.getString("engine", "mlkit") ?: "mlkit"
        set(v) = sp.edit().putString("engine", v).apply()

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

package app.mangalens

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists every translator: download, delete, configure and choose the one to use. */
class ModelsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var list: LinearLayout

    private val jobs = HashMap<String, Job>()
    private val progress = HashMap<String, String>()
    private var mlkitReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Translators"
        prefs = Prefs(this)
        store = ModelStore(this)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        setContentView(ScrollView(this).apply { addView(list) })
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refreshMlKit()
            render()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private suspend fun refreshMlKit() {
        mlkitReady = try {
            MlKitTranslator.isDownloaded()
        } catch (e: Exception) {
            false
        }
    }

    private fun isReady(info: EngineInfo): Boolean = when (info.kind) {
        EngineKind.MLKIT -> mlkitReady
        EngineKind.ONNX -> store.isDownloaded(info)
        EngineKind.ONLINE -> prefs.online().isComplete
    }

    private fun statusOf(info: EngineInfo): String {
        progress[info.id]?.let { return it }
        return when (info.kind) {
            EngineKind.ONLINE -> if (isReady(info)) "Configured" else "Needs base URL, API key and model"
            else -> if (isReady(info)) "Downloaded" else "Not downloaded"
        }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun render() {
        list.removeAllViews()
        for (info in Catalog.engines) list.addView(row(info))
    }

    private fun row(info: EngineInfo): LinearLayout {
        val selected = prefs.engineId == info.id
        val running = jobs[info.id]?.isActive == true

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }
        card.addView(TextView(this).apply {
            text = (if (selected) "● " else "") + info.title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply { text = info.description })
        card.addView(TextView(this).apply { text = "Size: ${info.approxSize}  •  ${statusOf(info)}" })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (running) {
            buttons.addView(button("Cancel") { jobs[info.id]?.cancel() })
        } else {
            if (info.kind == EngineKind.ONLINE) buttons.addView(button("Configure") { configureOnline() })
            if (isReady(info)) {
                if (!selected) buttons.addView(button("Use") {
                    prefs.engineId = info.id
                    render()
                })
                if (info.kind != EngineKind.ONLINE) buttons.addView(button("Delete") { confirmDelete(info) })
            } else if (info.kind != EngineKind.ONLINE) {
                buttons.addView(button("Download") { startDownload(info) })
            }
        }
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun startDownload(info: EngineInfo) {
        jobs[info.id] = lifecycleScope.launch {
            try {
                if (info.kind == EngineKind.MLKIT) {
                    progress[info.id] = "Downloading the Korean language pack…"
                    render()
                    MlKitTranslator.download()
                } else {
                    store.download(info) { msg ->
                        progress[info.id] = msg
                        runOnUiThread { render() }
                    }
                }
                toast("${info.title} downloaded.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Download failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                jobs.remove(info.id)
                progress.remove(info.id)
                lifecycleScope.launch {
                    refreshMlKit()
                    render()
                }
            }
        }
        render()
    }

    private fun confirmDelete(info: EngineInfo) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${info.title}?")
            .setMessage("You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> delete(info) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(info: EngineInfo) {
        lifecycleScope.launch {
            try {
                Engines.release(info.id)
                if (info.kind == EngineKind.MLKIT) {
                    MlKitTranslator.delete()
                } else {
                    withContext(Dispatchers.IO) { store.delete(info) }
                }
            } catch (e: Exception) {
                toast("Delete failed: ${e.message ?: e.javaClass.simpleName}")
            }
            refreshMlKit()
            render()
        }
    }

    private fun configureOnline() {
        val base = EditText(this).apply {
            hint = "Base URL, e.g. https://api.openai.com/v1"
            setText(prefs.apiBase)
            setSingleLine()
        }
        val key = EditText(this).apply {
            hint = "API key"
            setText(prefs.apiKey)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = EditText(this).apply {
            hint = "Model name"
            setText(prefs.apiModel)
            setSingleLine()
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
            addView(base)
            addView(key)
            addView(model)
        }
        AlertDialog.Builder(this)
            .setTitle("Online LLM")
            .setMessage("Any OpenAI-compatible /chat/completions endpoint works. The key is stored only on this phone.")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                prefs.apiBase = base.text.toString()
                prefs.apiKey = key.text.toString()
                prefs.apiModel = model.text.toString()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

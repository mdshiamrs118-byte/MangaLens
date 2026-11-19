package app.mangalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists every translator: download, copy links, load from files, delete, configure and choose. */
class ModelsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ModelStore
    private lateinit var list: LinearLayout

    private val jobs = HashMap<String, Job>()
    private val progress = HashMap<String, String>()
    private val errors = HashMap<String, String>()
    private var mlkitReady = false
    private var importTarget: EngineInfo? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val info = importTarget
        if (info != null && uris.isNotEmpty()) importFiles(info, uris)
    }

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

    private fun describe(e: Throwable): String {
        var msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        e.cause?.let { msg += " (" + it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") + ")" }
        return msg
    }

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
        errors[info.id]?.let { err ->
            card.addView(TextView(this).apply {
                text = err
                setTextColor(0xFFCC0000.toInt())
                setTextIsSelectable(true)
            })
        }

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
            } else if (info.kind == EngineKind.MLKIT) {
                buttons.addView(button("Download") { startDownload(info) })
            } else if (info.kind == EngineKind.ONNX) {
                buttons.addView(button("Download") { startDownload(info) })
                buttons.addView(button("Links") { showLinks(info) })
                buttons.addView(button("From files") { pickFor(info) })
            }
        }
        card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
    }

    // ---- Download ---------------------------------------------------------------------------

    private fun startDownload(info: EngineInfo) {
        errors.remove(info.id)
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
                var msg = "Download failed: " + describe(e)
                if (info.kind == EngineKind.MLKIT) msg += "\n(ML Kit needs Google Play services and an internet connection.)"
                if (info.kind == EngineKind.ONNX) msg += "\nTry Links or From files instead."
                errors[info.id] = msg
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

    // ---- Links and import -------------------------------------------------------------------

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Model link", text))
        toast("Copied")
    }

    private fun showLinks(info: EngineInfo) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        form.addView(TextView(this).apply {
            text = "Open each link in your browser, save the file with its original name, then tap From files " +
                "and select all of them."
        })
        for (rf in info.files) {
            form.addView(TextView(this).apply {
                text = rf.originalName
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, 0)
            })
            form.addView(TextView(this).apply {
                text = rf.url
                textSize = 12f
                setTextIsSelectable(true)
            })
            form.addView(button("Copy link") { copy(rf.url) })
        }
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Copy all links") { _, _ -> copy(info.files.joinToString("\n") { it.url }) }
            .setNeutralButton("From files") { _, _ -> pickFor(info) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickFor(info: EngineInfo) {
        importTarget = info
        pickFiles.launch(arrayOf("*/*"))
    }

    private fun importFiles(info: EngineInfo, uris: List<Uri>) {
        errors.remove(info.id)
        progress[info.id] = "Copying files…"
        render()
        lifecycleScope.launch {
            try {
                Engines.release(info.id)
                val r = store.importFiles(info, contentResolver, uris)
                val sb = StringBuilder()
                sb.append("Loaded: ").append(if (r.imported.isEmpty()) "nothing" else r.imported.joinToString(", "))
                if (r.skipped.isNotEmpty()) {
                    sb.append("\n\nIgnored (name not recognised): ").append(r.skipped.joinToString(", "))
                }
                if (r.missing.isEmpty()) {
                    sb.append("\n\nAll files are in place. Tap Use.")
                } else {
                    sb.append("\n\nStill missing: ").append(r.missing.joinToString(", "))
                }
                AlertDialog.Builder(this@ModelsActivity)
                    .setTitle("Load from files")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors[info.id] = "Import failed: " + describe(e)
            } finally {
                progress.remove(info.id)
                render()
            }
        }
    }

    // ---- Delete and online setup ------------------------------------------------------------

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
                errors[info.id] = "Delete failed: " + describe(e)
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

package app.mangalens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
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
            setPadding(dp(12), dp(4), dp(12), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@ModelsActivity, R.color.bg))
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(4))
        }
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() })
        header.addView(Ui.text(this, "Translators", 22f, R.color.on_bg, true).apply { setPadding(dp(8), 0, 0, 0) })
        root.addView(header)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refreshMlKit()
            render()
        }
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun lang(): Lang = Langs.byCode(prefs.sourceLang)

    private fun describe(e: Throwable): String {
        var msg = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        e.cause?.let { msg += " (" + it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") + ")" }
        return msg
    }

    private suspend fun refreshMlKit() {
        mlkitReady = try {
            MlKitTranslator.isDownloaded(lang().code)
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

    private fun button(label: String, filled: Boolean = false, onClick: () -> Unit): View =
        Pill(this, filled).apply {
            set(label, null)
            onTap(onClick)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8); topMargin = dp(10) }
        }

    private fun render() {
        list.removeAllViews()
        list.addView(Ui.text(
            this,
            "Text language: ${lang().name}. Change it on the home or reader screen; the downloads below are for this language.",
            13f, R.color.on_muted,
        ).apply { setPadding(dp(4), dp(4), dp(4), dp(12)) })
        for (info in Catalog.engines(lang())) list.addView(row(info))
    }

    private fun row(info: EngineInfo): LinearLayout {
        val selected = prefs.engineKey == info.key
        val running = jobs[info.id]?.isActive == true

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = Ui.rounded(
                Ui.color(this@ModelsActivity, R.color.surface), dp(16).toFloat(),
                if (selected) Ui.color(this@ModelsActivity, R.color.accent) else Ui.color(this@ModelsActivity, R.color.outline),
                dp(if (selected) 2 else 1),
            )
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleRow.addView(Ui.text(this, info.title, 17f, R.color.on_bg, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) titleRow.addView(Ui.text(this, "IN USE", 11f, R.color.accent, true))
        card.addView(titleRow)
        card.addView(Ui.text(this, info.description, 13f, R.color.on_muted).apply { setPadding(0, dp(6), 0, dp(6)) })
        val ready = isReady(info)
        card.addView(Ui.text(
            this, "Size: ${info.approxSize}  •  ${statusOf(info)}", 12f,
            if (ready) R.color.ok else R.color.on_muted,
        ))
        errors[info.id]?.let { err ->
            card.addView(Ui.text(this, err, 12f, R.color.error).apply {
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            })
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (running) {
            buttons.addView(button("Cancel") { jobs[info.id]?.cancel() })
        } else {
            if (info.kind == EngineKind.ONLINE) buttons.addView(button("Configure") { configureOnline() })
            if (ready) {
                if (!selected) buttons.addView(button("Use", true) {
                    prefs.engineKey = info.key
                    render()
                })
                if (info.kind != EngineKind.ONLINE) buttons.addView(button("Delete") { confirmDelete(info) })
            } else if (info.kind == EngineKind.MLKIT) {
                buttons.addView(button("Download", true) { startDownload(info) })
            } else if (info.kind == EngineKind.ONNX) {
                buttons.addView(button("Download", true) { startDownload(info) })
                buttons.addView(button("Links") { showLinks(info) })
                buttons.addView(button("From files") { pickFor(info) })
            }
        }
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        }
        card.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    // ---- Download ---------------------------------------------------------------------------

    private fun startDownload(info: EngineInfo) {
        errors.remove(info.id)
        jobs[info.id] = lifecycleScope.launch {
            try {
                if (info.kind == EngineKind.MLKIT) {
                    progress[info.id] = "Downloading the language pack…"
                    render()
                    MlKitTranslator.download(lang().code)
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
                    MlKitTranslator.delete(lang().code)
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

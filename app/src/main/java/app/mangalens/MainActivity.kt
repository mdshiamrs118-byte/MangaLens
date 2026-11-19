package app.mangalens

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: StripAdapter
    private lateinit var status: TextView
    private lateinit var engineLabel: TextView
    private lateinit var langButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var translateBtn: Button
    private lateinit var saveBtn: Button

    private val ocr by lazy { Ocr() }
    private val loader = RegionLoader()

    private var pages: List<Page> = emptyList()
    private var translateJob: Job? = null
    private var cameraUri: Uri? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) loadUris(uris)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) loadUris(listOf(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(button("Camera") { launchCamera() })
        topRow.addView(button("Gallery") { pickImages.launch("image/*") })
        topRow.addView(button("Translators") { openModels() })
        root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        langButton = Button(this).apply { setOnClickListener { chooseLanguage() } }
        root.addView(langButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        engineLabel = TextView(this).apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { openModels() }
        }
        root.addView(engineLabel)

        recycler = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        adapter = StripAdapter(lifecycleScope, loader, recycler) { toggle() }
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            text = "Pick one or more images (Gallery), or take a photo. Choose the text language first."
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(status)

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        translateBtn = button("Translate") {
            if (translateJob?.isActive == true) translateJob?.cancel() else runTranslate()
        }
        translateBtn.isEnabled = false
        saveBtn = button("Save") { saveAll() }
        saveBtn.isEnabled = false
        bottomRow.addView(translateBtn)
        bottomRow.addView(saveBtn)
        root.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        refreshLabels()
    }

    override fun onResume() {
        super.onResume()
        refreshLabels()
    }

    override fun onDestroy() {
        super.onDestroy()
        ocr.close()
        loader.close()
    }

    // ---- UI helpers -------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun lang(): Lang = Langs.byCode(prefs.sourceLang)

    private fun refreshLabels() {
        val l = lang()
        langButton.text = "Text language: ${l.name} (tap to change)"
        engineLabel.text = "Translator: ${Catalog.byKey(prefs.engineKey, l).title}  (tap to change)"
    }

    private fun openModels() {
        startActivity(Intent(this, ModelsActivity::class.java))
    }

    private fun chooseLanguage() {
        val names = Langs.all.map { it.name }.toTypedArray()
        val current = Langs.all.indexOfFirst { it.code == prefs.sourceLang }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Language of the text in the manga")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.sourceLang = Langs.all[which].code
                refreshLabels()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun anyTranslated(): Boolean = pages.any { it.translated != null }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        translateBtn.text = if (busy) "Cancel" else "Translate"
        saveBtn.isEnabled = !busy && anyTranslated()
    }

    private fun toggle() {
        if (!anyTranslated()) return
        adapter.showTranslated = !adapter.showTranslated
        adapter.refreshAll()
        status.text = if (adapter.showTranslated) {
            "Showing the translation. Tap the pages to see the originals."
        } else {
            "Showing the originals. Tap the pages to see the translation."
        }
    }

    // ---- Image input ------------------------------------------------------------------------

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("Camera unavailable: ${e.message}")
        }
    }

    private fun loadUris(uris: List<Uri>) {
        translateJob?.cancel()
        lifecycleScope.launch {
            try {
                progress.visibility = View.VISIBLE
                pages = emptyList()
                adapter.showTranslated = false
                adapter.setPages(emptyList())
                loader.clear()

                val loaded = PageIO.importUris(applicationContext, uris) { i, n ->
                    runOnUiThread { status.text = "Loading images… ${i + 1}/$n" }
                }
                pages = loaded
                adapter.setPages(loaded)
                translateBtn.isEnabled = loaded.isNotEmpty()
                status.text = if (loaded.isEmpty()) {
                    "Could not read those images."
                } else {
                    "${loaded.size} image(s) loaded in name order. Tap Translate."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status.text = "Could not open the images: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- Translate --------------------------------------------------------------------------

    private fun runTranslate() {
        if (pages.isEmpty()) {
            toast("Pick or take a photo first.")
            return
        }
        val l = lang()
        val info = Catalog.byKey(prefs.engineKey, l)
        val stamp = System.currentTimeMillis()
        setBusy(true)
        translateJob = lifecycleScope.launch {
            try {
                status.text = "Loading ${info.title}…"
                val translator = Engines.obtain(applicationContext, info, l)

                var boxes = 0
                var pagesWithText = 0
                for ((i, page) in pages.withIndex()) {
                    val label = "Page ${i + 1}/${pages.size}"
                    status.text = "$label: reading text…"
                    val bmp = withContext(Dispatchers.IO) { PageIO.decodeForOcr(page.file) } ?: continue
                    try {
                        val blocks = ocr.recognize(bmp, l.script) { status.text = "$label: $it" }
                        val bubbles = Grouping.group(blocks, l.script)
                        if (bubbles.isEmpty()) continue

                        val results = translator.translate(bubbles.map { it.text }) { done, total ->
                            runOnUiThread { status.text = "$label: translating ${minOf(done + 1, total)}/$total…" }
                        }

                        status.text = "$label: drawing…"
                        val out = withContext(Dispatchers.Default) { OverlayRenderer.render(bmp, bubbles, results) }
                        val target = File(PageIO.translatedDir(applicationContext), "t${stamp}_$i.jpg")
                        withContext(Dispatchers.IO) {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out.compress(Bitmap.CompressFormat.JPEG, 93, it) }
                        }
                        out.recycle()

                        page.translated?.delete()
                        page.translated = target
                        boxes += bubbles.size
                        pagesWithText++
                        if (!adapter.showTranslated) {
                            adapter.showTranslated = true
                            adapter.refreshAll()
                        } else {
                            adapter.pageChanged(i)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                status.text = if (pagesWithText == 0) {
                    "No ${l.name} text found. Check the text language."
                } else {
                    "Done: $boxes text boxes on $pagesWithText page(s). Tap the pages to compare with the originals."
                }
            } catch (e: CancellationException) {
                status.text = "Cancelled."
                throw e
            } catch (e: Throwable) {
                status.text = "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- Save -------------------------------------------------------------------------------

    private fun saveAll() {
        if (!anyTranslated()) return
        saveBtn.isEnabled = false
        val snapshot = pages.toList()
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val stamp = System.currentTimeMillis()
                var saved = 0
                var error: String? = null
                for ((i, page) in snapshot.withIndex()) {
                    val src = page.translated ?: continue
                    val name = "MangaLens_${stamp}_" + (i + 1).toString().padStart(3, '0') + ".jpg"
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangaLens")
                            }
                            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                ?: error("Could not create the file")
                            val out = contentResolver.openOutputStream(uri) ?: error("Could not open the file")
                            out.use { o -> FileInputStream(src).use { it.copyTo(o) } }
                        } else {
                            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                            src.copyTo(File(dir, name), overwrite = true)
                        }
                        saved++
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                }
                if (error == null) "Saved $saved page(s) to Pictures/MangaLens" else "Saved $saved page(s). Error: $error"
            }
            toast(msg)
            saveBtn.isEnabled = true
        }
    }
}

package app.mangalens

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var pages: LinearLayout
    private lateinit var status: TextView
    private lateinit var engineLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var translateBtn: Button
    private lateinit var saveBtn: Button

    private val ocr by lazy { Ocr() }

    private var original: Bitmap? = null
    private var translated: Bitmap? = null
    private var showingTranslated = false
    private var translateJob: Job? = null
    private var cameraUri: Uri? = null
    private val chunkCopies = ArrayList<Bitmap>()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) loadImage(uri)
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
        topRow.addView(button("Gallery") { pickImage.launch("image/*") })
        topRow.addView(button("Translators") { openModels() })
        root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        engineLabel = TextView(this).apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { openModels() }
        }
        root.addView(engineLabel)

        pages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(pages, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            text = "Pick a photo or take one, then tap Translate."
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(status)

        val bottomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        translateBtn = button("Translate") {
            if (translateJob?.isActive == true) translateJob?.cancel() else runTranslate()
        }
        translateBtn.isEnabled = false
        saveBtn = button("Save") { save() }
        saveBtn.isEnabled = false
        bottomRow.addView(translateBtn)
        bottomRow.addView(saveBtn)
        root.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        engineLabel.text = "Translator: ${Catalog.byId(prefs.engineId).title}  (tap to change)"
    }

    override fun onDestroy() {
        super.onDestroy()
        ocr.close()
    }

    // ---- UI helpers -------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun openModels() {
        startActivity(Intent(this, ModelsActivity::class.java))
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        translateBtn.text = if (busy) "Cancel" else "Translate"
        saveBtn.isEnabled = !busy && translated != null
    }

    /**
     * Shows a bitmap as a stack of slices. Very tall pages exceed the GPU texture size limit
     * when put into a single ImageView, so each slice is at most 2048 px tall.
     */
    private fun show(bmp: Bitmap) {
        pages.removeAllViews()
        for (c in chunkCopies) c.recycle()
        chunkCopies.clear()

        val sliceH = 2048
        var y = 0
        while (y < bmp.height) {
            val h = minOf(sliceH, bmp.height - y)
            val part = if (y == 0 && h == bmp.height) {
                bmp
            } else {
                Bitmap.createBitmap(bmp, 0, y, bmp.width, h).also { chunkCopies.add(it) }
            }
            val iv = ImageView(this).apply {
                setImageBitmap(part)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { toggle() }
            }
            pages.addView(iv)
            y += h
        }
    }

    private fun toggle() {
        val t = translated ?: return
        val o = original ?: return
        showingTranslated = !showingTranslated
        show(if (showingTranslated) t else o)
        status.text = if (showingTranslated) {
            "Showing the translation. Tap the image to see the original."
        } else {
            "Showing the original. Tap the image to see the translation."
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

    private fun loadImage(uri: Uri) {
        translateJob?.cancel()
        lifecycleScope.launch {
            try {
                status.text = "Loading image…"
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                original = bmp
                translated = null
                showingTranslated = false
                show(bmp)
                translateBtn.isEnabled = true
                saveBtn.isEnabled = false
                status.text = "Image loaded. Tap Translate."
            } catch (e: Throwable) {
                status.text = "Could not open the image: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Decodes with a sample size so the page stays within about 16 megapixels and 3000 px width. */
    private fun decode(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Unsupported image")

        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 16_000_000L ||
            bounds.outWidth / sample > 3000
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Could not decode the image")

        val orientation = try {
            contentResolver.openInputStream(uri)!!.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    // ---- Translate --------------------------------------------------------------------------

    private fun runTranslate() {
        val src = original ?: run {
            toast("Pick or take a photo first.")
            return
        }
        val info = Catalog.byId(prefs.engineId)
        setBusy(true)
        translateJob = lifecycleScope.launch {
            try {
                status.text = "Reading text…"
                val blocks = ocr.recognize(src) { status.text = it }
                val bubbles = Grouping.group(blocks)
                if (bubbles.isEmpty()) {
                    status.text = "No Korean text found on this image."
                    return@launch
                }

                status.text = "Loading ${info.title}…"
                val translator = Engines.obtain(applicationContext, info)
                val results = translator.translate(bubbles.map { it.text }) { done, total ->
                    runOnUiThread { status.text = "Translating ${minOf(done + 1, total)}/$total…" }
                }

                status.text = "Drawing the translation…"
                val out = withContext(Dispatchers.Default) { OverlayRenderer.render(src, bubbles, results) }
                translated = out
                showingTranslated = true
                show(out)
                status.text = "Done: ${bubbles.size} text boxes. Tap the image to compare with the original."
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

    private fun save() {
        val bmp = translated ?: return
        saveBtn.isEnabled = false
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val name = "MangaLens_${System.currentTimeMillis()}.png"
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MangaLens")
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: error("Could not create the file")
                        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        "Saved to Pictures/MangaLens"
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                        val f = File(dir, name)
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        "Saved to ${f.absolutePath}"
                    }
                } catch (e: Exception) {
                    "Save failed: ${e.message}"
                }
            }
            toast(msg)
            saveBtn.isEnabled = true
        }
    }
}

package app.mangalens

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

/**
 * Full-screen webtoon reader. One tap on a page hides or shows every control at once
 * (top bar, bottom bar, page counter and the system bars); long-press flips original / translation.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TREE = "tree"
        private const val EXTRA_TITLE_DOC = "title_doc"
        private const val EXTRA_TITLE_NAME = "title_name"
        private const val EXTRA_CHAPTER_DOC = "chapter_doc"

        fun forChapter(ctx: Context, tree: Uri, titleDoc: String, titleName: String, chapterDoc: String): Intent =
            Intent(ctx, ReaderActivity::class.java)
                .putExtra(EXTRA_TREE, tree.toString())
                .putExtra(EXTRA_TITLE_DOC, titleDoc)
                .putExtra(EXTRA_TITLE_NAME, titleName)
                .putExtra(EXTRA_CHAPTER_DOC, chapterDoc)

        fun forUris(ctx: Context, uris: List<Uri>): Intent {
            val intent = Intent(ctx, ReaderActivity::class.java)
            val clip = ClipData.newUri(ctx.contentResolver, "images", uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return intent
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: StripAdapter
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var pageCounter: TextView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var langChip: Pill
    private lateinit var engineChip: Pill
    private lateinit var translateBtn: Pill
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var compareBtn: ImageButton
    private lateinit var saveBtn: ImageButton

    private val ocr by lazy { Ocr() }
    private val loader = RegionLoader()

    private val pages = ArrayList<Page>()
    private var library: Library? = null
    private var titleDoc = ""
    private var titleName = ""
    private var chapters: List<Chapter> = emptyList()
    private var chapterIndex = -1
    private var loading = false
    private var chromeVisible = true
    private var lastWidth = 0
    private var readerRoot: FrameLayout? = null

    private var loadJob: Job? = null
    private var translateJob: Job? = null

    private lateinit var chromeViews: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val content = buildUi()
        readerRoot = content as? FrameLayout
        WebAiHost.container = readerRoot
        setContentView(content)
        refreshChips()
        start()
    }

    override fun onResume() {
        super.onResume()
        refreshChips()
    }

    override fun onDestroy() {
        if (WebAiHost.container === readerRoot) WebAiHost.container = null
        super.onDestroy()
        ocr.close()
        loader.close()
    }

    // ---- UI ---------------------------------------------------------------------------------

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.color(this, R.color.bg))

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = StripAdapter(
            lifecycleScope, loader, recycler, Ui.color(this, R.color.surface),
            onTap = { toggleChrome() },
            onLongTap = { toggleCompare() },
        )
        recycler.adapter = adapter
        recycler.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (lastWidth != 0 && w != lastWidth) adapter.refreshAll()
            lastWidth = w
        }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateCounter()
        })
        root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        root.setOnClickListener { toggleChrome() }

        // top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Scrims.top()
            setPadding(dp(8), dp(4), dp(12), dp(24))
        }
        topBar.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = Ui.singleLine(Ui.text(this, "", 16f, R.color.on_bg, true))
        subText = Ui.singleLine(Ui.text(this, "", 12f, R.color.on_muted))
        names.addView(titleText)
        names.addView(subText)
        topBar.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        pageCounter = Ui.text(this, "", 13f, R.color.on_bg, true).apply {
            background = Ui.rounded(0x66000000, dp(14).toFloat())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.INVISIBLE
        }
        topBar.addView(pageCounter)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Scrims.bottom()
            setPadding(dp(12), dp(28), dp(12), dp(10))
        }
        status = Ui.text(this, "", 12f, R.color.on_muted).apply {
            maxLines = 2
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.color(this@ReaderActivity, R.color.accent))
        }
        bottomBar.addView(status)
        bottomBar.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { bottomMargin = dp(8) })

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langChip = Pill(this, false).apply { onTap { chooseLanguage() } }
        engineChip = Pill(this, false).apply { onTap { startActivity(Intent(this@ReaderActivity, ModelsActivity::class.java)) } }
        chips.addView(langChip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        chips.addView(engineChip, LinearLayout.LayoutParams(0, -2, 1f))
        bottomBar.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prevBtn = Ui.iconButton(this, R.drawable.ic_prev, "Previous chapter") { goChapter(-1) }
        nextBtn = Ui.iconButton(this, R.drawable.ic_next, "Next chapter") { goChapter(1) }
        compareBtn = Ui.iconButton(this, R.drawable.ic_compare, "Show original / translation") { toggleCompare() }
        saveBtn = Ui.iconButton(this, R.drawable.ic_save, "Save translated pages") { saveAll() }
        translateBtn = Pill(this, true).apply {
            set("Translate", R.drawable.ic_translate)
            onTap { if (translateJob?.isActive == true) translateJob?.cancel() else runTranslate() }
        }
        actions.addView(prevBtn)
        actions.addView(translateBtn, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })
        actions.addView(compareBtn)
        actions.addView(saveBtn)
        actions.addView(nextBtn)
        bottomBar.addView(actions)
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        chromeViews = listOf(topBar, bottomBar)
        Ui.enable(compareBtn, false)
        Ui.enable(saveBtn, false)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(8), b.top + dp(4), dp(12), dp(24))
            bottomBar.setPadding(dp(12) + b.left, dp(28), dp(12) + b.right, dp(10) + b.bottom)
            insets
        }
        return root
    }

    private fun refreshChips() {
        val l = Langs.byCode(prefs.sourceLang)
        langChip.set("Text: ${l.name}", null)
        engineChip.set(Catalog.byKey(prefs.engineKey, l).title, null)
    }

    private fun chooseLanguage() {
        val names = Langs.all.map { it.name }.toTypedArray()
        val current = Langs.all.indexOfFirst { it.code == prefs.sourceLang }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Language of the text in the manga")
            .setSingleChoiceItems(names, current) { dialog, which ->
                prefs.sourceLang = Langs.all[which].code
                refreshChips()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun setStatus(msg: String) {
        status.text = msg
    }

    private fun updateCounter() {
        val lm = recycler.layoutManager as LinearLayoutManager
        val p = adapter.pageAt(lm.findFirstVisibleItemPosition())
        if (p < 0) {
            pageCounter.visibility = View.INVISIBLE
        } else {
            pageCounter.text = "${p + 1} / ${pages.size}"
            pageCounter.visibility = View.VISIBLE
        }
    }

    // ---- Tap to show / hide every control ---------------------------------------------------

    private fun toggleChrome() = setChrome(!chromeVisible)

    private fun setChrome(show: Boolean) {
        chromeVisible = show
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        if (show) {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
        }
        for (v in chromeViews) {
            v.animate().cancel()
            if (show) {
                if (v.visibility != View.VISIBLE) v.alpha = 0f
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(180).start()
            } else {
                v.animate().alpha(0f).setDuration(180).withEndAction { v.visibility = View.GONE }.start()
            }
        }
    }

    // ---- Loading ----------------------------------------------------------------------------

    private fun start() {
        val tree = intent.getStringExtra(EXTRA_TREE)
        if (tree != null) {
            titleDoc = intent.getStringExtra(EXTRA_TITLE_DOC) ?: ""
            titleName = intent.getStringExtra(EXTRA_TITLE_NAME) ?: ""
            val chapterDoc = intent.getStringExtra(EXTRA_CHAPTER_DOC) ?: ""
            val lib = try {
                Library(this, Uri.parse(tree))
            } catch (e: Exception) {
                setStatus("Could not open the folder.")
                return
            }
            library = lib
            titleText.text = titleName
            loadJob = lifecycleScope.launch {
                setStatus("Reading the folder…")
                chapters = withContext(Dispatchers.IO) { lib.chapters(Title(titleDoc, titleName, 0)) }
                val at = chapters.indexOfFirst { it.docId == chapterDoc }
                if (at < 0) {
                    setStatus("Chapter not found.")
                    return@launch
                }
                openChapter(at)
            }
        } else {
            val clip = intent.clipData
            val uris = ArrayList<Uri>()
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            titleText.text = if (uris.size == 1) "Photo" else "Images"
            subText.text = "${uris.size} image" + if (uris.size == 1) "" else "s"
            updateNav()
            loadJob = lifecycleScope.launch {
                val sorted = withContext(Dispatchers.IO) { PageIO.sortedByName(contentResolver, uris) }
                loadSources(sorted)
            }
        }
    }

    private fun goChapter(delta: Int) {
        val i = chapterIndex + delta
        if (i !in chapters.indices) return
        loadJob?.cancel()
        loadJob = lifecycleScope.launch { openChapter(i) }
    }

    private suspend fun openChapter(index: Int) {
        val lib = library ?: return
        translateJob?.cancel()
        chapterIndex = index
        val ch = chapters[index]
        subText.text = ch.name
        prefs.markRead(titleDoc, ch.docId)
        updateNav()
        val entries = withContext(Dispatchers.IO) { lib.images(ch.docId) }
        loadSources(entries.map { it.name to lib.uriOf(it.docId) })
    }

    private fun updateNav() {
        val multi = chapters.size > 1
        prevBtn.visibility = if (multi) View.VISIBLE else View.GONE
        nextBtn.visibility = if (multi) View.VISIBLE else View.GONE
        Ui.enable(prevBtn, chapterIndex > 0)
        Ui.enable(nextBtn, chapterIndex in 0 until chapters.size - 1)
    }

    private suspend fun loadSources(sources: List<Pair<String, Uri>>) {
        loading = true
        Ui.enable(translateBtn, false)
        progress.visibility = View.VISIBLE
        try {
            loader.clear()
            pages.clear()
            adapter.clear()
            PageIO.reset(applicationContext)
            Ui.enable(compareBtn, false)
            Ui.enable(saveBtn, false)
            recycler.scrollToPosition(0)

            if (sources.isEmpty()) {
                setStatus("No images in this chapter.")
                return
            }
            val stamp = System.currentTimeMillis()
            for ((i, s) in sources.withIndex()) {
                setStatus("Loading pages… ${i + 1}/${sources.size}")
                val page = PageIO.importOne(applicationContext, s.first, s.second, i, stamp) ?: continue
                pages.add(page)
                adapter.append(page)
                updateCounter()
            }
            setStatus(
                if (pages.isEmpty()) "Could not read those images."
                else "${pages.size} page(s). Tap a page to hide the controls, long-press to compare."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            setStatus("Could not load the pages: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            loading = false
            progress.visibility = View.GONE
            Ui.enable(translateBtn, pages.isNotEmpty())
        }
    }

    // ---- Translate --------------------------------------------------------------------------

    private fun anyTranslated(): Boolean = pages.any { it.translated != null }

    private fun refreshCompare() {
        Ui.enable(compareBtn, anyTranslated())
        Ui.setTint(compareBtn, Ui.color(this, if (adapter.showTranslated) R.color.accent else R.color.on_bg))
    }

    private fun toggleCompare() {
        if (!anyTranslated()) return
        adapter.showTranslated = !adapter.showTranslated
        adapter.refreshAll()
        refreshCompare()
        setStatus(if (adapter.showTranslated) "Showing the translation." else "Showing the originals.")
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        translateBtn.set(if (busy) "Stop" else "Translate", if (busy) R.drawable.ic_stop else R.drawable.ic_translate)
        Ui.enable(saveBtn, !busy && anyTranslated())
        refreshCompare()
    }

    private fun runTranslate() {
        if (loading || pages.isEmpty()) {
            toast("Wait until the pages have loaded.")
            return
        }
        val l = Langs.byCode(prefs.sourceLang)
        val info = Catalog.byKey(prefs.engineKey, l)
        val stamp = System.currentTimeMillis()
        val work = pages.toList()
        setBusy(true)
        translateJob = lifecycleScope.launch {
            try {
                setStatus("Loading ${info.title}…")
                val translator = Engines.obtain(applicationContext, info, l)

                var boxes = 0
                var pagesWithText = 0
                for ((i, page) in work.withIndex()) {
                    val label = "Page ${i + 1}/${work.size}"
                    setStatus("$label: reading text…")
                    val bmp = withContext(Dispatchers.IO) { PageIO.decodeForOcr(page.file) } ?: continue
                    try {
                        val blocks = ocr.recognize(bmp, l.script) { setStatus("$label: $it") }
                        val bubbles = Grouping.group(blocks, l.script)
                        if (bubbles.isEmpty()) continue

                        val results = translator.translate(bubbles.map { it.text }) { done, total ->
                            runOnUiThread { setStatus("$label: translating ${minOf(done + 1, total)}/$total…") }
                        }

                        setStatus("$label: drawing…")
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
                        refreshCompare()
                    } finally {
                        bmp.recycle()
                    }
                }
                setStatus(
                    if (pagesWithText == 0) "No ${l.name} text found. Check the text language."
                    else "Done: $boxes text boxes on $pagesWithText page(s). Long-press a page to compare."
                )
            } catch (e: CancellationException) {
                setStatus("Stopped.")
                throw e
            } catch (e: Throwable) {
                setStatus("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    // ---- Save -------------------------------------------------------------------------------

    private fun saveAll() {
        if (!anyTranslated()) return
        Ui.enable(saveBtn, false)
        val snapshot = pages.toList()
        val prefix = (titleName.ifBlank { "MangaLens" }).replace(Regex("[^A-Za-z0-9._-]+"), "_")
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val stamp = System.currentTimeMillis()
                var saved = 0
                var error: String? = null
                for ((i, page) in snapshot.withIndex()) {
                    val src = page.translated ?: continue
                    val name = "${prefix}_${stamp}_" + (i + 1).toString().padStart(3, '0') + ".jpg"
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
            Ui.enable(saveBtn, anyTranslated())
        }
    }
}

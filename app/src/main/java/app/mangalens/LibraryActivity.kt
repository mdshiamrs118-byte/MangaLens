package app.mangalens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home screen: the manga folder as a grid of series, each opening its list of chapters.
 * Also the entry point for one-off images from the gallery or the camera.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var library: Library? = null
    private var titles: List<Title> = emptyList()
    private var openTitle: Title? = null
    private var chapters: List<Chapter> = emptyList()
    private var scanJob: Job? = null
    private var cameraUri: Uri? = null

    private lateinit var backBtn: ImageButton
    private lateinit var logo: ImageView
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var langChip: Pill
    private lateinit var engineChip: Pill
    private lateinit var grid: RecyclerView
    private lateinit var chapterList: RecyclerView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var loading: ProgressBar
    private lateinit var bottomBar: LinearLayout

    private val titleAdapter = TitleAdapter()
    private val chapterAdapter = ChapterAdapter()
    private val covers = object : LruCache<String, android.graphics.Bitmap>(40) {}

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeTitle()
    }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onTreePicked(uri)
    }
    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) startActivity(ReaderActivity.forUris(this, uris))
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) startActivity(ReaderActivity.forUris(this, listOf(uri)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        onBackPressedDispatcher.addCallback(this, backCallback)
        setContentView(buildUi())
        loadRoot()
    }

    override fun onResume() {
        super.onResume()
        refreshChips()
        chapterAdapter.notifyDataSetChanged()
    }

    // ---- UI ---------------------------------------------------------------------------------

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@LibraryActivity, R.color.bg))
            fitsSystemWindows = true
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        backBtn = Ui.iconButton(this, R.drawable.ic_back, "Back") { closeTitle() }
        backBtn.visibility = View.GONE
        header.addView(backBtn)
        logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) }
        }
        header.addView(logo)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerTitle = Ui.singleLine(Ui.text(this, "MangaLens", 22f, R.color.on_bg, true))
        headerSub = Ui.singleLine(Ui.text(this, "", 12f, R.color.on_muted))
        titles.addView(headerTitle)
        titles.addView(headerSub)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.iconButton(this, R.drawable.ic_refresh, "Rescan folder") { rescan() })
        header.addView(Ui.iconButton(this, R.drawable.ic_translate, "Translators") {
            startActivity(Intent(this, ModelsActivity::class.java))
        })
        root.addView(header)

        // chips
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
        }
        langChip = Pill(this, false).apply { onTap { chooseLanguage() } }
        engineChip = Pill(this, false).apply {
            onTap { startActivity(Intent(this@LibraryActivity, ModelsActivity::class.java)) }
        }
        chips.addView(langChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        chips.addView(engineChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(chips)

        // content
        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@LibraryActivity, spanCount())
            adapter = titleAdapter
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        chapterList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LibraryActivity)
            adapter = chapterAdapter
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(16))
            visibility = View.GONE
        }
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(16), dp(32), dp(16))
        }
        empty.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(Ui.color(this@LibraryActivity, R.color.accent))
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        })
        emptyTitle = Ui.text(this, "Choose your manga folder", 20f, R.color.on_bg, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        emptyText = Ui.text(
            this,
            "Pick the folder that holds your series. MangaLens expects\nManga Title / Chapter 1 / 001.jpg, 002.jpg …",
            14f, R.color.on_muted,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        empty.addView(emptyTitle)
        empty.addView(emptyText)
        empty.addView(Pill(this, true).apply {
            set("Choose folder", R.drawable.ic_folder)
            onTap { pickTree.launch(null) }
        })
        loading = ProgressBar(this).apply { isIndeterminate = true }
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))
        content.addView(chapterList, FrameLayout.LayoutParams(-1, -1))
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        content.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // bottom bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.color(this@LibraryActivity, R.color.surface))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bottomBar.addView(tab(R.drawable.ic_folder, "Folder") { pickTree.launch(null) })
        bottomBar.addView(tab(R.drawable.ic_image, "Images") { pickImages.launch("image/*") })
        bottomBar.addView(tab(R.drawable.ic_camera, "Camera") { launchCamera() })
        root.addView(bottomBar)
        return root
    }

    private fun spanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return (widthDp / 120f).toInt().coerceIn(2, 5)
    }

    private fun tab(icon: Int, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            background = Ui.ripple(Ui.rounded(0x00000000, dp(14).toFloat()))
            setOnClickListener { onClick() }
        }
        col.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Ui.color(this@LibraryActivity, R.color.on_bg))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        col.addView(Ui.text(this, label, 12f, R.color.on_muted).apply { setPadding(0, dp(2), 0, 0) })
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        return col
    }

    private fun refreshChips() {
        val l = Langs.byCode(prefs.sourceLang)
        langChip.set("Text: ${l.name}", R.drawable.ic_translate)
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

    // ---- States -----------------------------------------------------------------------------

    private fun showEmpty(title: String, text: String) {
        emptyTitle.text = title
        emptyText.text = text
        empty.visibility = View.VISIBLE
        grid.visibility = View.GONE
        chapterList.visibility = View.GONE
        loading.visibility = View.GONE
    }

    private fun showTitles() {
        openTitle = null
        backCallback.isEnabled = false
        backBtn.visibility = View.GONE
        logo.visibility = View.VISIBLE
        headerTitle.text = "MangaLens"
        headerSub.text = library?.let { it.nameOf(it.rootId) } ?: ""
        bottomBar.visibility = View.VISIBLE
        empty.visibility = View.GONE
        chapterList.visibility = View.GONE
        loading.visibility = View.GONE
        grid.visibility = View.VISIBLE
        titleAdapter.notifyDataSetChanged()
    }

    private fun showLoading() {
        empty.visibility = View.GONE
        grid.visibility = View.GONE
        chapterList.visibility = View.GONE
        loading.visibility = View.VISIBLE
    }

    // ---- Folder -----------------------------------------------------------------------------

    private fun onTreePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // some providers only grant for this session; scanning still works until the app closes
        }
        prefs.rootUri = uri.toString()
        covers.evictAll()
        openTitle = null
        loadRoot()
    }

    private fun rescan() {
        covers.evictAll()
        if (openTitle != null) openTitle = null
        loadRoot()
    }

    private fun loadRoot() {
        val saved = prefs.rootUri
        if (saved == null) {
            library = null
            showEmpty("Choose your manga folder",
                "Pick the folder that holds your series. MangaLens expects\nManga Title / Chapter 1 / 001.jpg, 002.jpg …")
            bottomBar.visibility = View.VISIBLE
            headerSub.text = ""
            return
        }
        val uri = Uri.parse(saved)
        val granted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!granted) {
            prefs.rootUri = null
            library = null
            showEmpty("Folder access was lost", "Choose the manga folder again to give MangaLens access.")
            return
        }
        val lib = try {
            Library(this, uri)
        } catch (e: Exception) {
            prefs.rootUri = null
            showEmpty("Could not open that folder", "Choose a different folder.")
            return
        }
        library = lib
        backBtn.visibility = View.GONE
        logo.visibility = View.VISIBLE
        showLoading()
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { lib.titles() }
            titles = found
            if (found.isEmpty()) {
                showEmpty("No series found",
                    "Inside the folder, each series needs its own folder with chapter folders full of images:\nManga Title / Chapter 1 / 001.jpg")
            } else {
                showTitles()
            }
        }
    }

    // ---- Series and chapters ----------------------------------------------------------------

    private fun open(title: Title) {
        val lib = library ?: return
        openTitle = title
        backCallback.isEnabled = true
        backBtn.visibility = View.VISIBLE
        logo.visibility = View.GONE
        headerTitle.text = title.name
        headerSub.text = "${title.chapters} chapter" + if (title.chapters == 1) "" else "s"
        bottomBar.visibility = View.GONE
        grid.visibility = View.GONE
        showLoading()
        lifecycleScope.launch {
            chapters = withContext(Dispatchers.IO) { lib.chapters(title) }
            if (openTitle !== title) return@launch
            loading.visibility = View.GONE
            chapterList.visibility = View.VISIBLE
            chapterAdapter.notifyDataSetChanged()
            val last = prefs.lastChapter(title.docId)
            val at = chapters.indexOfFirst { it.docId == last }
            if (at >= 0) chapterList.scrollToPosition(at)
        }
    }

    private fun closeTitle() {
        if (openTitle == null) return
        showTitles()
    }

    private fun confirmDeleteTranslation(ch: Chapter, key: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete translation?")
            .setMessage("The translated pages of \"${ch.name}\" will be removed. Your original images are not touched.")
            .setPositiveButton("Delete") { _, _ ->
                TranslationStore.delete(this, key)
                chapterAdapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun read(title: Title, chapter: Chapter) {
        val tree = Uri.parse(prefs.rootUri ?: return)
        startActivity(ReaderActivity.forChapter(this, tree, title.docId, title.name, chapter.docId))
    }

    // ---- Camera -----------------------------------------------------------------------------

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---- Adapters ---------------------------------------------------------------------------

    private inner class TitleAdapter : RecyclerView.Adapter<TitleAdapter.H>() {

        inner class H(val card: LinearLayout, val cover: ImageView, val name: TextView, val sub: TextView) :
            RecyclerView.ViewHolder(card) {
            var job: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val card = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) }
                background = Ui.ripple(Ui.rounded(Ui.color(this@LibraryActivity, R.color.surface), dp(14).toFloat()))
                setPadding(dp(6), dp(6), dp(6), dp(8))
            }
            val cover = ImageView(this@LibraryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(Ui.color(this@LibraryActivity, R.color.surface_high), dp(10).toFloat())
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val name = Ui.text(this@LibraryActivity, "", 14f, R.color.on_bg, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(4), dp(8), dp(4), 0)
            }
            val sub = Ui.text(this@LibraryActivity, "", 12f, R.color.on_muted).apply { setPadding(dp(4), dp(2), dp(4), 0) }
            card.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10))
            card.addView(name)
            card.addView(sub)
            return H(card, cover, name, sub)
        }

        override fun getItemCount() = titles.size

        override fun onBindViewHolder(h: H, position: Int) {
            val t = titles[position]
            h.name.text = t.name
            h.sub.text = "${t.chapters} chapter" + if (t.chapters == 1) "" else "s"
            h.card.setOnClickListener { open(t) }

            val span = (grid.layoutManager as GridLayoutManager).spanCount
            val cardWidth = (grid.width - grid.paddingLeft - grid.paddingRight) / span - dp(8) - dp(12)
            h.cover.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (cardWidth.coerceAtLeast(dp(60)) * 4 / 3))

            h.job?.cancel()
            val cached = covers.get(t.docId)
            h.cover.setImageBitmap(cached)
            if (cached == null) {
                val lib = library ?: return
                h.job = lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        lib.coverUri(t)?.let { lib.thumbnail(it, 400) }
                    }
                    if (bmp != null) {
                        covers.put(t.docId, bmp)
                        h.cover.setImageBitmap(bmp)
                    }
                }
            }
        }

        override fun onViewRecycled(h: H) {
            h.job?.cancel()
            h.cover.setImageDrawable(null)
        }
    }

    private inner class ChapterAdapter : RecyclerView.Adapter<ChapterAdapter.H>() {

        inner class H(
            val row: LinearLayout,
            val dot: View,
            val name: TextView,
            val tag: TextView,
            val more: ImageButton,
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val row = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
                background = Ui.ripple(Ui.rounded(Ui.color(this@LibraryActivity, R.color.surface), dp(14).toFloat()))
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val dot = View(this@LibraryActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Ui.color(this@LibraryActivity, R.color.ok))
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) }
            }
            val name = Ui.text(this@LibraryActivity, "", 15f, R.color.on_bg, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val tag = Ui.text(this@LibraryActivity, "", 12f, R.color.on_muted)
            val more = Ui.iconButton(this@LibraryActivity, R.drawable.ic_more, "More", Ui.color(this@LibraryActivity, R.color.on_muted)) {}
            more.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4); rightMargin = -dp(8) }
            row.addView(dot)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tag)
            row.addView(more)
            return H(row, dot, name, tag, more)
        }

        override fun getItemCount() = chapters.size

        override fun onBindViewHolder(h: H, position: Int) {
            val ch = chapters[position]
            val title = openTitle ?: return
            h.name.text = ch.name
            val last = prefs.lastChapter(title.docId) == ch.docId
            val done = prefs.isRead(title.docId, ch.docId)
            h.tag.text = when {
                last -> "Last read"
                done -> "Read"
                else -> ""
            }
            h.tag.setTextColor(Ui.color(this@LibraryActivity, if (last) R.color.accent else R.color.on_muted))
            h.name.setTextColor(Ui.color(this@LibraryActivity, if (done && !last) R.color.on_muted else R.color.on_bg))
            h.row.setOnClickListener { read(title, ch) }

            val key = TranslationStore.key(title.docId, ch.docId)
            val translated = TranslationStore.has(this@LibraryActivity, key)
            h.dot.visibility = if (translated) View.VISIBLE else View.GONE
            h.more.visibility = if (translated) View.VISIBLE else View.GONE
            h.more.setOnClickListener { anchor ->
                val menu = PopupMenu(this@LibraryActivity, anchor)
                menu.menu.add(0, 1, 0, "Delete translation")
                menu.setOnMenuItemClickListener {
                    confirmDeleteTranslation(ch, key)
                    true
                }
                menu.show()
            }
        }
    }
}

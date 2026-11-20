package app.mangalens

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shows all pages one under the other like a webtoon, as a list of image bands loaded on demand.
 * Pages can be appended while a chapter is still loading.
 */
class StripAdapter(
    private val scope: CoroutineScope,
    private val loader: RegionLoader,
    private val recycler: RecyclerView,
    private val placeholder: Int,
    private val onTap: () -> Unit,
    private val onLongTap: () -> Unit,
) : RecyclerView.Adapter<StripAdapter.Holder>() {

    private val pages = ArrayList<Page>()
    private val slices = ArrayList<Slice>()

    /** True shows the translated pages (where a translation exists), false the originals. */
    var showTranslated = false

    /** Show the red-outlined version of pages that were translated by the fallback translator. */
    var markFallback = true

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
    }

    val pageCount: Int get() = pages.size

    fun clear() {
        pages.clear()
        slices.clear()
        showTranslated = false
        notifyDataSetChanged()
    }

    fun append(page: Page) {
        val index = pages.size
        pages.add(page)
        val added = PageMath.slices(listOf(page.h)).map { it.copy(page = index) }
        val start = slices.size
        slices.addAll(added)
        notifyItemRangeInserted(start, added.size)
    }

    /** Index of the page shown at list position [position], or -1. */
    fun pageAt(position: Int): Int = slices.getOrNull(position)?.page ?: -1

    /** Re-draws the bands of one page, e.g. after its translation is ready. */
    fun pageChanged(page: Int) {
        val first = slices.indexOfFirst { it.page == page }
        if (first < 0) return
        val count = slices.count { it.page == page }
        notifyItemRangeChanged(first, count)
    }

    fun refreshAll() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val iv = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(placeholder)
        }
        return Holder(iv)
    }

    override fun getItemCount(): Int = slices.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = slices[position]
        val page = pages[s.page]
        val width = max(1, recycler.width - recycler.paddingLeft - recycler.paddingRight)
        val height = max(1, ((s.y1 - s.y0) * width.toFloat() / page.w).roundToInt())

        holder.job?.cancel()
        holder.image.setImageDrawable(null)
        holder.image.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        holder.image.setOnClickListener { onTap() }
        holder.image.setOnLongClickListener {
            onLongTap()
            true
        }

        val translated = page.translated
        val marked = page.translatedMarked
        val file = when {
            !showTranslated || translated == null -> page.file
            markFallback && marked != null -> marked
            else -> translated ?: page.file
        }
        holder.job = scope.launch {
            val bmp = loader.slice(file, page.h, s.y0, s.y1, width)
            if (bmp != null) holder.image.setImageBitmap(bmp)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel()
        holder.image.setImageDrawable(null)
    }
}

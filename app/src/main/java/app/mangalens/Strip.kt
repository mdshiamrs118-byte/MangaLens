package app.mangalens

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Shows all pages one under the other like a webtoon, as a list of image bands loaded on demand. */
class StripAdapter(
    private val scope: CoroutineScope,
    private val loader: RegionLoader,
    private val recycler: RecyclerView,
    private val onTap: () -> Unit,
) : RecyclerView.Adapter<StripAdapter.Holder>() {

    private var pages: List<Page> = emptyList()
    private var slices: List<Slice> = emptyList()

    /** True shows the translated pages (where a translation exists), false the originals. */
    var showTranslated = false

    class Holder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var job: Job? = null
    }

    fun setPages(newPages: List<Page>) {
        pages = newPages
        slices = PageMath.slices(newPages.map { it.h })
        notifyDataSetChanged()
    }

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

        val translated = page.translated
        val file = if (showTranslated && translated != null) translated else page.file
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

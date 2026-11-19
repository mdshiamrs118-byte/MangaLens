package app.mangalens

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Small helpers for the programmatic UI so every screen shares one look. */
object Ui {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    fun rounded(fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    /** Wraps [content] in a ripple that follows the same rounded shape. */
    fun ripple(content: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    fun text(ctx: Context, s: String, sp: Float, colorRes: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color(ctx, colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun singleLine(t: TextView): TextView {
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    /** Round icon-only button with a ripple; the icon is tinted white or the given colour. */
    fun iconButton(ctx: Context, iconRes: Int, desc: String, tint: Int = Color.WHITE, onClick: () -> Unit): ImageButton =
        ImageButton(ctx).apply {
            setImageResource(iconRes)
            contentDescription = desc
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.CENTER
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 44))
            setOnClickListener { onClick() }
        }

    fun setTint(b: ImageButton, tint: Int) = b.setColorFilter(tint)

    fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }
}

/** Rounded text button with an optional leading icon; [filled] uses the accent colour. */
class Pill(ctx: Context, private val filled: Boolean) : LinearLayout(ctx) {

    private val icon = ImageView(ctx)
    private val label = TextView(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val h = Ui.dp(ctx, 14)
        setPadding(h, Ui.dp(ctx, 10), h, Ui.dp(ctx, 10))
        val radius = Ui.dp(ctx, 22).toFloat()
        background = if (filled) {
            Ui.ripple(Ui.rounded(Ui.color(ctx, R.color.accent_dark), radius))
        } else {
            Ui.ripple(Ui.rounded(Ui.color(ctx, R.color.surface_high), radius, Ui.color(ctx, R.color.outline), Ui.dp(ctx, 1)))
        }
        icon.layoutParams = LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18)).apply { rightMargin = Ui.dp(ctx, 8) }
        icon.setColorFilter(Color.WHITE)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        label.setTextColor(Color.WHITE)
        label.setTypeface(label.typeface, Typeface.BOLD)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        addView(icon)
        addView(label)
        minimumHeight = Ui.dp(ctx, 44)
    }

    fun set(text: String, iconRes: Int?) {
        label.text = text
        if (iconRes == null) {
            icon.visibility = View.GONE
        } else {
            icon.visibility = View.VISIBLE
            icon.setImageResource(iconRes)
        }
    }

    fun onTap(action: () -> Unit) {
        setOnClickListener { action() }
    }
}

/** Dark-toned vertical gradient used behind the reader's overlays so white controls stay readable. */
object Scrims {
    fun top(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6000000.toInt(), 0x00000000))

    fun bottom(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xF0000000.toInt(), 0x00000000))
}

fun ViewGroup.addMatchWidth(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
    addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
}

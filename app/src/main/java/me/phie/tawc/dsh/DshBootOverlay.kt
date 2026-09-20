package me.phie.tawc.dsh

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import me.phie.tawc.R
import kotlin.math.roundToInt

/**
 * What the user sees until [DshActivity]'s WebView takes over.
 *
 * This is **DSH's own boot splash, not ours.** DSH's web client renders one
 * between its first paint and the shell mount — `BootSplash` in
 * `dsh-web-frontend`, styled by the `_boot_u7vgf_*` CSS module — and it is
 * exactly the interval this overlay covers. Reproducing it rather than
 * inventing a second look means the native surface and the page underneath
 * agree on colour, weight and motion, instead of cutting between two apps.
 *
 * Every number here is read off that module rather than eyeballed:
 *
 * | this file | DSH |
 * | --- | --- |
 * | 20dp spinner, 2dp ring | `.spinner{width:20px;height:20px;border:2px solid}` |
 * | 72° arc | `var(--dsh-boot-arc, 72deg)` |
 * | 0.8s per turn, linear | `animation:_spin .8s linear infinite` |
 * | 16dp stack gap | `.card{gap:16px}` |
 * | 16sp wordmark, .08em | `.wordmark{font-size:16px;letter-spacing:.08em;font-weight:600}` |
 * | 12sp hint | `.hint{font-size:12px}` |
 * | 14sp/600 failure title | `.failedTitle{font-size:14px;font-weight:600}` |
 * | 18dp pill button, 0 14px | `._button{border-radius:18px;padding:0 14px}` |
 *
 * DSH draws a **progress** arc — `72deg + progress*216deg`, driven by its
 * plugin loader — and falls back to a bare 72° when there is no total to
 * measure against. We have no progress to report (the harness announces its
 * port or fails; it reports no steps in between), so we use the fallback.
 *
 * The two themes are the `dsh_boot_*` colour resources, so night mode needs
 * no code here: the pairs are DSH's own light block and its
 * `body[data-ds-dark-theme]` override.
 *
 * **Opaque on purpose.** DSH colours the document canvas before first paint
 * precisely so a half-loaded shell is never visible; a see-through scrim
 * would put back the flash it goes out of its way to avoid.
 */
internal class DshBootOverlay(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    /** DSH's `.wordmark` — the product name above the spinner. */
    private val wordmark = TextView(context).apply {
        text = context.getString(R.string.dsh_boot_wordmark)
        setTextColor(context.getColor(R.color.dsh_boot_label_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, WORDMARK_SP)
        // `.wordmark{letter-spacing:.08em}` — em, which is what
        // TextView.setLetterSpacing already speaks.
        letterSpacing = WORDMARK_LETTER_SPACING_EM
        // `.wordmark{font-weight:600}` — 600, not the 700 that
        // `Typeface.BOLD` means. DSH's scale stops at semibold.
        typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
    }

    /** DSH's `.spinner`. */
    private val spinner = DshSpinner(context)

    /** DSH's `.hint` — the line of state DSH puts under the spinner. */
    private val hint = TextView(context).apply {
        setTextColor(context.getColor(R.color.dsh_boot_label_tertiary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, HINT_SP)
    }

    init {
        // `.boot{background:var(--dsw-alias-bg-base, Canvas)}` — DSH resolves
        // this to its page background, which is what `dsh_boot_bg` holds.
        setBackgroundColor(context.getColor(R.color.dsh_boot_bg))
        // `.boot{height:100%;display:grid;place-items:center}`.
        addView(card, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }

    /**
     * The loading state: wordmark, spinner, hint — DSH's
     * `card.replaceChildren(wordmark, spinner, hint)`.
     */
    fun showLoading(hintText: CharSequence) {
        hint.text = hintText
        card.stack(listOf(wordmark, spinner, hint), gapPx = (CARD_GAP_DP * density).roundToInt())
        // A page that finished loading hid us; coming back is legal (a
        // reload after a page failure), so every state re-shows the overlay
        // rather than assuming the caller will.
        visibility = VISIBLE
    }

    /**
     * The failure state: wordmark plus a titled block of actions — DSH's
     * `.failed`, which it fills with a `.failedTitle` and its failing plugin
     * ids, and which **replaces** the spinner rather than sitting under it.
     *
     * The actions are ours: DSH's splash is reporting a fault in itself,
     * whereas a harness that will not come up needs to hand the user a way
     * out ([DshRecovery]). The first is filled and the rest are outlined,
     * matching the priority order the service chose.
     */
    fun showFailed(title: CharSequence, actions: List<BootAction>) {
        val titleView = TextView(context).apply {
            text = title
            setTextColor(context.getColor(R.color.dsh_boot_label_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FAILED_TITLE_SP)
            // `.failedTitle{font-weight:600}` — see the wordmark's note.
            typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
            // `.failed{max-width:480px}` — the block is as wide as its text
            // needs, up to the width at which a line stops being readable.
            maxWidth = (FAILED_MAX_WIDTH_DP * density).roundToInt()
            gravity = Gravity.CENTER
        }

        val block = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        block.stack(
            listOf(titleView) + actions.map { bootButton(it) },
            gapPx = (FAILED_GAP_DP * density).roundToInt(),
        )

        card.stack(listOf(wordmark, block), gapPx = (CARD_GAP_DP * density).roundToInt())
        visibility = VISIBLE
    }

    /**
     * DSH's `._button`: a pill, not the app's near-square `primaryButton`.
     * Deliberately *not* reusing [me.phie.tawc.ui.primaryButton] — that
     * corner radius and its accent belong to the app's own screens, and this
     * overlay sits inside DSH's surface, not the app's chrome.
     *
     * The filled variant's foreground is [R.color.dsh_boot_bg], i.e. the
     * page background: the brand flips from near-black to near-white between
     * themes, so its contrast partner is exactly the colour that keeps the
     * label readable on both.
     */
    private fun bootButton(action: BootAction): MaterialButton =
        MaterialButton(context).apply {
            text = action.label
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minHeight = (BUTTON_HEIGHT_DP * density).roundToInt()
            minimumHeight = minHeight
            minWidth = (BUTTON_MIN_WIDTH_DP * density).roundToInt()
            cornerRadius = (BUTTON_CORNER_DP * density).roundToInt()
            val horizontal = (BUTTON_PAD_H_DP * density).roundToInt()
            setPadding(horizontal, 0, horizontal, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_LABEL_SP)

            val filled = action.prominent
            backgroundTintList = ColorStateList.valueOf(
                if (filled) context.getColor(R.color.dsh_boot_brand) else Color.TRANSPARENT,
            )
            setTextColor(
                context.getColor(
                    if (filled) R.color.dsh_boot_bg else R.color.dsh_boot_label_primary,
                ),
            )
            if (!filled) {
                strokeWidth = density.roundToInt()
                strokeColor = ColorStateList.valueOf(context.getColor(R.color.dsh_boot_border))
            }

            setOnClickListener { action.onClick() }
        }
}

/**
 * One way out of a failure, as [DshBootOverlay.showFailed] renders it.
 *
 * Not [DshRecovery] itself: the service decides *what* is worth offering,
 * the Activity decides what to call it and what it does. `prominent` is
 * derived from the caller's order — the service already sorts by priority.
 */
internal data class BootAction(
    val label: CharSequence,
    val onClick: () -> Unit,
    val prominent: Boolean = false,
)

/**
 * Replace this layout's children with [views], spaced by [gapPx].
 *
 * `LinearLayout` has no `gap`, and the alternative — a divider drawable or
 * per-child margins decided by each caller — buries the one number the
 * caller actually knows. DSH expresses both stacks as `gap`, so this keeps
 * the port a one-liner at each call site.
 */
private fun LinearLayout.stack(views: List<View>, gapPx: Int) {
    removeAllViews()
    views.forEachIndexed { index, view ->
        addView(
            view,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (index > 0) topMargin = gapPx
            },
        )
    }
}

/**
 * DSH's `.spinner`: a 2dp ring with a rotating arc over it.
 *
 * DSH builds the arc out of a `conic-gradient(...)` plus a `radial-gradient`
 * mask, which is the CSS way to say "a stroked arc" — `drawArc` says it
 * directly here, at the same radius. The arc rides the ring's own radius
 * rather than sitting inside it, because DSH positions it with `inset:-2px`
 * over the border box.
 */
private class DshSpinner(context: Context) : View(context) {

    private val strokePx = SPINNER_STROKE_DP * resources.displayMetrics.density
    private val diameterPx = SPINNER_DP * resources.displayMetrics.density

    /** The full ring: `border:2px solid var(--dsh-boot-border)`. */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = context.getColor(R.color.dsh_boot_border)
    }

    /** The arc: `conic-gradient(var(--dsh-boot-brand) …)`. */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = context.getColor(R.color.dsh_boot_brand)
    }

    private var angle = 0f

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    // A fixed 20dp square whatever the parent offers: the caller is a
    // centred column, and `.spinner` sets explicit width/height too.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = diameterPx.roundToInt()
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val half = strokePx / 2f
        val bounds = RectF(half, half, width - half, height - half)
        canvas.drawArc(bounds, 0f, 360f, false, ringPaint)
        canvas.drawArc(bounds, angle, ARC_DEG, false, arcPaint)
    }

    // Only while visible: `render` swaps this view out of the card on a
    // failure, and an infinite animator on a detached view keeps asking the
    // choreographer for frames for nothing.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }
}

// ---- DSH's numbers -------------------------------------------------------
// Each group is quoted from `dsh-web-frontend`'s CSS module; the class name
// is in the comment so the source of any future drift is one search away.

/** `._spinner_u7vgf_45` */
private const val SPINNER_DP = 20f
private const val SPINNER_STROKE_DP = 2f

/** `@keyframes _spin_u7vgf_45` — `animation:… .8s linear infinite`. */
private const val SPIN_PERIOD_MS = 800L

/** `var(--dsh-boot-arc, 72deg)` — DSH's value when it has no total to measure. */
private const val ARC_DEG = 72f

/** `._card_u7vgf_24{gap:16px}` */
private const val CARD_GAP_DP = 16f

/** `._failed_u7vgf_72{gap:8px;max-width:480px}` */
private const val FAILED_GAP_DP = 8f
private const val FAILED_MAX_WIDTH_DP = 480f

/** `._wordmark_u7vgf_31{font-size:16px;letter-spacing:.08em}` */
private const val WORDMARK_SP = 16f
private const val WORDMARK_LETTER_SPACING_EM = 0.08f

/** `._hint_u7vgf_39{font-size:12px}` */
private const val HINT_SP = 12f

/** `._failedTitle_u7vgf_79{font-size:14px;font-weight:600}` */
private const val FAILED_TITLE_SP = 14f

/** `._button_cfgyt_4{border-radius:18px;padding:0 14px;font-size:14px}` */
private const val BUTTON_CORNER_DP = 18f
private const val BUTTON_PAD_H_DP = 14f
private const val BUTTON_LABEL_SP = 14f

/**
 * Not from DSH. Its button's height *is* its 22px line-height — it sets no
 * vertical padding — which is fine for a pointer and far too small a touch
 * target on a phone. So these get a real height, and a width floor so a short
 * label like "Reload" cannot render as a dot.
 */
private const val BUTTON_HEIGHT_DP = 36f
private const val BUTTON_MIN_WIDTH_DP = 88f

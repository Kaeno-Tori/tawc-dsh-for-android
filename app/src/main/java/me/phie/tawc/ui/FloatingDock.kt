package me.phie.tawc.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.roundToInt
import me.phie.tawc.R
import me.phie.tawc.Settings

/** One entry in a [FloatingDock]. */
data class DockAction(
    val iconRes: Int,
    val label: CharSequence,
    val onClick: () -> Unit,
)

/**
 * The DSH screen's host-side tools behind one round button, in place of
 * a toolbar.
 *
 * A toolbar spends a full-width band on a title the page already shows
 * and a single overflow button — a bad trade on a phone, and a
 * pointless one on a tablet. This spends a corner instead, and the
 * corner is draggable because what is *under* it changes: DSH's own
 * page has a header and a composer of its own.
 *
 * The tools open in a popup rather than sitting on screen as their own
 * buttons: they are places to go *away from* DSH, and a strip of them
 * floating over the page is permanent furniture in a way one dot is
 * not.
 *
 * Add it to a `FrameLayout` with a plain `addView(dock)`: it brings its
 * own layout params ([Gravity.TOP] or [Gravity.END], i.e. the corner the
 * toolbar's overflow used to occupy) and positions itself against them.
 * It also clears the host's `clipChildren`, which is what lets its shadow
 * out of a dock that is exactly the size of its own dot — see
 * [onAttachedToWindow].
 */
class FloatingDock(
    context: Context,
    private val actions: List<DockAction>,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    /** True between the first movement past the slop and the finger's up. */
    private var dragging = false

    init {
        orientation = VERTICAL
        clipToPadding = false
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        // Any re-layout that moves us is a chance to be off the edge: a
        // rotation, or the parent growing under a saved position. Our
        // own bounds are what carries that signal — with gravity END, a
        // wider parent moves us without touching our margins.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // …but not while a finger is on it: the drag's own margins
            // are the truth then, and re-applying the saved ones would
            // both fight the finger and lose the drag (the up would
            // remember wherever the snap-back left it).
            if (!dragging) applyPosition()
        }

        val handle = buildHandle()
        handle.setOnClickListener { showTools() }
        val size = context.tawcButtonSizePx()
        addView(handle, LinearLayout.LayoutParams(size, size))
        attachDrag(handle)
    }

    /**
     * A ViewGroup clips its children — elevation shadows included — to its
     * own bounds, and this dock is exactly the size of its one child, so
     * the shadow was cut off square at the disc's edge. The clip that
     * matters is the *dock's* own node, and a node's clip is set from its
     * parent's `clipChildren`; clearing it here, rather than asking every
     * host to remember, keeps the dock self-contained.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.clipChildren = false
    }

    /**
     * The dot itself: `button-floating-fill` in a circle, with the glyph
     * fading back while idle ([IDLE_FADE]).
     *
     * A plain [ImageButton] with an oval background rather than Material's
     * button, because Material draws a *button's* elevation through a
     * software shadow layer: measured on the device, the fill came out
     * `#E5E5E6` where `#FFFFFF` was set, and the shadow stopped dead at the
     * button's bounds. A plain view's elevation is a real shadow, and its
     * fill is exactly the colour it is given.
     *
     * The ripple is masked to the oval so the press wash can't square off
     * the corners — the same reason [tawcCard] sets its own ripple.
     */
    private fun buildHandle(): ImageButton {
        val size = context.tawcButtonSizePx()
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_more_vert)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.dsh_tools)
            // The glyph is sized by the padding, as on the header's back
            // button: FIT_CENTER scales into whatever the padding leaves.
            val inset = (size - GLYPH_DP * density).roundToInt() / 2
            setPadding(inset, inset, inset, inset)

            val disc = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.tawc_floating_bg))
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(context.getColor(R.color.tawc_ripple)),
                disc,
                mask,
            )

            // ImageButton brings no per-state elevation of its own, but the
            // platform's default animator for a clickable view would still
            // own `elevation`; clearing it keeps this value in force.
            stateListAnimator = null
            elevation = ELEVATION_DP * density
            setFaded(this, faded = true)
        }
    }

    /**
     * Fade or restore [handle]'s glyph.
     *
     * Deliberately the *icon tint* and not `View.alpha`: an alpha below 1
     * puts the view through an offscreen layer sized to its own bounds,
     * which clipped away the elevation shadow — the only thing that makes
     * a white disc on a white page visible at all.
     */
    private fun setFaded(handle: ImageButton, faded: Boolean) {
        val base = context.getColor(R.color.tawc_label_primary)
        handle.imageTintList = ColorStateList.valueOf(
            if (faded) {
                ColorUtils.setAlphaComponent(base, (IDLE_FADE * 255).roundToInt())
            } else {
                base
            },
        )
    }

    /**
     * Re-read where the user left the button. The reset lives on
     * another screen, so a change never arrives as a callback — the
     * host calls this when it comes forward.
     */
    fun applySavedPosition() = applyPosition()

    /**
     * The tool list. A popup rather than buttons beside the handle: it
     * carries the labels, the framework keeps it on screen wherever the
     * handle has been dragged to, and "tap anywhere else to dismiss" is
     * what makes one dot enough.
     */
    private fun showTools() {
        val popup = PopupMenu(context, this)
        actions.forEachIndexed { index, action ->
            popup.menu.add(Menu.NONE, index, index, action.label).apply {
                setOnMenuItemClickListener {
                    action.onClick()
                    true
                }
            }
        }
        popup.show()
    }

    /**
     * Make [handle] move the dock inside its parent. The gesture stays a
     * tap until it passes the touch slop, so the same view is both the
     * button and the handle.
     */
    private fun attachDrag(handle: ImageButton) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startRawX = 0f
        var startRawY = 0f
        var startRight = 0
        var startTop = 0

        handle.setOnTouchListener { view, event ->
            val lp = layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startRight = lp.rightMargin
                    startTop = lp.topMargin
                    dragging = false
                    setFaded(handle, faded = false)
                    // false: the view keeps its own pressed state and its
                    // click, and still sends us the rest of the gesture.
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        // Or the button paints itself as still pressed
                        // for the whole drag, having been told nothing.
                        view.isPressed = false
                        (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!dragging) return@setOnTouchListener false
                    place(startRight - dx.roundToInt(), startTop + dy.roundToInt())
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = dragging
                    dragging = false
                    if (moved) rememberPosition()
                    setFaded(handle, faded = true)
                    // true after a drag: swallowing the up is what stops a
                    // drop from also opening the tools.
                    moved
                }

                else -> false
            }
        }
    }

    /** Move to the given margins, clamped so the dock stays inside the parent. */
    private fun place(rightMargin: Int, topMargin: Int) {
        val host = parent as? View ?: return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val (maxX, maxY) = travelRange(host)
        val right = rightMargin.coerceIn(0, maxX)
        val top = topMargin.coerceIn(0, maxY)
        if (lp.rightMargin == right && lp.topMargin == top) return
        lp.rightMargin = right
        lp.topMargin = top
        layoutParams = lp
    }

    /**
     * Restore the saved position, or — before the first drag — settle
     * [DEFAULT_MARGIN_DP] inside the top-right corner.
     */
    private fun applyPosition() {
        val host = parent as? View ?: return
        if (host.width <= 0 || host.height <= 0 || width <= 0 || height <= 0) return
        val (maxX, maxY) = travelRange(host)
        val x = Settings.dshDockX
        val y = Settings.dshDockY
        val default = (DEFAULT_MARGIN_DP * density).roundToInt()
        place(
            rightMargin = if (x.isNaN()) default else (x * maxX).roundToInt(),
            topMargin = if (y.isNaN()) default else (y * maxY).roundToInt(),
        )
    }

    /**
     * Save where the drag ended as a fraction of the range it can move,
     * not as pixels: the same spot on the next launch, and the same
     * relative spot after a rotation.
     */
    private fun rememberPosition() {
        val host = parent as? View ?: return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val (maxX, maxY) = travelRange(host)
        if (maxX > 0) Settings.dshDockX = lp.rightMargin.toFloat() / maxX
        if (maxY > 0) Settings.dshDockY = lp.topMargin.toFloat() / maxY
    }

    /**
     * How far the dock can travel before its edges leave [host]'s
     * content box. The padding is the system-bar inset the caller put on
     * the container, and margins are measured inside it.
     */
    private fun travelRange(host: View): Pair<Int, Int> {
        val maxX = host.width - host.paddingLeft - host.paddingRight - width
        val maxY = host.height - host.paddingTop - host.paddingBottom - height
        return maxX.coerceAtLeast(0) to maxY.coerceAtLeast(0)
    }
}

/** Inside the top-right corner, where the toolbar's overflow used to be. */
private const val DEFAULT_MARGIN_DP = 8f

private const val ELEVATION_DP = 6f

/** The dot's glyph, inside its `@dimen/tawc_icon_button_size` disc. */
private const val GLYPH_DP = 20f

/**
 * Resting opacity of the glyph. The button permanently covers part of DSH's
 * own page, so it fades back once the user stops touching it — and comes
 * back to full on the next touch, which is also the only moment its exact
 * position matters.
 */
private const val IDLE_FADE = 0.55f

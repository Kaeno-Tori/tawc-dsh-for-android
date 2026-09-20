package me.phie.tawc.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import me.phie.tawc.R

/**
 * Helpers for building screens that share the app's chrome — a header row
 * with the platform-standard back affordance plus a vertically stacked
 * content area — without duplicating boilerplate across activities.
 *
 * Layouts are still built imperatively in Kotlin; these helpers just
 * install the header and hand back the inner column so the caller can
 * keep using `addView(...)` like before.
 *
 * Everything here follows DSH's component shapes rather than Material's:
 * capsules for buttons, 12dp blocks bounded by a hairline instead of
 * elevated cards, a 44dp bare header row instead of a toolbar. The values
 * and their provenance are in `values/dimens.xml`, `values/colors.xml` and
 * `values/styles.xml`.
 */

data class Scaffold(
    val root: LinearLayout,
    val header: TawcHeader,
    val content: LinearLayout,
)

/**
 * The screen header: a bare row — optional back affordance, title, and a
 * hairline under it — matching the web client's page header.
 *
 * A `MaterialToolbar` was the wrong shape for this app on three counts: it
 * is 56dp tall where DSH's header is 44, it centres/uppercases its title by
 * Material's rules rather than the page's, and it draws its own elevation
 * band that DSH does not have anywhere.
 */
class TawcHeader(
    context: Context,
    title: CharSequence,
    withUp: Boolean,
    titleStyleRes: Int,
    onUpClicked: (() -> Unit)? = null,
) : LinearLayout(context) {

    private val titleView: TextView = TextView(context).apply {
        text = title
        tawcText(titleStyleRes)
        setTextColor(context.getColor(R.color.tawc_label_primary))
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Replaces the header's title in place (the log screen retitles itself). */
    var title: CharSequence
        get() = titleView.text
        set(value) {
            titleView.text = value
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 8dp horizontal, not 16: the tap target below is 36dp wide and its
        // glyph 20dp, so an 8dp inset puts the *glyph* on the same 16dp
        // column the page content starts at.
        setPadding(dp(8), 0, dp(8), 0)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.tawc_header_height)

        if (withUp) {
            val size = resources.getDimensionPixelSize(R.dimen.tawc_icon_button_size)
            addView(
                backButton(context).apply { setOnClickListener { onUpClicked?.invoke() } },
                LayoutParams(size, size),
            )
            titleView.setPadding(dp(4), 0, 0, 0)
        } else {
            titleView.setPadding(dp(8), 0, dp(8), 0)
        }
        addView(titleView, LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /**
     * Borderless icon button whose ripple comes from the theme's
     * `colorControlHighlight` — which the theme points at DSH's press wash,
     * so this matches the web client's `.ghost:hover` rather than Material's
     * grey.
     */
    private fun backButton(context: Context): View = ImageButton(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        imageTintList = ColorStateList.valueOf(context.getColor(R.color.tawc_label_primary))
        // ImageView's FIT_CENTER scales the glyph into whatever the padding
        // leaves, so the padding *is* the glyph size here.
        val inset = (dp(36) - resources.getDimensionPixelSize(R.dimen.tawc_icon_leading)) / 2
        setPadding(inset, inset, inset, inset)
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        setBackgroundResource(out.resourceId)
        // Same string the platform's own up affordance uses, so TalkBack reads
        // what users already know.
        contentDescription = context.getString(androidx.appcompat.R.string.abc_action_bar_up_description)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * Build a child screen (Install, Uninstall, Distro info, …): header with the
 * up arrow + a content column. Tapping the up arrow finishes, i.e. the screen
 * pops back to its parent.
 */
fun AppCompatActivity.buildChildScreen(title: CharSequence): Scaffold =
    buildScreenInternal(title, withUp = true, titleStyleRes = R.style.TextAppearance_Tawc_CardTitle)

/** Top-level screen (setup): header with the title only, no up arrow. */
fun AppCompatActivity.buildHomeScreen(title: CharSequence): Scaffold =
    buildScreenInternal(title, withUp = false, titleStyleRes = R.style.TextAppearance_Tawc_SectionTitle)

private fun AppCompatActivity.buildScreenInternal(
    title: CharSequence,
    withUp: Boolean,
    titleStyleRes: Int,
): Scaffold {
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
    root.applyTawcSystemBarPadding()

    val header = TawcHeader(this, title, withUp, titleStyleRes) { finish() }
    root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    root.addView(tawcDivider(), LinearLayout.LayoutParams(MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.tawc_hairline)))

    val pad = resources.getDimensionPixelSize(R.dimen.tawc_page_padding)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, pad)
    }
    root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

    return Scaffold(root, header, content)
}

/**
 * Keep [this] view's content out from under the status bar, the
 * navigation bar and any display cutout, by padding it with the current
 * insets and re-padding it whenever they change.
 *
 * Public because not every screen wants a header: the DSH surface
 * builds its own root so the WebView can own the whole window, and it
 * still needs to stay clear of the system bars.
 */
fun View.applyTawcSystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * The uniform button height in pixels — also the exact width/height
 * callers should give [tonalIconButton]s' layout params so the squares
 * can't be squeezed by their row (LinearLayout only guarantees
 * minHeight for exact-size children).
 */
fun Context.tawcButtonSizePx(): Int = resources.getDimensionPixelSize(R.dimen.tawc_icon_button_size)

/**
 * DSH's button geometry: 36dp tall, 14dp of horizontal padding, and an
 * 18dp corner radius — which on a 36dp control is a full capsule, from the
 * Figma component (`h36, pad 14/7, r18`). `minWidth` is zeroed because
 * Material's default 88dp minimum would make short labels ("Run", "Stop")
 * wider than DSH's own buttons.
 */
private fun MaterialButton.applyTawcButtonShape() {
    insetTop = 0
    insetBottom = 0
    val height = resources.getDimensionPixelSize(R.dimen.tawc_button_height)
    minHeight = height
    minimumHeight = height
    minWidth = 0
    minimumWidth = 0
    cornerRadius = resources.getDimensionPixelSize(R.dimen.tawc_radius_button)
    val padH = resources.getDimensionPixelSize(R.dimen.tawc_button_padding_h)
    setPadding(padH, 0, padH, 0)
}

/**
 * Filled button for primary actions (Install, Open): DSH's near-black cap
 * (near-white in dark mode), *not* an accent colour — the web client's only
 * filled-button token is `button-primary-fill`.
 */
fun AppCompatActivity.primaryButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.tawc_accent))
        setTextColor(getColor(R.color.tawc_on_accent))
        iconTint = ColorStateList.valueOf(getColor(R.color.tawc_on_accent))
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * Filled red button for destructive actions (Uninstall). Tinted
 * programmatically — no XML style indirection — so it's resilient
 * against future Material widget churn.
 */
fun AppCompatActivity.destructiveButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.tawc_danger))
        setTextColor(getColor(R.color.tawc_on_danger))
        iconTint = ColorStateList.valueOf(getColor(R.color.tawc_on_danger))
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * DSH's `_outline` button: `border: .5px solid border-l3; background:
 * transparent`. Used for the secondary variant and for icon buttons that
 * sit on a row rather than on a surface.
 */
private fun MaterialButton.applyTawcOutline() {
    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    strokeWidth = resources.getDimensionPixelSize(R.dimen.tawc_hairline)
    // Explicit setter for the same reason as in [tawcCard]: the `strokeColor`
    // property can resolve to the int overload and flatten the alpha.
    setStrokeColor(ColorStateList.valueOf(context.getColor(R.color.tawc_border_l3)))
}

/**
 * Outlined button for secondary actions (Manage, Cancel, Task manager,
 * "open source licenses"): DSH's `_outline` variant — transparent, a
 * hairline `border-l3`, and the same capsule shape as [primaryButton].
 *
 * It used to be a grey fill (`bg-module-platform`). That token is DSH's
 * *panel* fill, not one of its button fills — its only three button looks
 * are ghost, outline and filled-primary — and a grey disc beside a
 * near-black primary is Material's pairing, not DSH's.
 */
fun Context.tonalButton(label: CharSequence, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        setTextColor(getColor(R.color.tawc_label_primary))
        applyTawcOutline()
        applyTawcButtonShape()
        setOnClickListener { onClick() }
    }

/**
 * Square icon-only variant of [tonalButton] (e.g. the edit/remove
 * buttons on a manage-binds row). Fixed
 * `@dimen/tawc_icon_button_size`-square so every icon button matches the
 * text buttons' height regardless of icon size. MaterialButton centers a
 * TEXT_START icon when there's no text and iconPadding is 0.
 *
 * **Ghost by default** — transparent, no border — which is what DSH's
 * row-level icon actions look like (the sidebar's search / sliders /
 * new-folder icons). Pass a `backgroundColor` for the exceptions, and
 * **pass the matching `foregroundColor` with it**: the icon defaults to
 * `label-primary`, which is right on a ghost button but on a
 * [R.color.tawc_accent] fill it draws a near-black glyph on a near-black
 * disc — a solid dot.
 */
fun Context.tonalIconButton(
    iconRes: Int,
    description: CharSequence,
    backgroundColor: Int? = null,
    foregroundColor: Int = R.color.tawc_label_primary,
    iconSizeDp: Int? = null,
    onClick: () -> Unit,
): MaterialButton =
    MaterialButton(this).apply {
        icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)
        // DSH's icon container is a 16px glyph; 20dp is the same optical
        // weight against our slightly larger 36dp target.
        iconSize = ((iconSizeDp ?: 20) * resources.displayMetrics.density).toInt()
        iconPadding = 0
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        applyTawcButtonShape()
        val size = tawcButtonSizePx()
        minWidth = size
        minimumWidth = size
        setPadding(0, 0, 0, 0)
        contentDescription = description
        backgroundTintList = ColorStateList.valueOf(
            if (backgroundColor == null) Color.TRANSPARENT else getColor(backgroundColor),
        )
        iconTint = ColorStateList.valueOf(getColor(foregroundColor))
        setOnClickListener { onClick() }
    }

/**
 * Block / panel surface used where something really is a block rather
 * than a list row — today the operation log panel and the task manager's
 * process-detail dialog.
 *
 * 12dp corners (`tawc_radius_panel`) plus a 1px hairline
 * (`tawc_divider`) — DSH's shape for a block. It is deliberately *not*
 * Material's elevated card: DSH has no elevation anywhere, and its blocks
 * are separated by their outline rather than by depth or by a fill
 * difference (in light mode the block fill and the page are the same
 * white; the hairline is the whole boundary).
 *
 * Pass `clickable = true` for blocks that navigate or act. That wires the
 * press state too: MaterialCardView masks its ripple with the card's own
 * shape, so the highlight can't bleed past the corners — which is why
 * this is set here rather than by callers setting `isClickable`
 * themselves.
 */
fun Context.tawcCard(clickable: Boolean = false): MaterialCardView =
    MaterialCardView(this).apply {
        radius = resources.getDimension(R.dimen.tawc_radius_panel)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.tawc_hairline)
        // Explicit setter, not the `strokeColor` property: the property
        // resolves to the int overload (`getStrokeColor()` returns an int),
        // which would flatten the alpha out of the hairline colour.
        setStrokeColor(ColorStateList.valueOf(getColor(R.color.tawc_divider)))
        cardElevation = 0f
        setCardBackgroundColor(getColor(R.color.tawc_card_bg))
        if (clickable) {
            isClickable = true
            isFocusable = true
            setRippleColor(ColorStateList.valueOf(getColor(R.color.tawc_ripple)))
        }
    }

/**
 * DSH's settings group — `AppearanceRow.module.css`'s `.group`:
 * `border-bottom: .5px solid border-l2; padding: 16px 0`, with the title at
 * 14/400 and the explanatory line at 12/400 in `label-tertiary`.
 *
 * Callers add their rows to the returned column and finish it with
 * [tawcDivider]. This is what a settings screen looks like in DSH: no cards
 * and no fills — the separators are the whole structure. Reach for
 * [tawcCard] only where something really is a block (a code block, a panel
 * that floats over the page).
 */
fun Context.tawcSettingsGroup(title: CharSequence, description: CharSequence? = null): LinearLayout {
    val space = resources.getDimensionPixelSize(R.dimen.tawc_space_l)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, space, 0, space)
    }
    column.addView(
        TextView(this).apply {
            text = title
            tawcText(R.style.TextAppearance_Tawc_Body)
            setTextColor(getColor(R.color.tawc_label_primary))
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    if (description != null) {
        column.addView(
            TextView(this).apply {
                text = description
                tawcText(R.style.TextAppearance_Tawc_Caption)
                setTextColor(tawcTertiaryColor())
                val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_xs)
                setPadding(0, gap, 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }
    return column
}

/**
 * DSH's disclosure row, the shape of every navigable row in the web client
 * (`DisclosureRow.module.css`, the session list, the file tree): a title at
 * 14/400 with the press wash from `interactive-bg-hover` and — when the row
 * is clickable — a hairline under it, rather than a card of its own.
 *
 * @param detail the second line, at 12/400 `label-tertiary` (the `.desc`
 *   role), or null for a single-line row.
 */
fun Context.tawcListRow(
    title: CharSequence,
    detail: CharSequence? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val space = resources.getDimensionPixelSize(R.dimen.tawc_space_l)
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, space, 0, space)
    }
    column.addView(
        TextView(this).apply {
            text = title
            tawcText(R.style.TextAppearance_Tawc_Body)
            setTextColor(getColor(R.color.tawc_label_primary))
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    if (detail != null) {
        column.addView(
            TextView(this).apply {
                text = detail
                tawcText(R.style.TextAppearance_Tawc_Caption)
                setTextColor(tawcTertiaryColor())
                val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_xs)
                setPadding(0, gap, 0, 0)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }
    if (onClick != null) {
        column.isClickable = true
        column.isFocusable = true
        // The theme's colorControlHighlight *is* DSH's press wash, so the
        // platform's own selectable background is already the right colour.
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        column.setBackgroundResource(out.resourceId)
        column.setOnClickListener { onClick() }
    }
    return column
}

/**
 * DSH's input (`Input.module.css`): 32dp tall, 8dp corners, `bg-layer-1`
 * fill, a hairline `border-l4` outline, 14sp text and 8dp horizontal
 * padding.
 *
 * Built on top of a plain `EditText` with its own background, because the
 * theme's `editTextStyle` draws a Material outlined box (4dp corners, a
 * 1dp focus-grow border) that reads as a different product next to DSH's
 * flat hairline field.
 */
fun Context.tawcInput(): EditText = EditText(this).apply {
    tawcText(R.style.TextAppearance_Tawc_Body)
    setTextColor(getColor(R.color.tawc_label_primary))
    setHintTextColor(getColor(R.color.tawc_label_caption))
    isSingleLine = true
    gravity = Gravity.CENTER_VERTICAL or Gravity.START
    minimumHeight = resources.getDimensionPixelSize(R.dimen.tawc_input_height)

    val hairline = resources.getDimensionPixelSize(R.dimen.tawc_hairline)
    val outline = getColor(R.color.tawc_outline)
    val brand = getColor(R.color.tawc_accent)
    val box = GradientDrawable().apply {
        cornerRadius = resources.getDimension(R.dimen.tawc_radius_input)
        setColor(getColor(R.color.tawc_card_bg))
        setStroke(hairline, outline)
    }
    background = box
    // `.wrap:focus-within { border-color: brand-primary }` — the focus ring
    // *is* the border going near-black, not a second outline drawn outside it.
    setOnFocusChangeListener { _, focused -> box.setStroke(hairline, if (focused) brand else outline) }

    val padH = resources.getDimensionPixelSize(R.dimen.tawc_input_padding_h)
    setPadding(padH, 0, padH, 0)
}

/**
 * A DSH hairline: one physical pixel of `border-l2`, full width, with its
 * layout params already set so `addView(context.tawcDivider())` is enough.
 *
 * DSH separates rows inside a block with these rather than with gaps or
 * per-row cards; use one between rows that belong together.
 */
fun Context.tawcDivider(): View = View(this).apply {
    setBackgroundColor(getColor(R.color.tawc_divider))
    layoutParams = LinearLayout.LayoutParams(
        MATCH_PARENT,
        resources.getDimensionPixelSize(R.dimen.tawc_hairline),
    )
}

/**
 * Apply one of the `TextAppearance.Tawc.*` styles. Sizes used to be set
 * inline — eleven of them between 11sp and 28sp across 70 call sites.
 */
fun TextView.tawcText(styleRes: Int): TextView = apply { setTextAppearance(styleRes) }

/**
 * The colour for weakened content: explanatory lines under a heading,
 * secondary row values, timestamps, hints — DSH's `label-secondary`.
 *
 * The UI used to mix two treatments for this — `alpha = 0.6f..0.75f` on
 * the primary text colour, and a `colorOnSurfaceVariant` lookup — which gave
 * the same visual weight two different values depending on the screen. Both
 * collapse to this, and the theme points `colorOnSurfaceVariant` at the same
 * token so Material's own widgets agree.
 */
fun Context.tawcSecondaryColor(): Int = getColor(R.color.tawc_label_secondary)

/**
 * [tawcSecondaryColor] for callers that hold a view rather than a
 * context — which is most of them, since the colour is usually set on
 * the view being built.
 */
fun View.tawcSecondaryColor(): Int = context.tawcSecondaryColor()

/**
 * The quietest readable tier — DSH's `label-tertiary`. Its own use of this
 * is the explanatory line under a row title (`.desc` in the settings rows),
 * which DSH sets at 12px rather than at the row's own size.
 */
fun Context.tawcTertiaryColor(): Int = getColor(R.color.tawc_label_tertiary)

/** Convenience: vertical [LinearLayout.LayoutParams] with a bottom margin. */
fun verticalLp(width: Int, height: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height).also { it.bottomMargin = bottomMargin }

package me.phie.tawc.ops

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.phie.tawc.R
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.tawcSecondaryColor
import me.phie.tawc.ui.tawcText
import me.phie.tawc.ui.tonalButton

/**
 * Reusable "operation in progress" UI: emphasised status line + info-tinted
 * progress bar + scrolling log + subdued tonal Cancel button.
 *
 * Owners attach [view] into their layout, then call [bind] with an
 * [Operation] when one is available and [unbind] when leaving the
 * screen. The panel collects from [Operation.progress] / [Operation.log]
 * and updates the views; cancel taps invoke [onCancelClicked] (the
 * owner usually wraps with a confirm dialog if [Operation.cancelConfirmation]
 * says to).
 *
 * Lifecycle note: when an Operation terminates and is unregistered, the
 * owner calls [unbind]. The TextView/status views are *not* cleared;
 * the panel just stops collecting. This matches the design choice that
 * a still-open viewer keeps its last-rendered state frozen rather than
 * blanking out.
 */
class OperationLogPanel(private val activity: Activity) {

    val view: LinearLayout
    private val statusText: TextView
    private val progressBar: ProgressBar
    private val stepsColumn: LinearLayout
    private val logText: TextView
    private val logScroll: ScrollView
    private val cancelButton: MaterialButton

    private var collectScope: CoroutineScope? = null

    /** Step names the rendered checklist was built from, to avoid rebuilding
     *  six TextViews on every progress emit. */
    private var renderedSteps: List<String> = emptyList()
    private var stepRows: List<Pair<TextView, TextView>> = emptyList()

    /** The currently bound op, or `null`. Owners may read this from [onCancelClicked]. */
    var boundOperation: Operation? = null
        private set

    /**
     * Tap handler for the Cancel button.
     *
     * **Inert until an owner sets it.** The panel deliberately has no
     * default: whether a cancel needs a confirm dialog is a property of
     * the operation ([Operation.cancelConfirmation]), and for an install
     * the answer is yes — an unconfirmed tap wipes the rootfs. Owners
     * route it through `confirmAndCancel`; [LogScreenActivity] and
     * [me.phie.tawc.MainActivity] both do.
     */
    var onCancelClicked: (() -> Unit)? = null

    init {
        val pad = activity.resources.getDimensionPixelSize(R.dimen.tawc_space_l)
        val info = activity.getColor(R.color.tawc_info)

        view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        statusText = TextView(activity).apply {
            text = ""
            tawcText(R.style.TextAppearance_Tawc_BodyStrong)
        }
        view.addView(statusText, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(info)
            progressTintList = ColorStateList.valueOf(info)
        }
        view.addView(progressBar, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Checklist, between the bar and the log. Empty (and so zero-height)
        // for operations that don't declare steps, which keeps this panel
        // usable for the ops that only have a status line.
        stepsColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        view.addView(stepsColumn, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        logScroll = ScrollView(activity)
        logText = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            tawcText(R.style.TextAppearance_Tawc_CaptionSmall)
            // setTextIsSelectable installs ArrowKeyMovementMethod, which is
            // what makes long-press select + copy work. Don't override it
            // with ScrollingMovementMethod — the wrapping ScrollView already
            // handles scrolling, and that override silently kills selection.
            setTextIsSelectable(true)
            val innerPad = pad / 2
            setPadding(innerPad, innerPad, innerPad, innerPad)
        }
        logScroll.addView(logText)
        // Wrap the log in a card so it reads as its own panel against
        // the screen background — the same fill/no-stroke block as the
        // task manager's process-detail dialog.
        val logCard = activity.tawcCard().apply { addView(logScroll) }
        view.addView(logCard, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Tonal (shaded fill, no border) so it stays a quieter sibling
        // to the primary path while still reading as a button. Hidden
        // until a stage event tells us a job is actually running.
        cancelButton = activity.tonalButton(activity.getString(R.string.action_cancel)) { onCancelClicked?.invoke() }
        cancelButton.visibility = View.GONE
        view.addView(cancelButton, lp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = 0).apply {
            topMargin = pad / 2
        })
    }

    /**
     * Bind the panel to [op]. Cancels any previous binding's collectors
     * but **does not** clear the local TextView — by design, since the
     * intended UX is "show the previous frozen state until the new op
     * fills the panel." The service's per-op `_log.resetReplayCache()`
     * keeps the new op's replay buffer from leaking the previous run.
     */
    fun bind(op: Operation) {
        unbind()
        boundOperation = op
        val cs = CoroutineScope(Dispatchers.Main)
        collectScope = cs

        cs.launch {
            op.progress.collectLatest { p -> applyProgress(p) }
        }

        cs.launch {
            op.log.collect { line -> appendLog(line) }
        }
    }

    /**
     * Stop collecting. Leaves the views as-is so the owner can show the
     * frozen final state. Reads the bound op's latest progress value
     * synchronously *before* cancelling the scope: a terminal-then-
     * unregister race means the registry-driven unbind can fire before
     * the panel's collector has dispatched the final emit, in which case
     * the views would otherwise stay stuck on the penultimate stage —
     * that's why uninstall ("DELETING → DONE", microseconds apart) used
     * to never paint the green "Deleted" status. We pump the StateFlow
     * value ourselves to close that window.
     */
    fun unbind() {
        boundOperation?.progress?.value?.let { applyProgress(it) }
        collectScope?.cancel()
        collectScope = null
        boundOperation = null
    }

    private fun applyProgress(p: OperationProgress) {
        val danger = activity.getColor(R.color.tawc_danger)
        val success = activity.getColor(R.color.tawc_success)
        val defaultTextColor = MaterialColors.getColor(
            statusText, com.google.android.material.R.attr.colorOnSurface,
        )
        statusText.text = p.message
        statusText.setTextColor(when (p.stage) {
            OperationStage.FAILED -> danger
            OperationStage.DONE -> success
            else -> defaultTextColor
        })
        if (p.percent != null) {
            progressBar.isIndeterminate = false
            progressBar.progress = p.percent
        } else {
            progressBar.isIndeterminate = true
        }
        val terminal = p.stage.isTerminal || p.stage == OperationStage.IDLE
        progressBar.visibility = if (terminal) View.GONE else View.VISIBLE
        cancelButton.visibility = if (terminal) View.GONE else View.VISIBLE
        applySteps(p)
    }

    /**
     * Paint the step checklist.
     *
     * The rows are built once per distinct step list ([renderedSteps]) and
     * then only re-marked, because progress emits arrive at high frequency
     * during a download and re-inflating six TextViews per emit would be
     * the most expensive thing on the screen.
     *
     * The two states that matter and aren't obvious: [OperationProgress.currentStep]
     * is `steps.size` on success (nothing is "current", everything is
     * done), and on failure the position stays at the step that threw — so
     * the danger mark lands on the step that actually failed rather than
     * on nothing.
     */
    private fun applySteps(p: OperationProgress) {
        if (p.steps != renderedSteps) {
            stepsColumn.removeAllViews()
            stepRows = p.steps.map { name ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                val mark = TextView(activity).apply {
                    typeface = Typeface.MONOSPACE
                    // Fixed width: "✓", "▶" and "·" don't render at the
                    // same advance width, so without this the names
                    // below would not line up.
                    width = markWidthPx
                    includeFontPadding = false
                }
                val label = TextView(activity).apply {
                    text = name
                    tawcText(R.style.TextAppearance_Tawc_BodySmall)
                    includeFontPadding = false
                }
                row.addView(mark)
                row.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                stepsColumn.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                mark to label
            }
            renderedSteps = p.steps
        }
        if (p.steps.isEmpty()) return

        val success = activity.getColor(R.color.tawc_success)
        val info = activity.getColor(R.color.tawc_info)
        val danger = activity.getColor(R.color.tawc_danger)
        val onSurface = MaterialColors.getColor(
            stepsColumn, com.google.android.material.R.attr.colorOnSurface,
        )
        val muted = stepsColumn.tawcSecondaryColor()

        stepRows.forEachIndexed { i, (mark, label) ->
            val done = i < p.currentStep
            val current = i == p.currentStep
            val failed = current && p.stage == OperationStage.FAILED
            mark.text = when {
                failed -> "✗"
                done -> "✓"
                current -> "▶"
                else -> "·"
            }
            mark.setTextColor(
                when {
                    failed -> danger
                    current -> info
                    done -> success
                    else -> muted
                },
            )
            label.setTextColor(if (done || current) onSurface else muted)
            // DSH has no bold; the "this one is running" emphasis is the
            // 500 weight of the same size, not a heavier step.
            label.tawcText(
                if (current) R.style.TextAppearance_Tawc_BodyStrong else R.style.TextAppearance_Tawc_Body,
            )
        }
    }

    private val markWidthPx: Int =
        activity.resources.getDimensionPixelSize(R.dimen.tawc_space_l)

    /**
     * Clear the rendered log + status. Used by viewers that swap the
     * panel from one op to another (e.g. [LogScreenActivity.onNewIntent])
     * so the user doesn't see the previous op's frozen content under
     * the new op's toolbar title.
     */
    fun reset() {
        statusText.text = ""
        logText.text = ""
        stepsColumn.removeAllViews()
        renderedSteps = emptyList()
        stepRows = emptyList()
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        cancelButton.visibility = View.GONE
    }

    fun appendLog(line: String) {
        // Cap on-screen log to keep memory bounded; full history is in logcat.
        val cur = logText.text
        if (cur.length > 80_000) {
            logText.text = cur.subSequence(40_000, cur.length).toString()
        }
        val withNewline = "$line\n"
        val span = SpannableString(withNewline)
        span.setSpan(
            LeadingMarginSpan.Standard(0, hangingIndentPx),
            0, withNewline.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        logText.append(span)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private val hangingIndentPx: Int =
        activity.resources.getDimensionPixelSize(R.dimen.tawc_space_l)

    private fun lp(w: Int, h: Int, bottomMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h).also { it.bottomMargin = bottomMargin }
}

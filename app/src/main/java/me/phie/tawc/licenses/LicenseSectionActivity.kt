package me.phie.tawc.licenses

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcSecondaryColor
import me.phie.tawc.ui.tawcText

/**
 * One license family from [LicensesActivity]: the components it covers,
 * followed by each distinct license text under it.
 *
 * A single family can still run to a couple hundred KB (Apache-2.0
 * covers 200-odd components across a handful of upstream text
 * variants), so rows go through a RecyclerView rather than one giant
 * TextView, which would stall the screen open.
 */
class LicenseSectionActivity : AppCompatActivity() {

    /** One rendered row: a heading, a component list, or a text block. */
    private sealed interface Row {
        data class Heading(val text: String) : Row
        data class Components(val text: String) : Row
        data class Prose(val text: String) : Row
        data class Pre(val text: String) : Row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val doc = LicenseDoc.load(this)
        val index = intent.getIntExtra(EXTRA_SECTION, -1)
        val section = doc.sections.getOrNull(index)
        if (section == null) {
            // Only reachable via a stale intent (e.g. a restored task
            // after the asset changed shape). Nothing useful to show.
            finish()
            return
        }

        val scaffold = buildChildScreen(section.title)
        scaffold.content.addView(
            RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@LicenseSectionActivity)
                adapter = RowAdapter(buildRows(section))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(scaffold.root)
    }

    private fun buildRows(section: LicenseDoc.Section): List<Row> {
        val rows = mutableListOf<Row>()
        val multiple = section.entries.size > 1
        section.entries.forEachIndexed { i, entry ->
            if (entry.components.isNotEmpty()) {
                rows += Row.Heading(
                    if (multiple) {
                        getString(
                            me.phie.tawc.R.string.licenses_variant_heading,
                            i + 1,
                            section.entries.size,
                            entry.components.size,
                        )
                    } else {
                        getString(me.phie.tawc.R.string.licenses_covers_heading, entry.components.size)
                    },
                )
                rows += Row.Components(entry.components.joinToString("\n") { "• $it" })
            }
            entry.blocks.forEach { block ->
                if (block.text.isNotBlank()) {
                    rows += if (block.pre) Row.Pre(block.text) else Row.Prose(block.text)
                }
            }
        }
        return rows
    }

    private inner class RowAdapter(private val rows: List<Row>) :
        RecyclerView.Adapter<RowAdapter.Holder>() {

        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Heading -> TYPE_HEADING
            is Row.Components -> TYPE_COMPONENTS
            is Row.Prose -> TYPE_PROSE
            is Row.Pre -> TYPE_PRE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_s)
            val view = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                when (viewType) {
                    TYPE_HEADING -> {
                        tawcText(R.style.TextAppearance_Tawc_BodyStrong)
                        setPadding(0, resources.getDimensionPixelSize(R.dimen.tawc_space_l), 0, gap)
                    }
                    TYPE_COMPONENTS -> {
                        tawcText(R.style.TextAppearance_Tawc_Caption)
                        setTextColor(context.tawcSecondaryColor())
                        setPadding(0, 0, 0, gap * 2)
                    }
                    TYPE_PRE -> {
                        tawcText(R.style.TextAppearance_Tawc_Caption)
                        setTypeface(Typeface.MONOSPACE)
                        setPadding(0, 0, 0, gap)
                    }
                    else -> {
                        tawcText(R.style.TextAppearance_Tawc_BodySmall)
                        setPadding(0, 0, 0, gap)
                    }
                }
            }
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.text.text = when (val row = rows[position]) {
                is Row.Heading -> row.text
                is Row.Components -> row.text
                is Row.Prose -> row.text
                is Row.Pre -> row.text
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    companion object {
        const val EXTRA_SECTION = "section"

        private const val TYPE_HEADING = 0
        private const val TYPE_COMPONENTS = 1
        private const val TYPE_PROSE = 2
        private const val TYPE_PRE = 3
    }
}

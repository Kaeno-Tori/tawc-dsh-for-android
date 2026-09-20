package me.phie.tawc.licenses

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import me.phie.tawc.R
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcDivider
import me.phie.tawc.ui.tawcListRow
import me.phie.tawc.ui.tawcTertiaryColor
import me.phie.tawc.ui.tawcText

/**
 * Index of the app's licensing: a short notice, then one tappable row
 * per license family, each opening a [LicenseSectionActivity].
 *
 * The full attribution text runs to hundreds of KB. Presented as a
 * single page it is unnavigable, so the generator groups components by
 * license family and this screen shows only the ~14 group headings.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_licenses))
        val doc = LicenseDoc.load(this)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildNotice(doc))
        column.addView(tawcDivider())

        doc.sections.forEachIndexed { index, section ->
            column.addView(buildSectionRow(section, index))
            column.addView(tawcDivider())
        }

        scaffold.content.addView(
            ScrollView(this).apply { addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        setContentView(scaffold.root)
    }

    /**
     * The document's own preamble, as page text rather than in a block: DSH
     * puts prose straight on the page and reserves blocks for things that
     * really are objects (a code block, a panel).
     */
    private fun buildNotice(doc: LicenseDoc.Doc): android.view.View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val space = resources.getDimensionPixelSize(R.dimen.tawc_space_l)
            setPadding(0, space, 0, space)
        }
        for (paragraph in doc.intro) {
            column.addView(
                TextView(this).apply {
                    text = paragraph
                    tawcText(R.style.TextAppearance_Tawc_Body)
                    val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_s)
                    setPadding(0, 0, 0, gap)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        column.addView(
            TextView(this).apply {
                text = doc.sourceUrl
                tawcText(R.style.TextAppearance_Tawc_BodySmall)
                setTextColor(context.tawcTertiaryColor())
                // Selectable rather than a link: no browser intent from a
                // screen that exists to be readable offline.
                setTextIsSelectable(true)
                setTypeface(Typeface.MONOSPACE)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        return column
    }

    private fun buildSectionRow(section: LicenseDoc.Section, index: Int): android.view.View =
        tawcListRow(section.title, section.subtitle) {
            startActivity(
                Intent(this@LicensesActivity, LicenseSectionActivity::class.java)
                    .putExtra(LicenseSectionActivity.EXTRA_SECTION, index),
            )
        }
}

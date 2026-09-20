package me.phie.tawc.install

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import me.phie.tawc.R
import me.phie.tawc.ui.tawcSecondaryColor
import me.phie.tawc.ui.tawcText

/**
 * The shared checkbox-plus-one-line-description row, used by the ando
 * toggle (notes/ando.md) on both the install form ([InstallActivity])
 * and distro settings ([DistroInfoActivity]), and by the auto-bind
 * toggle on the setup screen's grant card ([me.phie.tawc.MainActivity]).
 * Callers differ only in the strings and in what [onChange] does. The
 * checkbox is passed back so a commit-path caller can revert it on
 * failure.
 */
internal fun buildToggleRow(
    context: Context,
    labelRes: Int,
    descriptionRes: Int,
    checked: Boolean,
    onChange: (checkbox: CheckBox, checked: Boolean) -> Unit,
): LinearLayout {
    val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    container.addView(
        CheckBox(context).apply {
            text = context.getString(labelRes)
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(this, c) }
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    container.addView(
        TextView(context).apply {
            text = context.getString(descriptionRes)
            tawcText(R.style.TextAppearance_Tawc_Caption)
            setTextColor(context.tawcSecondaryColor())
        },
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
    )
    return container
}

package me.phie.tawc

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.phie.tawc.install.EnabledGraphicsBackends
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.VulkanProvisionOp
import me.phie.tawc.licenses.LicensesActivity
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.tawcDivider
import me.phie.tawc.ui.tawcSettingsGroup
import me.phie.tawc.ui.tawcTertiaryColor
import me.phie.tawc.ui.tawcText
import me.phie.tawc.ui.tonalButton

/**
 * App settings screen. Reachable only from the DSH dock's "Settings"
 * action — [MainActivity] forwards straight to DSH and has no Settings
 * entry of its own. Each section is its own card with a bold title
 * at the top followed by the section's controls. Add a new section by
 * building a card via [buildSectionCard] and adding it to
 * `scaffold.content`.
 *
 * Three cards, on purpose. The rendering/compatibility/scaling controls
 * that used to live here are still supported and still persisted in
 * [Settings] — the debug broker's `set-*` actions are how they are
 * changed now — they just don't get a row each any more.
 *
 * Picks are saved immediately on selection — no Save button to
 * accidentally forget. The Vulkan pick additionally runs the provisioning
 * scripts in every container (see [VulkanProvisionOp]), which is why that
 * one card confirms before it commits. The appearance pick recreates this
 * screen instead, which is AppCompat applying the new theme, not us.
 */
class SettingsActivity : AppCompatActivity() {

    private val cardPad get() = resources.getDimensionPixelSize(R.dimen.tawc_card_padding)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scaffold = buildChildScreen(getString(R.string.title_settings))

        scaffold.content.addView(
            buildSection(
                getString(R.string.settings_appearance),
                null,
                buildAppearanceSettings(),
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        scaffold.content.addView(
            buildSection(
                getString(R.string.settings_dsh_display),
                getString(R.string.settings_dsh_zoom_detail),
                buildDshDisplaySettings(),
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        scaffold.content.addView(
            buildSection(
                getString(R.string.settings_vulkan),
                getString(R.string.settings_vulkan_detail),
                buildVulkanSettings(),
            ),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        scaffold.content.addView(
            buildSection(getString(R.string.settings_about), null, buildAboutSettings()),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        setContentView(scaffold.root)
    }

    /**
     * A DSH settings group: title, optional explanatory line, the rows, and a
     * hairline under the group ([tawcSettingsGroup] + [tawcDivider]).
     *
     * There is no card here any more, and nothing is lost: DSH separates its
     * settings with the hairline and lets the page be the surface, which is
     * why its screens read as one sheet rather than as a stack of tiles.
     */
    private fun buildSection(title: String, description: String?, body: android.view.View): android.view.View {
        val group = tawcSettingsGroup(title, description)
        group.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        group.addView(tawcDivider())
        return group
    }

    /**
     * Light / dark / follow the system. Three radios and no detail
     * paragraph, unlike the Vulkan card: there is nothing to explain, the
     * labels are the whole setting.
     *
     * The pick goes through [AppCompatDelegate.setDefaultNightMode], which
     * also recreates every running Activity, this one included. That is the
     * platform's own mechanism for re-resolving `-night` resources rather
     * than a wart of ours, and the pref is written first, so what comes back
     * reads the new value.
     *
     * Scope note: this is the app's theme, not DSH's. The harness UI is a web
     * client in a WebView with its own appearance setting — see [ThemeMode].
     */
    private fun buildAppearanceSettings(): android.view.View {
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (mode in ThemeMode.entries) {
            group.addView(
                RadioButton(this).apply {
                    // A generated id, *not* `ordinal + 1`. View state is saved
                    // and restored under the id as its key, and this Activity
                    // holds a second RadioGroup (the Vulkan card) whose radios
                    // would then carry the very same 1/2/3 — so the checked
                    // "Turnip" (id 3) gets restored onto "Dark" (id 3) across
                    // any recreate, and the listener reads that as the user
                    // picking dark. That is exactly how choosing "follow
                    // system" used to flash light and snap back to dark.
                    id = View.generateViewId()
                    text = getString(themeLabel(mode))
                    tawcText(R.style.TextAppearance_Tawc_Body)
                    isChecked = mode == Settings.themeMode
                    setPadding(0, cardPad / 2, 0, cardPad / 2)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        // The index in the group, not the id: the ids are generated, and the
        // children are added in [ThemeMode.entries] order.
        group.setOnCheckedChangeListener { g, checkedId ->
            val child = g.findViewById<View>(checkedId)
                ?: return@setOnCheckedChangeListener
            val picked = ThemeMode.entries.getOrNull(g.indexOfChild(child))
                ?: return@setOnCheckedChangeListener
            if (picked == Settings.themeMode) return@setOnCheckedChangeListener
            Settings.themeMode = picked
            AppCompatDelegate.setDefaultNightMode(picked.delegateMode)
        }
        return group
    }

    private fun themeLabel(mode: ThemeMode): Int = when (mode) {
        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_follow_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

    /**
     * The one control here that reaches inside the WebView: a stepper for
     * [Settings.dshZoomPercent], the page scale of DSH's own interface.
     *
     * A stepper rather than a slider for two reasons — DSH's own numeric
     * setting is a stepper (its font size is ± buttons with the value between
     * them), and every step is one tap that can be thought about, where a drag
     * would keep landing on 87%.
     *
     * The change is not applied from here: [me.phie.tawc.dsh.DshActivity] is
     * stopped behind this screen, and it re-applies the scale in `onResume`.
     * That is what keeps the control live — no reload, so DSH does not drop
     * back to its boot splash and lose the scroll position for a scale tweak.
     */
    private fun buildDshDisplaySettings(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_s)
        val value = TextView(this).apply {
            text = getString(R.string.settings_dsh_zoom_value, Settings.dshZoomPercent)
            tawcText(R.style.TextAppearance_Tawc_BodyStrong)
            gravity = android.view.Gravity.CENTER
        }
        fun step(delta: Int) {
            Settings.dshZoomPercent = Settings.dshZoomPercent + delta
            value.text = getString(R.string.settings_dsh_zoom_value, Settings.dshZoomPercent)
        }
        // The glyphs are the same pair DSH puts around its own font size, and
        // the words they stand for go in the content descriptions rather than
        // on the buttons: two labelled capsules would outweigh the number they
        // sit around.
        val smaller = tonalButton("−") { step(-Settings.ZOOM_STEP_PERCENT) }.apply {
            contentDescription = getString(R.string.settings_dsh_zoom_decrease)
        }
        val larger = tonalButton("+") { step(Settings.ZOOM_STEP_PERCENT) }.apply {
            contentDescription = getString(R.string.settings_dsh_zoom_increase)
        }

        row.addView(smaller)
        row.addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = gap
            marginEnd = gap
        })
        row.addView(larger)
        return row
    }

    /**
     * The app's only graphics-related pick: which Vulkan accelerator to use,
     * both for the env the app hands each rootfs spawn ([RootfsEnv] derives
     * its backend from this) and for the container's own standard paths
     * ([VulkanProvisionOp] runs the bundled scripts there, so programs the
     * user starts *inside* the container get the same driver).
     *
     * There used to be a separate "Graphics driver" list here. It could not
     * coexist with this one: the backend it chose put its own
     * `libvulkan.so.1` on `LD_LIBRARY_PATH`, which out-ranks the rootfs's
     * `/usr/lib/libvulkan.so.1`, so the two picks could disagree and this
     * one would silently lose. One knob that is true beats two that fight.
     *
     * Options are filtered by what this APK ships — `system` needs the
     * libhybris asset, `turnip` the Turnip one — and the pick is only saved
     * once the user confirms, because accepting it changes files in every
     * container.
     */
    private fun buildVulkanSettings(): android.view.View {
        val options = vulkanOptions()
        // What is *in effect*, not what was saved: until the user answers,
        // that is the backend this build defaults to, and it is what the
        // container has been provisioned for. Showing "Off" there would
        // claim the rootfs is untouched when it is wired.
        var shown = VulkanDriver.effective().takeIf { it in options } ?: VulkanDriver.OFF

        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (option in options) {
            group.addView(
                RadioButton(this).apply {
                    // Generated, for the reason spelled out in
                    // [buildAppearanceSettings]: the two RadioGroups in this
                    // Activity would otherwise both number their radios from
                    // one, and a recreate would restore each other's picks.
                    id = View.generateViewId()
                    text = getString(vulkanLabel(option))
                    tawcText(R.style.TextAppearance_Tawc_Body)
                    isChecked = option == shown
                    setPadding(0, cardPad / 2, 0, cardPad / 2)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
        }
        // Re-checking the previous button to undo a declined dialog would
        // otherwise come straight back through this listener as a new pick.
        var reverting = false
        group.setOnCheckedChangeListener { g, checkedId ->
            if (reverting) return@setOnCheckedChangeListener
            val child = g.findViewById<View>(checkedId)
                ?: return@setOnCheckedChangeListener
            val picked = options.getOrNull(g.indexOfChild(child))
                ?: return@setOnCheckedChangeListener
            if (picked == shown) return@setOnCheckedChangeListener
            val from = shown
            confirmVulkanChange(picked) { accepted ->
                if (accepted) {
                    shown = picked
                    Settings.vulkanDriver = picked
                    VulkanProvisionOp.start(this, from, picked)
                } else {
                    reverting = true
                    // By position, since the ids are no longer `ordinal + 1`.
                    group.getChildAt(options.indexOf(from))?.let { group.check(it.id) }
                    reverting = false
                }
            }
        }
        return group
    }

    /** Drivers this APK can actually provision. Off is always offered. */
    private fun vulkanOptions(): List<VulkanDriver> = buildList {
        add(VulkanDriver.OFF)
        if (EnabledGraphicsBackends.libhybris) add(VulkanDriver.SYSTEM)
        if (EnabledGraphicsBackends.turnip) add(VulkanDriver.TURNIP)
    }

    private fun vulkanLabel(driver: VulkanDriver): Int = when (driver) {
        VulkanDriver.OFF -> R.string.vulkan_driver_off
        VulkanDriver.SYSTEM -> R.string.vulkan_driver_system
        VulkanDriver.TURNIP -> R.string.vulkan_driver_turnip
    }

    /**
     * Spell out what is about to change in every container before doing it —
     * this rewrites files in the rootfs, so it does not get to be a silent
     * checkbox. With no ready container there is nothing to write to, and the
     * dialog's only action is to go back.
     */
    private fun confirmVulkanChange(picked: VulkanDriver, onResult: (Boolean) -> Unit) {
        val hasContainer = InstallationStore(this).list()
            .any { it.state == Installation.State.READY }
        if (!hasContainer) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.vulkan_confirm_title)
                .setMessage(R.string.vulkan_confirm_message_no_container)
                .setPositiveButton(android.R.string.ok) { _, _ -> onResult(false) }
                .setOnCancelListener { onResult(false) }
                .show()
            return
        }
        val message = if (picked == VulkanDriver.OFF) {
            getString(R.string.vulkan_confirm_message_cleanup)
        } else {
            getString(R.string.vulkan_confirm_message_enable, getString(vulkanLabel(picked)))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vulkan_confirm_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onResult(true) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(false) }
            .setOnCancelListener { onResult(false) }
            .show()
    }

    /**
     * Application ID, version and the non-official notice, plus the way
     * into the licenses index. The identity line is selectable because
     * the README asks bug reporters to quote their app version — make
     * that copyable rather than something to squint at and retype.
     */
    private fun buildAboutSettings(): android.view.View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_s)
        column.addView(
            TextView(this).apply {
                text = getString(
                    R.string.settings_about_detail,
                    BuildConfig.APPLICATION_ID,
                    BuildConfig.VERSION_NAME,
                )
                tawcText(R.style.TextAppearance_Tawc_Body)
                setTextIsSelectable(true)
                setPadding(0, cardPad / 2, 0, gap)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        // Fine print under the identity line, in DSH's `.desc` shape: one
        // step down the type scale, `label-tertiary`, and no block of its
        // own. It says what the app is *not*, which belongs next to what it
        // is rather than in the licenses text (that page is about the
        // components we ship, not about us).
        column.addView(
            TextView(this).apply {
                text = getString(R.string.settings_about_unofficial)
                tawcText(R.style.TextAppearance_Tawc_Caption)
                setTextColor(context.tawcTertiaryColor())
                setPadding(0, 0, 0, cardPad)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        column.addView(
            tonalButton(getString(R.string.settings_about_licenses)) {
                startActivity(Intent(this, LicensesActivity::class.java))
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        return column
    }
}

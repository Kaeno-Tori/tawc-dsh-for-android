package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.install.distro.origin
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tawcSecondaryColor
import me.phie.tawc.ui.tawcInput
import me.phie.tawc.ui.tawcText
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp

/**
 * "Install new distro" screen — the setup flow's "customize" path.
 * Form-only: distro / label / method / cache-proxy controls plus an
 * Install button. Tapping Install kicks off [InstallationService] and
 * finishes, so the screen behind it (the setup screen, which renders the
 * install inline) picks the progress up.
 *
 * Mutating an installation never happens as a side-effect of opening
 * this screen. The button press is the only trigger; CLI install /
 * uninstall lives on the dev exec broker (see `InstallActions`).
 * This was the
 * `install-uninstall-trigger-via-activity-launch` issue's resolution.
 */
class InstallActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var selectedMethod: String? = null
    private var selectedDistro: String? = null

    /** (distro key, radio) for every rendered distro option; the
     *  picker manages exclusivity itself. See [buildDistroPicker]. */
    private val distroRadios = mutableListOf<Pair<String, RadioButton>>()
    private var labelEdited: Boolean = false

    /**
     * Bootstrap-flavor pick, as a [me.phie.tawc.install.distro.BootstrapFlavor.id]
     * string. Null means "the distro's supported flavor" (the only
     * possibility in release builds — the row only renders when the
     * build ships >1 flavor for the selected distro). Reset to null
     * when the distro selection changes.
     */
    private var selectedBootstrap: String? = null
    private var bootstrapRow: LinearLayout? = null

    /**
     * Download-mirror input and its preview line. [savedMirrorText] is
     * the field's text as restored from instance state (or the persisted
     * [me.phie.tawc.Settings.bootstrapMirror] on a cold start); it is
     * written back to Settings in [beginInstall] rather than on every
     * keystroke, so a half-typed URL can't outlive the form.
     *
     * The spinner above the field is a shortcut, not a second source of
     * truth: it writes the base URL into the field and otherwise only
     * *reflects* it ([syncMirrorSpinner]). That keeps the two from
     * disagreeing — and makes "the text matches no preset" the same
     * state as "Custom…" instead of a snap-back fight.
     */
    private lateinit var mirrorField: EditText
    private lateinit var mirrorHint: TextView
    private lateinit var mirrorPresetRow: LinearLayout
    private var mirrorSpinner: android.widget.Spinner? = null
    private var mirrorPresets: List<String> = emptyList()
    private var mirrorSync = false
    private var savedMirrorText: String = ""

    /**
     * External-storage binds the install starts with (see
     * notes/external-binds.md). Starts empty, edited via
     * [ManageBindsActivity], passed to the service as JSON. Only
     * meaningful for tawcroot installs — the row hides (and
     * [beginInstall] passes null) for other methods.
     */
    private val pendingBinds = mutableListOf<ExternalBind>()
    private var bindsRow: LinearLayout? = null
    private var bindsCountLabel: TextView? = null

    /**
     * Whether [pendingBinds] still holds the set the form was *seeded*
     * with rather than one the user assembled. Only affects the row's
     * wording (see [updateBindsRow]); cleared the moment the manage
     * screen comes back with a list, so the label can't outlive the
     * provenance it describes.
     */
    private var bindsSeededFromHome = false
    private val manageBinds = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(ManageBindsActivity.EXTRA_BINDS)
        if (result.resultCode == RESULT_OK && json != null) {
            pendingBinds.clear()
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(json)) }
                    .getOrDefault(emptyList())
            )
            bindsSeededFromHome = false
            updateBindsRow()
        }
    }

    /**
     * Tri-state for the "Use cache proxy" checkbox:
     *   - null: not yet initialised (will be seeded from build type).
     *   - true / false: user-overridden value, persisted across rotations.
     */
    private var useCacheProxy: Boolean? = null
    private lateinit var cacheProxyCheckbox: CheckBox

    /** ando (notes/ando.md) toggle. Off by default, shown for all
     *  methods; persisted across rotations. */
    private var andoEnabled: Boolean = false

    private lateinit var formScroll: ScrollView
    private lateinit var formSection: LinearLayout
    private lateinit var methodGroup: RadioGroup
    private lateinit var labelField: EditText
    private lateinit var locationLabel: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var scaffold: me.phie.tawc.ui.Scaffold

    /**
     * Resolved id for the Install button. Tracks (label → slug → unique)
     * so the service-call site doesn't have to re-derive it; null when
     * the label is empty / unslugifiable / collides with an existing
     * install (Install button is also disabled in that state).
     */
    private var resolvedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMethod = savedInstanceState?.getString(KEY_METHOD)
        selectedDistro = savedInstanceState?.getString(KEY_DISTRO)
        selectedBootstrap = savedInstanceState?.getString(KEY_BOOTSTRAP)
        labelEdited = savedInstanceState?.getBoolean(KEY_LABEL_EDITED) == true
        useCacheProxy = when {
            savedInstanceState?.containsKey(KEY_USE_PROXY) == true ->
                savedInstanceState.getBoolean(KEY_USE_PROXY)
            // Dev build default: on. Production: off (and the row is
            // hidden anyway, see buildCacheProxyRow).
            me.phie.tawc.BuildConfig.DEBUG -> true
            else -> false
        }
        andoEnabled = savedInstanceState?.getBoolean(KEY_ANDO) == true
        // Seed from the saved setting so a mirror survives an app
        // restart — it is a persisted preference, not a per-install
        // choice, and re-typing it on every install would be hostile.
        savedMirrorText = savedInstanceState?.getString(KEY_MIRROR)
            ?: me.phie.tawc.Settings.bootstrapMirror
        pendingBinds.clear()
        savedInstanceState?.getString(KEY_BINDS)?.let { savedBinds ->
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(savedBinds)) }
                    .getOrDefault(emptyList())
            )
        }
        // A form opened fresh — not restored — starts from the main
        // screen's "bind shared storage automatically" choice, so the two
        // ways into an install agree about the default instead of
        // MainActivity mapping storage and this one silently mapping
        // nothing.
        // Seeded once and only when the list is empty: once it is on
        // screen the list is the user's, and clearing it in the manage
        // screen must not be undone by a later re-seed. Rotation takes
        // the [KEY_BINDS] path above instead, which is what keeps an
        // emptied list empty.
        bindsSeededFromHome = savedInstanceState == null &&
            pendingBinds.isEmpty() &&
            AllFilesAccess.declared(this) &&
            (me.phie.tawc.Settings.autoBindSharedStorage ?: AllFilesAccess.granted())
        if (bindsSeededFromHome) {
            pendingBinds.addAll(AllFilesAccess.sharedStorageBinds())
        }

        scaffold = buildChildScreen(getString(R.string.title_install))

        val pad = resources.getDimensionPixelSize(R.dimen.tawc_space_l)
        formSection = buildFormSection(pad, savedInstanceState?.getString(KEY_LABEL_TEXT))
        // Wrap the form in a ScrollView so the soft keyboard can lift
        // the EditText into view without ever covering the Install
        // button on a small phone. The scaffold's content column is
        // MATCH_PARENT, so the scroll view fills it; the inner
        // formSection is WRAP_CONTENT and grows naturally.
        formScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(formSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        scaffold.content.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(scaffold.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LABEL_EDITED, labelEdited)
        // Guard the late-init lookup the same way [revalidate] does —
        // saving state can in principle fire before the form is built
        // if a future onCreate path bails early.
        if (::labelField.isInitialized) {
            outState.putString(KEY_LABEL_TEXT, labelField.text.toString())
        }
        selectedMethod?.let { outState.putString(KEY_METHOD, it) }
        selectedDistro?.let { outState.putString(KEY_DISTRO, it) }
        selectedBootstrap?.let { outState.putString(KEY_BOOTSTRAP, it) }
        useCacheProxy?.let { outState.putBoolean(KEY_USE_PROXY, it) }
        outState.putBoolean(KEY_ANDO, andoEnabled)
        if (::mirrorField.isInitialized) {
            outState.putString(KEY_MIRROR, mirrorField.text.toString())
        }
        outState.putString(KEY_BINDS, ExternalBind.toJsonArray(pendingBinds).toString())
    }

    private fun buildFormSection(pad: Int, savedLabelText: String?): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // List the distros that match the host's primary ABI. Empty
        // list means no Distro supports this device; render an
        // explanatory line rather than a dead radio group, and let
        // the service-level gate refuse the install if the user taps
        // anyway.
        val available = DistroRegistry.availableForHost()

        s.addView(buildDistroPicker(available), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        s.addView(buildMirrorField(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Dev-only bootstrap-flavor radio row, between the distro
        // picker and the label field. Rendered only when the selected
        // distro has >1 flavor *in this build* — release APKs ship
        // tarball only (EnabledBootstrapFlavors), so the row never
        // appears there.
        run {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            bootstrapRow = row
            updateBootstrapRow()
            s.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        }

        s.addView(buildInstallDirField(available, savedLabelText), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Method picker + info link only render when this APK ships
        // more than one install method. The single-method case (default
        // for release: tawcroot only) hides both — there's nothing for
        // the user to choose, and the "What's the difference?" page
        // would compare a single option against nothing. Saved
        // instance state overrides the default for rotation.
        if (!EnabledMethods.onlyOne) {
            s.addView(buildMethodPicker(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        } else {
            // Pin the single enabled method as the selection so
            // beginInstall doesn't fall back to tawcroot's KEY when
            // the only enabled method happens to be something else.
            selectedMethod = EnabledMethods.keys.single()
        }

        if (!EnabledMethods.onlyOne) {
            // "What's the difference?" link to the install method info
            // page. Borderless text button so it reads as a help affordance,
            // not a primary action.
            s.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.install_help_methods)
                    setTextColor(getColor(R.color.tawc_info))
                    setOnClickListener {
                        startActivity(Intent(this@InstallActivity, InstallMethodInfoActivity::class.java))
                    }
                },
                verticalLp(WRAP_CONTENT, WRAP_CONTENT, bottomMargin = pad),
            )
        }

        // Header for the trailing post-install-editable settings (ando,
        // binds): both are also on the distro settings page, so say so
        // here and spare the user agonizing over them mid-install.
        s.addView(
            TextView(this).apply {
                text = getString(R.string.install_changeable_later)
                tawcText(R.style.TextAppearance_Tawc_BodySmall)
                setTextColor(tawcSecondaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 4),
        )

        // ando toggle (notes/ando.md) — shown for all methods and build
        // types, default off. Opt-in, fail-closed. Above the binds row.
        s.addView(buildAndoRow(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // External-storage binds row: count + Manage button. Hidden
        // when the build doesn't ship MANAGE_EXTERNAL_STORAGE, and for
        // non-tawcroot methods (the only consumer of the bind list) —
        // see updateBindsRow / the method-picker listener.
        if (AllFilesAccess.declared(this)) {
            val row = buildBindsRow()
            bindsRow = row
            s.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
            updateBindsRow()
        }

        // Dev-only "Use cache proxy" checkbox. Hidden in release builds
        // — production must never even ask the user about a localhost
        // proxy URL, since it'd never be reachable from a packaged APK.
        if (me.phie.tawc.BuildConfig.DEBUG) {
            s.addView(buildCacheProxyRow(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        }

        installButton = primaryButton(getString(R.string.action_install)) { beginInstall() }
        s.addView(installButton, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        // Initial validation pass — populates resolvedId, location row,
        // and Install button enabled-state from the default label.
        revalidate()
        return s
    }

    /**
     * Build the distro picker. The two supported distros
     * ([Distro.supported]) sit at the top; everything else the APK
     * ships hides behind an "Other distros" expander, collapsed
     * unless the current pick lives in there. Distros whose Android
     * ABI doesn't match the host aren't listed at all. With no
     * unsupported distros for this host it degrades to a flat list
     * (no expander).
     *
     * Radios are driven by hand rather than by a [RadioGroup] because
     * the selection spans two containers — a RadioGroup only
     * un-checks its own direct children.
     */
    private fun buildDistroPicker(available: List<Distro>): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_distro_label); tawcText(R.style.TextAppearance_Tawc_Body) }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        if (available.isEmpty()) {
            val msg = TextView(this).apply {
                text = getString(R.string.install_no_supported_distro)
                tawcText(R.style.TextAppearance_Tawc_Body)
                typeface = Typeface.MONOSPACE
            }
            container.addView(msg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return container
        }

        distroRadios.clear()

        val initialKey = selectedDistro?.takeIf { k -> available.any { it.key == k } }
            ?: available.first().key

        for (d in available) {
            container.addView(distroRadio(d), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        selectDistro(initialKey, updateLabel = false)
        return container
    }

    /** One distro radio, registered in [distroRadios] for exclusivity. */
    private fun distroRadio(d: Distro): RadioButton {
        val rb = RadioButton(this).apply {
            id = View.generateViewId()
            text = d.displayName
            setOnClickListener { selectDistro(d.key, updateLabel = true) }
        }
        distroRadios.add(d.key to rb)
        return rb
    }

    /**
     * Make [key] the pick: check its radio, clear the rest, and (when
     * the user hasn't typed a custom label) follow the distro's default
     * label so flipping from "Arch Linux ARM" to "Debian Sid" updates
     * the install directory too.
     */
    private fun selectDistro(key: String, updateLabel: Boolean) {
        val changed = key != selectedDistro
        selectedDistro = key
        for ((k, rb) in distroRadios) rb.isChecked = (k == key)
        if (changed) {
            // Flavor picks don't transfer across distros — reset to the
            // new distro's supported default and rebuild the (dev-only)
            // radio row. Guarded on an actual change so a restored
            // selection keeps its saved flavor.
            selectedBootstrap = null
            updateBootstrapRow()
        }
        if (updateLabel && !labelEdited) {
            DistroRegistry.availableForHost().firstOrNull { it.key == key }
                ?.let { setLabelTextSilently(it.defaultLabel) }
        }
        updateMirrorRow()
        revalidate()
    }

    /**
     * (Re)populate the dev-only bootstrap-flavor radio row for the
     * currently selected distro. Hidden entirely when this build ships
     * a single flavor for it — which is every distro in a release
     * APK. See notes/installation.md "Bootstrap flavors".
     */
    private fun updateBootstrapRow() {
        val row = bootstrapRow ?: return
        row.removeAllViews()
        val distro = DistroRegistry.availableForHost().firstOrNull { it.key == selectedDistro }
        val flavors = distro?.bootstrapFlavors?.keys.orEmpty()
        if (distro == null || flavors.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.addView(
            TextView(this).apply { text = getString(R.string.install_bootstrap_label); tawcText(R.style.TextAppearance_Tawc_Body) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val idsByFlavor = mutableMapOf<Int, String>()
        for (f in flavors) {
            val rid = View.generateViewId()
            idsByFlavor[rid] = f.id
            group.addView(
                RadioButton(this).apply {
                    id = rid
                    text = if (f == distro.supportedFlavor) "${f.id} (supported)" else f.id
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
        row.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val initial = selectedBootstrap ?: distro.supportedFlavor.id
        idsByFlavor.entries.firstOrNull { it.value == initial }?.let { group.check(it.key) }
        group.setOnCheckedChangeListener { _, checkedId ->
            idsByFlavor[checkedId]?.let { selectedBootstrap = it }
            updateMirrorHint()
        }
    }

    /**
     * Build the optional download-mirror block: a mirror picker, a
     * free-text base URL, and a preview of the URL that will actually be
     * downloaded.
     *
     * The distro's bootstrap URL is a compile-time constant pointing at
     * one upstream origin, which is the wrong origin for most of the
     * world — this lets the user re-root that download onto a mirror
     * their network can reach. See
     * [me.phie.tawc.install.BootstrapMirror] for what "re-root" means
     * (a prefix swap, not an origin swap) and why the substitution can't
     * be done any earlier.
     *
     * The preview line is the only honest way to show this setting: the
     * user names a mirror, and the app decides where the distro's own
     * path slots in underneath it.
     */
    private fun buildMirrorField(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            TextView(this).apply { text = getString(R.string.install_mirror_label); tawcText(R.style.TextAppearance_Tawc_Body) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )

        mirrorPresetRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(mirrorPresetRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        mirrorField = tawcInput().apply {
            setText(savedMirrorText)
            hint = getString(R.string.install_mirror_hint)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = updateMirrorHint()
            })
        }
        container.addView(mirrorField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        mirrorHint = TextView(this).apply {
            tawcText(R.style.TextAppearance_Tawc_Caption)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        container.addView(mirrorHint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        updateMirrorRow()
        return container
    }

    /**
     * (Re)build the preset spinner for the currently selected distro.
     * Called on distro and flavor changes, not on keystrokes — the list
     * of presets only depends on which distro is picked, because each
     * one keeps its bootstrap under a different directory on the same
     * mirrors.
     */
    private fun updateMirrorRow() {
        if (!::mirrorPresetRow.isInitialized) return
        mirrorPresetRow.removeAllViews()

        val path = currentMirrorPath()
        // The same composition the probe uses as its candidate pool, so
        // every mirror offered here is one the install will measure.
        mirrorPresets = path?.let { me.phie.tawc.install.distro.MirrorPresets.basesFor(it) }
            ?: emptyList()

        val labels = buildList {
            add(getString(R.string.install_mirror_preset_upstream))
            me.phie.tawc.install.distro.MirrorPresets.ALL.forEach { add(getString(it.nameRes)) }
            add(getString(R.string.install_mirror_preset_custom))
        }
        val spinner = android.widget.Spinner(this).apply {
            isEnabled = mirrorPresets.isNotEmpty()
            adapter = android.widget.ArrayAdapter(
                this@InstallActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
                ) {
                    if (mirrorSync) return
                    // 0 = upstream (clear), 1..n = a preset, last = custom
                    // (leave the text alone so the user can type).
                    when {
                        position == 0 -> setMirrorText("")
                        position <= mirrorPresets.size -> setMirrorText(mirrorPresets[position - 1])
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        mirrorSpinner = spinner
        mirrorPresetRow.addView(spinner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        updateMirrorHint()
    }

    /** [me.phie.tawc.install.distro.Distro.bootstrapMirrorPath] of the current pick. */
    private fun currentMirrorPath(): String? =
        DistroRegistry.availableForHost().firstOrNull { it.key == selectedDistro }?.bootstrapMirrorPath

    /** Field write that the spinner's own listener shouldn't react to. */
    private fun setMirrorText(text: String) {
        mirrorSync = true
        try {
            mirrorField.setText(text)
        } finally {
            mirrorSync = false
        }
    }

    /**
     * Point the spinner at whichever entry the field's text corresponds
     * to. Derived, never authoritative: "matches nothing" is exactly the
     * "Custom…" entry, so this can't fight the user.
     */
    private fun syncMirrorSpinner() {
        val spinner = mirrorSpinner ?: return
        val text = BootstrapMirror.normalise(mirrorField.text.toString())
        val index = when {
            text.isEmpty() -> 0
            else -> mirrorPresets.indexOf(text).let { if (it < 0) mirrorPresets.size + 1 else it + 1 }
        }
        if (spinner.selectedItemPosition == index) return
        mirrorSync = true
        try {
            spinner.setSelection(index)
        } finally {
            mirrorSync = false
        }
    }

    /**
     * Refresh the preview line (and the spinner) from the current
     * (distro, flavor, field) triple. An empty field and "this distro
     * can't be mirrored" are both non-errors — they read as statements
     * of what will happen, in the quiet variant colour; only a
     * malformed URL is an error.
     */
    private fun updateMirrorHint() {
        if (!::mirrorHint.isInitialized || !::mirrorField.isInitialized) return
        val distro = DistroRegistry.availableForHost().firstOrNull { it.key == selectedDistro }
        val prefix = distro?.bootstrapMirrorPrefix
        val raw = mirrorField.text.toString()
        val normalColor = mirrorHint.tawcSecondaryColor()

        // Preview against the flavor the install would actually use, so
        // the default (flavor-agnostic) case and the dev-only flavor
        // radio agree with each other.
        val descriptor = distro?.let {
            val flavor = it.bootstrapFlavors.keys.firstOrNull { f -> f.id == selectedBootstrap }
                ?: it.supportedFlavor
            it.bootstrapFlavors[flavor]
        }

        val (text, isError) = when {
            distro == null -> "" to false
            prefix == null -> getString(
                R.string.install_mirror_unavailable, distro.displayName,
            ) to false
            BootstrapMirror.normalise(raw).isEmpty() ->
                getString(R.string.install_mirror_default, prefix) to false
            else -> {
                val rewritten = descriptor?.let { BootstrapMirror.apply(it, prefix, raw) }
                // An unchanged descriptor means the rewrite refused the
                // base (no scheme, or a URL not under the distro's root).
                val url = rewritten?.takeIf { it !== descriptor }?.origin
                if (url != null) {
                    getString(R.string.install_mirror_applied, url) to false
                } else {
                    getString(R.string.install_mirror_invalid) to true
                }
            }
        }
        mirrorHint.text = text
        mirrorHint.setTextColor(
            if (isError) MaterialColors.getColor(mirrorHint, com.google.android.material.R.attr.colorError)
            else normalColor,
        )
        syncMirrorSpinner()
    }

    /**
     * Build the merged Label / Install-directory block. The user-typed
     * string is the [Installation.label]; we slugify it into the on-disk
     * id and render the resulting absolute path on the line directly
     * below as a quieter monospace echo, which doubles as the hint
     * shown when the label is empty / unslugifiable / collides.
     */
    private fun buildInstallDirField(available: List<Distro>, savedLabelText: String?): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_label_label); tawcText(R.style.TextAppearance_Tawc_Body) }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val initialDefault = available.firstOrNull { it.key == selectedDistro }?.defaultLabel ?: ""
        labelField = tawcInput().apply {
            setText(savedLabelText ?: initialDefault)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressEditedFlag) labelEdited = true
                    revalidate()
                }
            })
        }
        container.addView(labelField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Single quieter monospace line below the input — when valid,
        // it's the resolved absolute install path; when invalid (empty
        // / unslugifiable / collides), it's the explanation in the
        // same slot. One line of feedback instead of two.
        locationLabel = TextView(this).apply {
            tawcText(R.style.TextAppearance_Tawc_Caption)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(tawcSecondaryColor())
        }
        container.addView(locationLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return container
    }

    /**
     * `setText(...)` from inside the activity (e.g. when the distro
     * radio flips) must not flip [labelEdited] back to true. Wrap
     * those updates with this guard.
     */
    private var suppressEditedFlag = false

    private fun setLabelTextSilently(text: String) {
        suppressEditedFlag = true
        try {
            labelField.setText(text)
        } finally {
            suppressEditedFlag = false
        }
    }

    /**
     * Recompute resolvedId, the location row, the hint, and the
     * Install button's enabled state from the current label. Called on
     * label edits and distro flips.
     */
    private fun revalidate() {
        if (!::labelField.isInitialized) return
        val rawLabel = labelField.text.toString().trim()
        val slug = if (rawLabel.isEmpty()) null else Installation.slugifyLabel(rawLabel)
        val collides = slug != null && store.installationDir(slug).exists()
        resolvedId = slug?.takeUnless { collides }

        if (::locationLabel.isInitialized) {
            locationLabel.text = when {
                rawLabel.isEmpty() -> getString(R.string.install_label_empty)
                slug == null -> getString(R.string.install_label_invalid)
                collides -> getString(R.string.install_already_installed_at, store.installationDir(slug).absolutePath)
                else -> store.installationDir(slug).absolutePath
            }
            locationLabel.setTextColor(
                if (resolvedId == null) {
                    MaterialColors.getColor(locationLabel, com.google.android.material.R.attr.colorError)
                } else {
                    locationLabel.tawcSecondaryColor()
                },
            )
        }

        if (::installButton.isInitialized) {
            installButton.isEnabled = (resolvedId != null)
            installButton.text = getString(R.string.action_install)
        }
    }

    /**
     * "External storage binds: N" + Manage button. Tapping Manage
     * round-trips [pendingBinds] through [ManageBindsActivity] so the
     * binds are settled before the install starts — they're live
     * during the installation process itself (first boot included).
     */
    private fun buildBindsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val count = TextView(this).apply { tawcText(R.style.TextAppearance_Tawc_Body) }
        bindsCountLabel = count
        row.addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tonalButton(getString(R.string.action_manage)) {
            manageBinds.launch(
                ManageBindsActivity.intentForResult(
                    this, ExternalBind.toJsonArray(pendingBinds).toString(),
                )
            )
        })
        return row
    }

    private fun updateBindsRow() {
        bindsCountLabel?.text = getString(
            if (bindsSeededFromHome) {
                R.string.install_external_binds_auto_label
            } else {
                R.string.install_external_binds_label
            },
            pendingBinds.size,
        )
        // Only tawcroot consumes the bind list; hide the row when the
        // user picks a debug method so the form doesn't promise binds
        // the spawn path would ignore. selectedMethod is always set
        // before the row exists (pinned for the single-method case,
        // defaulted by buildMethodPicker otherwise).
        bindsRow?.visibility =
            if (selectedMethod == TawcrootMethod.KEY) View.VISIBLE else View.GONE
    }

    /**
     * ando toggle ([buildToggleRow], notes/ando.md). Off by
     * default; drives [andoEnabled], passed to the service by
     * [beginInstall]. Shown for every method and build type — unlike
     * binds, ando applies to all install methods.
     */
    private fun buildAndoRow(): LinearLayout =
        buildToggleRow(
            this,
            R.string.ando_toggle_label,
            R.string.ando_toggle_description,
            andoEnabled,
        ) { _, checked -> andoEnabled = checked }

    /**
     * Dev-only "Use cache proxy" checkbox. Drives [useCacheProxy],
     * which gates whether [beginInstall] passes a `mirrorProxy` URL to
     * the service. See `notes/cache-proxy.md`.
     */
    private fun buildCacheProxyRow(): CheckBox {
        cacheProxyCheckbox = CheckBox(this).apply {
            text = getString(R.string.install_use_cache_proxy)
            isChecked = useCacheProxy ?: true
            setOnCheckedChangeListener { _, checked -> useCacheProxy = checked }
        }
        return cacheProxyCheckbox
    }

    /**
     * Build the method picker. One radio per build-enabled method
     * ([EnabledMethods]) in recommendation order: tawcroot first as
     * the default, proot as the established rootless fallback, chroot
     * last for rooted-only setups. Caller in [buildFormSection] omits
     * the picker entirely when only one method is enabled.
     */
    private fun buildMethodPicker(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_method_label); tawcText(R.style.TextAppearance_Tawc_Body) }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        methodGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rootAvailable = Su.rootAvailable()

        // Use generateViewId() rather than hand-picked constants —
        // any literal we'd reach for in the AAPT range collides with
        // future R.id.* once we add a layout XML.
        val idByKey = mutableMapOf<String, Int>()
        val keyById = mutableMapOf<Int, String>()
        for (key in EnabledMethods.keys) {
            val rid = View.generateViewId()
            idByKey[key] = rid
            keyById[rid] = key
            val rb = RadioButton(this).apply {
                id = rid
                text = when (key) {
                    TawcrootMethod.KEY -> getString(R.string.install_method_tawcroot_recommended)
                    ProotMethod.KEY -> getString(R.string.install_method_proot)
                    ChrootMethod.KEY -> getString(R.string.install_method_chroot_requires_root)
                    else -> key
                }
                // Chroot greys out on un-rooted devices so the
                // limitation is visible at the form level.
                if (key == ChrootMethod.KEY) isEnabled = rootAvailable
            }
            methodGroup.addView(rb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        container.addView(methodGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Initial selection: saved/intent override if it points at an
        // enabled method, else the first enabled key (tawcroot under
        // the default ordering).
        val initial = selectedMethod?.takeIf { idByKey.containsKey(it) }
            ?: EnabledMethods.keys.first()
        idByKey[initial]?.let { methodGroup.check(it) }
        selectedMethod = initial

        methodGroup.setOnCheckedChangeListener { _, checkedId ->
            keyById[checkedId]?.let { selectedMethod = it; updateBindsRow() }
        }
        return container
    }

    private fun beginInstall() {
        // Only the chroot path needs `su`. Proot/tawcroot are rootless
        // by definition, so a missing-root device fails this check only
        // if the user picked chroot anyway.
        val methodKey = selectedMethod ?: EnabledMethods.keys.firstOrNull() ?: TawcrootMethod.KEY
        if (methodKey == ChrootMethod.KEY && !Su.rootAvailable()) {
            // We don't have a panel anymore; surface as a quick
            // toast-style status on the form. Service-level gate would
            // also refuse, but a fail-fast at the form level avoids the
            // service start.
            android.widget.Toast.makeText(
                this,
                getString(R.string.install_root_unavailable),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val targetId = resolvedId ?: return  // button disabled when null

        val distroKey = selectedDistro
        val labelText = labelField.text.toString().trim().takeIf { it.isNotEmpty() }

        // Persist the mirror before handing off: [Installer] reads it
        // from Settings, not from the service intent, because the same
        // value has to apply to a re-install triggered from anywhere.
        if (::mirrorField.isInitialized) {
            me.phie.tawc.Settings.bootstrapMirror = mirrorField.text.toString().trim()
        }
        // Dev-time cache proxy URL: when the (debug-only) checkbox is
        // on, use the standard local proxy URL; else null. Service-side
        // gates this on BuildConfig.DEBUG so a release APK ignores any
        // stray value anyway.
        val mirrorProxyUrl = if (useCacheProxy == true) DEFAULT_PROXY_URL else null

        // Bind list for tawcroot installs; null for methods that
        // don't consume binds.
        val bindsJson = if (methodKey == TawcrootMethod.KEY && AllFilesAccess.declared(this)) {
            ExternalBind.toJsonArray(pendingBinds).toString()
        } else {
            null
        }

        val launch = {
            InstallationService.startInstall(
                this, targetId, methodKey, distroKey, labelText, mirrorProxyUrl, bindsJson, andoEnabled,
                selectedBootstrap,
            )
            // Hand control back to the setup screen, which renders the
            // install inline. Opening LogScreenActivity here would be the
            // jump the inline progress exists to remove — and it would
            // leave the user on a screen they then have to back out of.
            finish()
        }

        // A shared-storage bind without the all-files grant fails
        // closed at first spawn — i.e. during the install itself. Warn
        // up front instead of letting the install run for minutes and
        // then park in FAILED.
        if (bindsJson != null &&
            AllFilesAccess.requiresGrant(pendingBinds) && !AllFilesAccess.granted()
        ) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.install_binds_grant_title))
                .setMessage(getString(R.string.install_binds_grant_message))
                .setNegativeButton(getString(R.string.install_binds_grant_anyway)) { _, _ -> launch() }
                .setPositiveButton(getString(R.string.install_binds_grant_grant)) { _, _ ->
                    AllFilesAccess.openSettings(this)
                }
                .show()
            return
        }
        launch()
    }

    companion object {
        /** URL the "Use cache proxy" checkbox sets. Debug-only. */
        private const val DEFAULT_PROXY_URL = "http://127.0.0.1:8080/proxy/"
        private const val KEY_METHOD = "tawc.install.method"
        private const val KEY_DISTRO = "tawc.install.distro"
        private const val KEY_LABEL_EDITED = "tawc.install.labelEdited"
        private const val KEY_LABEL_TEXT = "tawc.install.labelText"
        private const val KEY_USE_PROXY = "tawc.install.useCacheProxy"
        private const val KEY_BINDS = "tawc.install.externalBinds"
        private const val KEY_ANDO = "tawc.install.ando"
        private const val KEY_BOOTSTRAP = "tawc.install.bootstrap"
        private const val KEY_MIRROR = "tawc.install.mirror"
    }
}

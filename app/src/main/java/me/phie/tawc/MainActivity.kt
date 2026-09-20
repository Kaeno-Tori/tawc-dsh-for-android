package me.phie.tawc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.phie.tawc.dsh.DshActivity
import me.phie.tawc.install.AllFilesAccess
import me.phie.tawc.install.EnabledMethods
import me.phie.tawc.install.ExternalBind
import me.phie.tawc.install.InstallActivity
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationService
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.buildToggleRow
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.OperationLogPanel
import me.phie.tawc.ops.OperationsRegistry
import me.phie.tawc.ops.confirmAndCancel
import me.phie.tawc.ui.buildHomeScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tawcDivider
import me.phie.tawc.ui.tawcSecondaryColor
import me.phie.tawc.ui.tawcTertiaryColor
import me.phie.tawc.ui.tawcText
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp

/**
 * The app's entry point, and the whole host-side flow.
 *
 * This app does one thing — hand the user to DSH — and a container is
 * the step on the way there, not a destination. So there is no home
 * screen listing features: this Activity renders whatever the single
 * installation's state calls for, then gets out of the way.
 *
 *   no container   setup — one-tap default, or the full form
 *   installing     live progress inline; no jump to a log screen
 *   failed         the reason, plus a one-tap start-over
 *   ready          straight into [DshActivity]
 *
 * **Single container by design.** One app, one container: everything
 * above is a function of `store.list().firstOrNull()`, and the user
 * never picks between installations.
 */
class MainActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Body of the scaffold. Every render rebuilds its children. */
    private lateinit var body: LinearLayout

    /**
     * The progress surface, built lazily and reused across renders.
     * [OperationLogPanel] owns its view, so a rebuild has to re-add the
     * same instance rather than construct a second one.
     */
    private val panel by lazy { OperationLogPanel(this) }

    /** What is on screen now, so an unrelated re-render is a no-op. */
    private var rendered: Kind? = null

    /** The op the panel is bound to, so rebinding the same one is a no-op. */
    private var boundOpId: String? = null

    /** One-way latch for [forwardToDsh]; onStart can run more than once. */
    private var forwarded = false

    /**
     * Set by "start over" so the reinstall happens by itself once the
     * uninstall it triggered has finished and the slot is gone. Without
     * it, recovery would be uninstall → setup screen → install, which
     * is three taps and two screens for one intent.
     */
    private var restartAfterUninstall: InstallRequest? = null

    private var collectJob: Job? = null

    /**
     * The container state [render] saw last time, so a finish can be
     * recognised as a *transition* (INSTALLING → READY) rather than as a
     * property of the record.
     */
    private var lastRenderedState: Installation.State? = null

    /** See [render]: true only while the just-finished install's receipt
     *  is still owed to the user. */
    private var completedNow = false

    /**
     * The setup screen's all-files-access card, or null when this build
     * declares no such permission or the screen isn't showing it. Held
     * so [onResume] can refresh it in place — see [renderGrantCard].
     */
    private var grantCard: LinearLayout? = null

    /** The things the host side can be showing. */
    private enum class Kind { SETUP, PROGRESS, FAILED, DONE, DSH }

    private fun successColor(): Int =
        getColor(R.color.tawc_success)

    /** Enough of an install to repeat it, captured before its slot is removed. */
    private class InstallRequest(
        val id: String,
        val methodKey: String?,
        val distroKey: String?,
        val label: String?,
        val andoEnabled: Boolean,
        /** Non-null when this install extracts a pack the user picked. */
        val packUri: String? = null,
        val packName: String? = null,
    )

    /**
     * SAF picker for a rootfs pack.
     *
     * `OpenDocument` rather than `GetContent`: only the former can return
     * a URI whose read grant survives a reboot, and an import that fails
     * an hour later because the grant lapsed reads as a corrupt pack.
     * The picker is opened with a wildcard MIME filter because `.tar.zst`
     * has no registered type — filtering would hide the very files this
     * is for.
     */
    private val pickPack = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Not every provider grants persistable access. The URI is
            // still readable for this process, which covers the install;
            // only a retry after a restart would need more, and the
            // service re-checks readability and rejects with a clear
            // message rather than failing mid-copy.
        }
        launchImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        val scaffold = buildHomeScreen(getString(R.string.app_name))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(scaffold.root)
    }

    override fun onStart() {
        super.onStart()
        // Two things move this screen, and they are separate writes in
        // the service: the registry (an op appearing or departing) and
        // the store (the record it renders). It listened to the registry
        // alone, which is not a superset — the service registers the op
        // *before* its worker writes INSTALLING, so the render that
        // followed the register still read "no container" and the write
        // after it had nobody listening. Re-read the store on every
        // change either way.
        collectJob = scope.launch {
            combine(OperationsRegistry.ops, InstallationStore.changes) { _, _ -> }
                .collect { render() }
        }
    }

    override fun onStop() {
        collectJob?.cancel()
        collectJob = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
        // Coming back from the system "all files access" toggle is the
        // whole point of this call: [render] rebuilds only on a Kind
        // *change*, so a resume that lands on the same screen would keep
        // showing "not granted" for a permission the user just gave.
        renderGrantCard()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val inst = store.list().firstOrNull()

        // Work in flight is named by the op the service registered.
        // Derived from the persisted state rather than a local flag, so
        // a restart mid-install reattaches to the running op instead of
        // offering to start a second one.
        val opId = when (inst?.state) {
            Installation.State.INSTALLING -> "install:${inst.id}"
            Installation.State.UNINSTALLING -> "uninstall:${inst.id}"
            else -> null
        }

        // The summary is a *completion* affordance, not a home screen:
        // it appears only for a finish this Activity watched happen. On
        // a later launch a READY container goes straight to DSH, which
        // is what "this app only does one thing" means — otherwise
        // every cold start would land on a receipt for something that
        // finished days ago.
        if (lastRenderedState == Installation.State.INSTALLING &&
            inst?.state == Installation.State.READY
        ) {
            completedNow = true
        }
        lastRenderedState = inst?.state

        val kind = when {
            opId != null -> Kind.PROGRESS
            inst == null -> Kind.SETUP
            inst.state == Installation.State.READY ->
                if (completedNow) Kind.DONE else Kind.DSH
            else -> Kind.FAILED
        }

        // The uninstall that "start over" kicked off has finished (the
        // slot is gone), so relaunch the same install rather than
        // dropping the user back on the setup screen.
        if (kind == Kind.SETUP) {
            restartAfterUninstall?.let { request ->
                restartAfterUninstall = null
                launchInstall(request)
                return
            }
        }

        if (kind != rendered) {
            rendered = kind
            body.removeAllViews()
            when (kind) {
                Kind.SETUP -> buildSetup()
                Kind.PROGRESS -> buildProgress()
                // Non-null by construction: FAILED is only chosen for a
                // record that exists and is not READY. The compiler can't
                // carry that across the two `when`s.
                Kind.FAILED -> buildFailed(inst!!)
                Kind.DONE -> buildDone(inst!!)
                Kind.DSH -> forwardToDsh()
            }
        }

        if (kind == Kind.PROGRESS) bindOp(opId)
    }

    /**
     * What just happened, and the way in.
     *
     * The install used to hand straight off to DSH the instant the
     * rootfs flipped to READY, which for a 14-minute download meant the
     * screen the user had been watching simply vanished. This is the
     * receipt: where the rootfs came from, what it is, and where it
     * lives — the things you want when an install goes wrong later and
     * you're trying to say which one you have.
     */
    private fun buildDone(inst: Installation) {
        val pad = resources.getDimensionPixelSize(R.dimen.tawc_card_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_card_gap)

        body.addView(
            TextView(this).apply {
                text = getString(R.string.done_title)
                tawcText(R.style.TextAppearance_Tawc_SectionTitle)
                setTextColor(successColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap / 2),
        )
        body.addView(
            TextView(this).apply {
                text = getString(R.string.done_subtitle)
                tawcText(R.style.TextAppearance_Tawc_Body)
                setTextColor(tawcSecondaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )

        val distro = DistroRegistry.all.firstOrNull { it.key == inst.distro }
        addDoneRow(R.string.done_row_distro, distro?.displayName ?: inst.distro)
        addDoneRow(R.string.done_row_method, inst.method)
        addDoneRow(
            R.string.done_row_source,
            sourceDescription(inst),
            mono = true,
        )
        addDoneRow(
            R.string.done_row_location,
            store.rootfsDir(inst.id).absolutePath,
            mono = true,
        )
        addDoneRow(
            R.string.done_row_when,
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
            ).format(java.util.Date(inst.installedAtMillis)),
        )

        body.addView(View(this), verticalLp(MATCH_PARENT, pad))

        body.addView(
            primaryButton(getString(R.string.done_open_dsh)) {
                // Latch it so coming back from DSH doesn't re-show the
                // receipt — render() runs again on resume.
                completedNow = false
                forwardToDsh()
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    /**
     * One label/value line in the completion summary, in DSH's settings-row
     * shape (`AppearanceRow.module.css`'s `.group`): 16dp above and below,
     * with a hairline underneath rather than a card around it.
     */
    private fun addDoneRow(
        labelRes: Int,
        value: String,
        mono: Boolean = false,
    ) {
        val space = resources.getDimensionPixelSize(R.dimen.tawc_space_l)
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_space_xs)
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, space, 0, space)
        }
        group.addView(
            TextView(this).apply {
                text = getString(labelRes)
                tawcText(R.style.TextAppearance_Tawc_Caption)
                setTextColor(tawcTertiaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
        group.addView(
            TextView(this).apply {
                text = value
                tawcText(R.style.TextAppearance_Tawc_Body)
                if (mono) typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, gap, 0, 0)
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
        body.addView(group, verticalLp(MATCH_PARENT, WRAP_CONTENT))
        body.addView(tawcDivider())
    }

    /**
     * Where this rootfs came from, in the user's terms.
     *
     * An imported pack has no URL — `sourceUrl` records `import:<name>`
     * — and showing the raw upstream URL for one would be a lie about an
     * operation that never touched the network. A download shows the
     * full URL rather than just the host: which *path* on which mirror
     * is the detail that explains a version mismatch later.
     */
    private fun sourceDescription(inst: Installation): String =
        if (inst.packUri != null) {
            getString(R.string.done_source_pack, inst.sourceUrl.removePrefix("import:"))
        } else {
            inst.sourceUrl
        }

    /**
     * First run: what is about to happen, and the two ways to make it
     * happen. The default is one tap and is what the eye lands on; the
     * form sits behind a secondary button, so picking a different
     * distro stays possible without being a decision everyone has to
     * make before they can use the app.
     */
    private fun buildSetup() {
        val pad = resources.getDimensionPixelSize(R.dimen.tawc_card_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_card_gap)

        body.addView(
            TextView(this).apply {
                text = getString(R.string.setup_title)
                tawcText(R.style.TextAppearance_Tawc_SectionTitle)
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )
        body.addView(
            TextView(this).apply {
                text = getString(R.string.setup_body)
                tawcText(R.style.TextAppearance_Tawc_Body)
                setTextColor(tawcSecondaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )

        val distro = DistroRegistry.defaultForHost()
        if (distro == null) {
            // No distro this APK ships matches the device's ABI. The
            // install service would refuse too; saying so here beats a
            // button that fails when tapped.
            body.addView(
                TextView(this).apply {
                    text = getString(R.string.install_no_supported_distro)
                    tawcText(R.style.TextAppearance_Tawc_Body)
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT),
            )
            return
        }

        body.addView(
            TextView(this).apply {
                text = getString(R.string.setup_default_heading)
                tawcText(R.style.TextAppearance_Tawc_Body)
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap / 2),
        )
        body.addView(
            TextView(this).apply {
                text = getString(
                    R.string.setup_default_detail,
                    distro.displayName,
                    getString(R.string.setup_size_download),
                    getString(R.string.setup_size_disk),
                )
                tawcText(R.style.TextAppearance_Tawc_Body)
                setTextColor(tawcSecondaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )

        // The all-files grant is asked for *here*, in the main flow,
        // rather than only on the binds screen it belongs to. That
        // screen is reachable only from the custom install form or from
        // container management — i.e. only for a user who already knew
        // to go looking — while the one-tap install above is exactly the
        // path that never mentions it. Granting is skippable and binds
        // are still configured where they were; see [renderGrantCard].
        if (AllFilesAccess.declared(this)) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad, 0, pad)
            }
            grantCard = group
            body.addView(group, verticalLp(MATCH_PARENT, WRAP_CONTENT))
            body.addView(tawcDivider())
            body.addView(View(this), verticalLp(MATCH_PARENT, gap))
            renderGrantCard()
        } else {
            // A `-PtawcAllFilesAccess=false` build (the Play variant)
            // declares no MANAGE_EXTERNAL_STORAGE. The card must not
            // appear at all: it would deep-link to a toggle this app
            // isn't listed under.
            grantCard = null
        }

        val setUp = primaryButton(getString(R.string.setup_action_default)) {}
        setUp.setOnClickListener {
            // Disable on the way out: the service picks the work up
            // asynchronously, and a second tap inside that window would
            // start a second install against the same id.
            setUp.isEnabled = false
            launchInstall(
                InstallRequest(
                    id = defaultInstallId(distro),
                    methodKey = EnabledMethods.keys.firstOrNull(),
                    distroKey = distro.key,
                    label = distro.defaultLabel,
                    andoEnabled = false,
                ),
            )
        }
        body.addView(setUp, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap))

        body.addView(
            tonalButton(getString(R.string.setup_action_custom)) {
                startActivity(Intent(this, InstallActivity::class.java))
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )

        // The third way in, and the only one that needs no network at
        // all: a rootfs the user already has. It replaces the whole
        // download + provision path, which is why it sits next to the
        // default button rather than inside the form.
        body.addView(
            tonalButton(getString(R.string.setup_action_import_pack)) {
                pickPack.launch(arrayOf("*/*"))
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    /**
     * Fill [grantCard] for the current grant state, or do nothing when
     * the card isn't on screen.
     *
     * Idempotent and re-run from [onResume]: the grant lives in system
     * settings, so the only way to notice it is to ask again when the
     * user comes back. The card is rebuilt rather than shown/hidden
     * because its *content* differs by state — a granted card says so
     * instead of offering a button that would open a toggle already on.
     *
     * Asks for the grant, then — once it exists — offers to act on it.
     * The auto-bind box appears only in the granted state because only
     * then can it be honoured: shared-storage binds are fail-closed, so
     * an install carrying them without the grant refuses to launch. Its
     * default follows the grant for the same reason — the user was asked
     * for the permission and granted it, and a box that stayed off would
     * leave that answer with nothing to apply to. [Settings.
     * autoBindSharedStorage] records an explicit override; see
     * [autoBindSharedStorage] for the derivation both callers share.
     */
    private fun renderGrantCard() {
        val column = grantCard ?: return
        column.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_card_gap)
        val granted = AllFilesAccess.granted()

        column.addView(
            TextView(this).apply {
                text = getString(R.string.setup_grant_title)
                tawcText(R.style.TextAppearance_Tawc_Body)
                setTextColor(getColor(R.color.tawc_label_primary))
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap / 2),
        )
        column.addView(
            TextView(this).apply {
                text = getString(R.string.setup_grant_body)
                tawcText(R.style.TextAppearance_Tawc_Caption)
                setTextColor(tawcTertiaryColor())
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )
        if (granted) {
            column.addView(
                TextView(this).apply {
                    text = getString(R.string.setup_grant_granted)
                    tawcText(R.style.TextAppearance_Tawc_Body)
                    setTextColor(successColor())
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
            )
            column.addView(
                buildToggleRow(
                    this,
                    R.string.setup_auto_bind_label,
                    R.string.setup_auto_bind_description,
                    autoBindSharedStorage(),
                ) { _, checked -> Settings.autoBindSharedStorage = checked },
                verticalLp(MATCH_PARENT, WRAP_CONTENT),
            )
            return
        }
        column.addView(
            tonalButton(getString(R.string.manage_binds_grant_button)) {
                AllFilesAccess.openSettings(this)
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    /**
     * Start an install from a pack the user picked.
     *
     * The pack is a *rootfs*, not a distro, so one still has to be named
     * — [Distro.configure] and the package-manager stages run against it
     * afterwards (see [me.phie.tawc.install.distro.ImportedPack]). We use
     * the same default the one-tap button would, which is the distro
     * packs are built from; a pack of a different distro fails at the
     * package-manager stage with the installer's own message rather than
     * silently doing the wrong thing.
     */
    private fun launchImport(uri: android.net.Uri) {
        val distro = DistroRegistry.defaultForHost() ?: return
        val name = displayNameOf(uri)
        launchInstall(
            InstallRequest(
                id = defaultInstallId(distro),
                methodKey = EnabledMethods.keys.firstOrNull(),
                distroKey = distro.key,
                label = distro.defaultLabel,
                andoEnabled = false,
                packUri = uri.toString(),
                packName = name,
            ),
        )
    }

    /** The picker's own filename for [uri], or null when it has none. */
    private fun displayNameOf(uri: android.net.Uri): String? = try {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** The container is on disk but unusable, so offer the way out of it. */
    private fun buildFailed(inst: Installation) {
        val pad = resources.getDimensionPixelSize(R.dimen.tawc_card_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.tawc_card_gap)

        body.addView(
            TextView(this).apply {
                text = getString(R.string.setup_failed_title)
                tawcText(R.style.TextAppearance_Tawc_SectionTitle)
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )
        // The installer's own words. Monospace because they carry paths
        // and exit codes, and are worth being able to select and copy.
        inst.failure?.takeIf { it.isNotBlank() }?.let { reason ->
            body.addView(
                TextView(this).apply {
                    text = reason
                    tawcText(R.style.TextAppearance_Tawc_Caption)
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setTextColor(tawcSecondaryColor())
                },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
            )
        }

        body.addView(
            primaryButton(getString(R.string.setup_action_start_over)) { confirmStartOver(inst) },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = gap),
        )
        // The way *out* of repeating the same install. "Start over" rebuilds
        // the request that just failed — right when the failure was
        // transient, wrong when it was the request itself (a distro whose
        // mirror is unreachable, a method this device can't run, a pack that
        // turned out to be the wrong one). Without this the only offer was
        // to run it again, and picking something else meant uninstalling
        // from 容器管理 and hoping the setup screen came back.
        body.addView(
            tonalButton(getString(R.string.setup_action_back_to_setup)) { confirmBackToSetup(inst) },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    private fun buildProgress() {
        // The panel's Cancel button does nothing until its owner wires
        // it up, and this owner has to go through the confirm dialog:
        // [me.phie.tawc.ops.Operation.cancelConfirmation] for an install
        // spells out that the rootfs is wiped, which is not something a
        // stray tap should decide. Same helper LogScreenActivity uses.
        panel.onCancelClicked = { panel.boundOperation?.let { confirmAndCancel(it) } }
        body.addView(panel.view, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /**
     * Rebuild the container. tawc's own recovery model is exactly this —
     * a half-written rootfs has no supported in-place repair, so the
     * fix is to rebuild it — which is why there is no separate "retry"
     * that would extract an image over the broken tree.
     */
    private fun confirmStartOver(inst: Installation) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.setup_start_over_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.setup_action_start_over)) { _, _ ->
                restartAfterUninstall = InstallRequest(
                    id = inst.id,
                    methodKey = inst.method,
                    distroKey = inst.distro,
                    label = inst.label,
                    andoEnabled = inst.andoEnabled,
                    // A failed import must retry the *pack*. Without
                    // these it would quietly reinstall from the upstream
                    // mirror — same button, different operation.
                    packUri = inst.packUri,
                    packName = inst.sourceUrl
                        .takeIf { inst.packUri != null && it.startsWith("import:") }
                        ?.removePrefix("import:"),
                )
                InstallationService.startUninstall(this, inst.id)
            }
            .show()
    }

    /**
     * Delete the failed slot and stop there. That deletion *is* the
     * navigation: [render] derives the screen from the single record, so
     * "no record" is the setup screen — there is nothing to open, only
     * something to remove.
     *
     * Same destructive step as [confirmStartOver], minus the relaunch:
     * `restartAfterUninstall` stays null on purpose, so the user lands on
     * the picker rather than on the install that just failed.
     */
    private fun confirmBackToSetup(inst: Installation) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.setup_back_to_setup_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.setup_action_back_to_setup)) { _, _ ->
                InstallationService.startUninstall(this, inst.id)
            }
            .show()
    }

    private fun launchInstall(request: InstallRequest) {
        InstallationService.startInstall(
            this,
            request.id,
            request.methodKey,
            request.distroKey,
            request.label,
            null,
            externalBindsJson(),
            request.andoEnabled,
            null,
            request.packUri,
            request.packName,
        )
    }

    /**
     * Whether an install started from this screen carries the
     * shared-storage binds. The single derivation both callers share —
     * the card's checkbox and [externalBindsJson] — because a box that
     * said one thing while the install did another would be the worst of
     * both.
     *
     * An explicit answer wins; with none, follow the grant, which is the
     * state in which the box is offered at all.
     */
    private fun autoBindSharedStorage(): Boolean =
        Settings.autoBindSharedStorage ?: AllFilesAccess.granted()

    /**
     * Binds for an install from this screen as the service's JSON extra,
     * or null for none.
     *
     * Only ever [AllFilesAccess.sharedStorageBinds] — never the Android
     * root, which is suggested but not automatic. The custom install form
     * has its own binds row and does not come through here.
     */
    private fun externalBindsJson(): String? =
        if (autoBindSharedStorage()) {
            ExternalBind.toJsonArray(AllFilesAccess.sharedStorageBinds()).toString()
        } else {
            null
        }

    /** On-disk id for the default install: the distro's own label, slugged. */
    private fun defaultInstallId(distro: Distro): String =
        Installation.slugifyLabel(distro.defaultLabel) ?: Installation.DISTRO_ARCH

    /**
     * Point the panel at [opId], if the service has registered it yet.
     * Runs on every registry change, so an op appearing after the state
     * flipped still lands — the state write and the registration are
     * separate writes in the service.
     */
    private fun bindOp(opId: String?) {
        val op = opId?.let { OperationsRegistry.get(it) }
        if (op == null) {
            if (boundOpId != null) {
                panel.unbind()
                boundOpId = null
            }
            return
        }
        if (op.id == boundOpId) return
        // A start-over runs uninstall then install as two ops; without
        // this the second one's log would render under the first one's.
        panel.reset()
        boundOpId = op.id
        panel.bind(op)
    }

    /**
     * The container is ready, so make DSH the whole screen.
     *
     * This Activity finishes: with a single container there is nothing
     * behind it to come back to, and leaving it on the stack would make
     * "back" out of DSH land on a setup screen the user has already
     * completed.
     */
    private fun forwardToDsh() {
        if (forwarded) return
        forwarded = true
        startActivity(Intent(this, DshActivity::class.java))
        finish()
    }

    // On API 33+ foreground-service notifications (install progress, the
    // running harness) are suppressed unless POST_NOTIFICATIONS is
    // granted. Best-effort: the install runs either way, so the result is
    // deliberately not acted on.
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(perm), REQUEST_NOTIFICATIONS)
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}

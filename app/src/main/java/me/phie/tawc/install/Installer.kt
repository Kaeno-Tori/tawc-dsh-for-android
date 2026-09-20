package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.AndoBrokers
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.install.distro.BootstrapFlavor
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.ImportedPack
import me.phie.tawc.install.distro.MirrorPresets
import me.phie.tawc.install.distro.PackageBootstrap
import me.phie.tawc.install.distro.TarballBootstrap
import me.phie.tawc.install.pkgbootstrap.PackageBootstrapInstaller
import me.phie.tawc.install.util.AppOwnership
import me.phie.tawc.install.util.HumanSize
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Generic install/uninstall pipeline. The shape is the same regardless
 * of distro family (Arch today, Ubuntu/Fedora later); per-distro policy
 * lives in the [Distro] passed in.
 *
 * Stages 2-4 below are the tarball flavor's; a [PackageBootstrap]
 * dispatches them to [PackageBootstrapInstaller] instead (see
 * notes/installation.md "Bootstrap flavors") and rejoins at stage 5.
 *
 * Stages, mirroring [InstallStage]:
 *
 *   1. (state write)        — `setState(INSTALLING)` after `mkdir` of
 *                             `<distros>/<id>/`. From here the slot
 *                             exists on disk; any failure parks it in
 *                             FAILED for the user to uninstall + retry.
 *   2. DOWNLOADING          — [BootstrapCache.download] using
 *                             `distro.cacheKey` (e.g. `arch-aarch64`,
 *                             `manjaro-aarch64`) as the cache key.
 *   3. VERIFYING            — [SignatureVerifier.verify] checks the
 *                             tarball against the distro's
 *                             [BootstrapVerification] policy (PGP
 *                             detached signature for both Arch
 *                             flavours, resolved SHA-256 digest for
 *                             Manjaro/Void/Debian). On mismatch the
 *                             install fails before any byte hits the
 *                             rootfs.
 *   4. EXTRACTING           — [InstallationMethod.extractBootstrap]
 *                             (chroot → toybox tar via su; proot →
 *                             pure-Kotlin [ProotArchiveExtractor]),
 *                             honouring `bootstrap.stripPrefix`.
 *   5. CONFIGURING          — [Distro.configure] writes /etc files
 *                             (mirrorlist, pacman.conf tweaks, etc.).
 *   6. PKG_KEYRING          — [Distro.initPackageManager] (pacman-key
 *                             init / keyring populate / pacman -Syu
 *                             for Arch; apt-get update for Debian).
 *   7. PKG_INSTALL          — [Distro.installPackages] installs
 *                             the base package list.
 *   8. PROVISIONING         — [NodeProvisioner] installs the Node
 *                             runtime, npm and DSH. Not run for an
 *                             imported pack, which is already
 *                             provisioned.
 *   9. (state write)        — `setState(READY)`.
 *
 * The state-machine gate ([InstallationService]) only dispatches to
 * `install` against a `(no dir)` slot, so the rootfs is laid down on a
 * clean directory and never overlaid. `uninstall` delegates straight
 * to [RootfsCleaner.wipe]; mounts are torn down there, never here.
 */
class Installer(
    private val context: Context,
    private val store: InstallationStore,
    private val cache: BootstrapCache,
    private val distro: Distro,
    private val method: InstallationMethod,
    private val id: String,
    private val label: String? = null,
    /**
     * Dev-time caching reverse proxy. When non-null, bootstrap fetches
     * and the rootfs's package-mirror config get rewritten through it.
     * Always null for production installs — set only via the
     * `--es mirrorProxy` install intent extra (debug builds) or the
     * "Use local proxy mirror" form checkbox. See
     * `notes/cache-proxy.md`.
     */
    private val mirrorProxy: MirrorProxy? = null,
    /**
     * External-storage binds persisted into the initial metadata, so
     * they're already live for every in-rootfs step of the install
     * itself (first boot included). Resolved by [InstallationService]
     * — defaults or an explicit caller-provided list.
     */
    private val externalBinds: List<ExternalBind> = emptyList(),
    /**
     * Whether ando (notes/ando.md) is enabled for this install. Persisted
     * into the initial metadata so the broker listener + per-distro bind
     * are live for the install's own in-rootfs steps (first boot
     * included). Default false — opt-in, fail-closed.
     */
    private val andoEnabled: Boolean = false,
    /**
     * Which bootstrap flavor to install. `null` means the distro's
     * supported flavor — the only value reachable in release builds
     * ([InstallationService] rejects anything else there). The
     * uninstall path never reads this.
     */
    private val bootstrapFlavor: BootstrapFlavor? = null,
    /**
     * A rootfs the user picked from local storage, which replaces
     * whatever [Distro.resolveBootstrap] would have produced. Null for
     * every ordinary install. [distro] is still used for the stages
     * after extraction (see [ImportedPack]).
     */
    private val importedPack: ImportedPack? = null,
) {
    /** Throws on failure. Reports progress + log lines via the callbacks. */
    fun install(
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        // Stage-boundary cancel gate. `runInterruptible` translates a
        // coroutine cancel into a thread interrupt, but inner blocking
        // calls (PGP digest, tar extract, pacman) are uninterruptible
        // for chunks of seconds-to-minutes and only honour the flag
        // when they reach a poll point. This guard ensures that even
        // if a slow stage runs to completion ignoring the interrupt,
        // we tip over at the next stage boundary instead of plowing
        // through the whole pipeline.
        fun checkCancel() {
            if (Thread.interrupted()) {
                throw InterruptedIOException("install cancelled by user")
            }
        }

        // The steps this install will actually walk, in order, with the
        // labels the checklist shows. A downloaded bootstrap verifies a
        // signature; an imported pack has no upstream to check against
        // and nothing to fetch, so it lists neither step. Declaring the
        // list up front (rather than letting the UI infer it from stages
        // seen so far) is what lets the panel grey out what's still to
        // come instead of only ever showing what already happened.
        val steps: List<Pair<InstallStage, String>> = buildList {
            // Only when the mirror is actually up for grabs: one the user
            // pinned by hand is used as-is, with nothing measured (see
            // [mirrorTargets] and [packageMirrorsForPack]).
            val measures = Settings.bootstrapMirror.isBlank()
            val pack = importedPack != null
            // Where the probe sits differs by flavor, and the checklist
            // has to say where it really is: a downloaded bootstrap
            // probes *first*, because the measurement is what picks the
            // host to fetch from, while an imported pack has no host to
            // pick and probes after the extract, once there is a rootfs
            // to point pacman at. Listed in one place regardless, so
            // the order can't drift from the pipeline — but at the
            // route's own position, or the panel ticks 选择镜像源 while
            // the archive is still being unpacked.
            if (measures && !pack) {
                add(
                    InstallStage.PROBING_MIRROR to
                        context.getString(R.string.install_step_pick_mirror),
                )
            }
            if (pack) {
                add(InstallStage.DOWNLOADING to context.getString(R.string.install_step_read_pack))
            } else {
                add(
                    InstallStage.DOWNLOADING to context.getString(
                        R.string.install_step_download,
                        distro.linuxArch,
                    ),
                )
                add(InstallStage.VERIFYING to context.getString(R.string.install_step_verify))
            }
            add(InstallStage.EXTRACTING to context.getString(R.string.install_step_extract))
            if (measures && pack) {
                add(
                    InstallStage.PROBING_MIRROR to
                        context.getString(R.string.install_step_pick_mirror),
                )
            }
            add(InstallStage.CONFIGURING to context.getString(R.string.install_step_configure))
            add(InstallStage.PKG_KEYRING to context.getString(R.string.install_step_keyring))
            add(InstallStage.PKG_INSTALL to context.getString(R.string.install_step_base_packages))
            // A pack carries its own Node runtime and DSH (§9.8), so the
            // step isn't listed for one — the checklist describes the
            // install that is actually about to run, not the union of
            // what every route could do.
            if (!pack) {
                add(
                    InstallStage.PROVISIONING to
                        context.getString(R.string.install_step_provision),
                )
            }
        }
        val stepNames = steps.map { it.second }

        /**
         * Report a stage plus its position in [steps]. Every call site in
         * this class goes through here so the checklist can't drift from
         * the pipeline: adding a stage without listing it above leaves
         * [currentStep] at -1, which the panel renders as "no checklist"
         * rather than silently mislabelling the position.
         */
        fun report(stage: InstallStage, message: String, percent: Int?) {
            val index = if (stage == InstallStage.DONE) {
                steps.size
            } else {
                steps.indexOfFirst { it.first == stage }
            }
            progress(InstallProgress(
                stage = stage,
                message = message,
                percent = percent,
                steps = stepNames,
                currentStep = index,
            ))
        }

        val rootfsDir = store.rootfsDir(id)
        val rootfsPath = rootfsDir.absolutePath

        // Lay down the metadata first thing, in INSTALLING. The parent
        // dir is created with app uid (chown-fixed below for chroot)
        // so this writeText is a plain Java file write — no su needed.
        store.installationDir(id).mkdirs()
        // The chown only matters for the chroot path: a previous `su`
        // invocation could have left `<distros>/<id>/` root-owned, and
        // we then can't write `metadata.json` from app uid. Proot
        // installs are app-uid-owned end-to-end, and on a non-rooted
        // device `Su.run` would throw IOException on `ProcessBuilder
        // .start("su")` and tank the install before stage 0.
        if (method.requiresRoot) {
            AppOwnership.chownAppDirNonRecursive(store.installationDir(id))
        }
        // Stamp the app version that performed this install. The rootfs
        // is treated as immutable across app updates (see
        // notes/installation.md "Upgrade policy"), so this is the
        // version whose `Distro.configure` output the rootfs carries —
        // useful later for "if installed before vN, do X" gating.
        val appVersionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { 0L }
        // Resolve the bootstrap descriptor before writing metadata so
        // the persisted `sourceUrl` reflects the actual URL the install
        // ran against (matters for distros with dynamic resolution like
        // DebianSid, which resolves the current image layer through the
        // registry). Failure here aborts before disk state is laid down.
        val flavor = bootstrapFlavor ?: distro.supportedFlavor
        val bootstrap = importedPack
            ?: distro.resolveBootstrap(log, mirrorProxy, flavor)
        // An imported pack has no flavor of its own and is recorded as
        // `tarball`: that field describes which pipeline ran (one
        // archive vs. assembled from packages), not where the bytes came
        // from. Provenance lives in `sourceUrl`, which says
        // `import:<filename>` for these.
        // The distro declares the upstream prefix its bootstrap URLs
        // live under; a user-configured mirror swaps that prefix for
        // one their network can reach. Applied here (rather than inside
        // resolveBootstrap) so every distro gets it for free, and
        // before the metadata write so `sourceUrl` records the URL the
        // install actually used.
        val effectiveBootstrap = BootstrapMirror.apply(
            bootstrap, distro.bootstrapMirrorPrefix, Settings.bootstrapMirror,
        )
        if (effectiveBootstrap !== bootstrap) {
            log("Using bootstrap mirror: ${Settings.bootstrapMirror.trim().trimEnd('/')}")
        }
        val sourceUrl = when (effectiveBootstrap) {
            is TarballBootstrap -> effectiveBootstrap.url
            is PackageBootstrap -> effectiveBootstrap.archiveRoot
            // Not a URL — recorded so the install record says where the
            // rootfs actually came from rather than showing an upstream
            // URL this install never touched.
            is ImportedPack -> "import:${effectiveBootstrap.displayName}"
        }

        store.save(
            Installation(
                id = id,
                distro = distro.key,
                arch = distro.androidAbi,
                method = method.key,
                installedAtMillis = System.currentTimeMillis(),
                sourceUrl = sourceUrl,
                state = Installation.State.INSTALLING,
                installedAtAppVersionCode = appVersionCode,
                label = label,
                externalBinds = externalBinds,
                andoEnabled = andoEnabled,
                bootstrapFlavor = flavor.id,
                packUri = (effectiveBootstrap as? ImportedPack)?.uri,
            )
        )
        // Bring the ando broker listener up (or down) to match the
        // freshly-written metadata, so the install's own in-rootfs
        // steps can use ando when enabled. See notes/ando.md.
        AndoBrokers.refresh(context)

        // Stages 1-3 diverge by flavor: the tarball path downloads/
        // verifies/extracts one archive; the packages path assembles
        // the rootfs from signed repo metadata + debootstrap. Both
        // rejoin at the flavor-agnostic configure step below.
        //
        // [packageMirrors] carries the mirrors this install proved out
        // on this network into `configure`, so the package manager gets
        // the same answer the bootstrap download just got rather than
        // whichever host the user's route happens to hate — the 11 KB/s
        // `pacman -Syu` of §9.6's write-up. Which mirrors those are is
        // decided *here*, per route, and handed to `configure` as a
        // parameter: the flavors below differ in whether a hand-pinned
        // mirror is even meaningful for them, and that is a question
        // only the route can answer.
        val packageMirrors: List<String> = when (effectiveBootstrap) {
            // The **unmirrored** descriptor, deliberately: re-rooting is
            // [installTarballBootstrap]'s own job (through
            // [mirrorTargets]), and it can only recognise a pinned base
            // if it is looking at the upstream URL — a descriptor that
            // already points at the mirror no longer sits under
            // [Distro.bootstrapMirrorPrefix], so the pin reads as
            // unusable and the install quietly measures instead. What
            // that cost: no base came back for `configure`, which is why
            // `configure` used to reach for the setting itself.
            is TarballBootstrap ->
                installTarballBootstrap(bootstrap as TarballBootstrap, rootfsPath, ::checkCancel, ::report, log)
            is PackageBootstrap -> {
                PackageBootstrapInstaller(
                    context, store, cache, distro, method, id, effectiveBootstrap, mirrorProxy,
                ).install(::checkCancel, progress, log)
                // The bootstrap was re-rooted onto the pin above, so the
                // package manager has to follow it there — otherwise the
                // user pins a mirror because upstream is unreachable and
                // gets a fast 800 MB bootstrap followed by a `pacman -Syu`
                // against that same unreachable upstream.
                pinnedMirrors()
            }
            is ImportedPack -> {
                installImportedPack(effectiveBootstrap, rootfsPath, ::checkCancel, ::report, log)
                packageMirrorsForPack(::checkCancel, ::report, log)
            }
        }

        checkCancel()
        // Stage 3: configure. /etc files via Distro.configure. The
        // chroot-entry mechanics (mounts, bind-table, setsid, exec)
        // live entirely in [InstallationMethod.startInside] now —
        // there's nothing to materialise on disk between calls.
        report(InstallStage.CONFIGURING, context.getString(R.string.install_progress_configuring_chroot), null)
        distro.configure(method, rootfsPath, mirrorProxy, log, packageMirrors)
        // Device locale + timezone, on every flavor including imported
        // packs: which zone a container is in, and whether its tools
        // speak UTF-8, are properties of *this* device rather than of the
        // rootfs that happens to be on disk. Distro-agnostic on purpose —
        // it is not the distro's config to own — which is why it is not
        // inside [me.phie.tawc.install.distro.Distro.configure].
        Localization.configure(method, rootfsPath, log)
        // Lay down everything the app ships per-rootfs (libhybris into
        // /usr/lib/hybris, the glvnd vendor JSON, …) as real files via
        // [TawcInstaller]. Must follow distro.configure (which may
        // create the /usr tree we're writing into) and precede the
        // package-manager bootstrap so any pacman scriptlet that
        // touches our paths sees a coherent state. Idempotent: the
        // (id, app-stamp) pair gets recorded so the same call from
        // [me.phie.tawc.TawcApplication.onCreate] no-ops on subsequent
        // app starts until an APK upgrade bumps the stamp.
        TawcInstaller.installInto(context, store, id, log)

        checkCancel()
        // Stage 4: package-manager bootstrap. State stays INSTALLING
        // throughout — if either pacman invocation fails the service
        // wraps it as FAILED and the only recovery is uninstall +
        // install again.
        report(InstallStage.PKG_KEYRING, context.getString(R.string.install_progress_initializing_package_manager), null)
        distro.initPackageManager(method, rootfsPath, log)

        checkCancel()
        // Stage 5: install base packages.
        report(
            InstallStage.PKG_INSTALL,
            context.getString(R.string.install_progress_installing_base_packages),
            null,
        )
        distro.installPackages(method, rootfsPath, distro.basePackages, log)
        checkCancel()
        // Stage 6: the Node runtime, npm, and DSH itself. Without it the
        // install "succeeds" and every harness launch dies at 127, which
        // is the one gap that leaves the user with no in-app way out —
        // §9.7. A pack already carries all of it, so it is skipped there
        // rather than re-downloaded on top of one.
        if (importedPack == null) {
            NodeProvisioner(context, distro, method, rootfsPath)
                .install(::checkCancel, ::report, log)
        }

        // All stages succeeded — flip to READY. From this point the
        // gate refuses install and only allows uninstall.
        store.setState(id, Installation.State.READY)
        // …and wire the container's Vulkan the way the app has been running
        // it all along. The spawn env comes from the saved pick (or the
        // build's default when nobody picked), but the rootfs's own
        // `/usr/lib` is only written by the provisioning scripts — so
        // without this a fresh container has no loader for the programs
        // started *inside* it, and the fix used to be "go to settings and
        // re-pick the accelerator". See [VulkanProvisionOp.reconcile], which
        // is idempotent and swallows its own failures.
        try {
            VulkanProvisionOp.reconcile(context)
        } catch (t: Throwable) {
            log("vulkan provisioning failed: ${t.message ?: t.javaClass.simpleName}")
        }
        report(InstallStage.DONE, context.getString(R.string.install_progress_installed), null)
    }

    /**
     * Tarball flavor, stages 1-3: download the bootstrap tarball into
     * the cache, integrity-check it against the distro's
     * [BootstrapVerification] policy, extract onto the fresh rootfs.
     *
     * [bootstrap] is the distro's **own** descriptor, not one already
     * re-rooted onto the user's mirror: re-rooting happens here, so that
     * a pinned mirror still looks like what it is. See the call site.
     *
     * **Which host** is decided here, per install, by measuring this
     * network ([MirrorProbe]) — and the targets are walked on failure,
     * so a mirror that dies mid-download costs a retry rather than the
     * install. A mirror the user pinned in the install form skips the
     * measurement entirely: an explicit choice is not a guess to be
     * improved, and it's the only way to use a mirror this build has
     * never heard of.
     *
     * @return the mirror bases the package manager should use, best
     *   first. Only sources this run actually *measured*, plus a pinned
     *   one — which is not a measurement but is an instruction. A host
     *   that was merely declared is worse evidence than the distro's own
     *   mirrorlist, which §9.6 tuned by hand.
     */
    private fun installTarballBootstrap(
        bootstrap: TarballBootstrap,
        rootfsPath: String,
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ): List<String> {
        val targets = mirrorTargets(bootstrap, checkCancel, report, log)
        var verified: File? = null
        var chosen: MirrorProbe.Target? = null
        var lastError: IOException? = null
        for ((index, target) in targets.withIndex()) {
            checkCancel()
            if (targets.size > 1) log("[mirror] source ${index + 1}/${targets.size}: ${target.label}")
            try {
                // Every target for this path is a re-rooted
                // [TarballBootstrap]: the input is one, and
                // [BootstrapMirror.apply] preserves the shape.
                verified = fetchVerified(target.bootstrap as TarballBootstrap, checkCancel, report, log)
                chosen = target
                break
            } catch (e: IOException) {
                // A cancelled install must not walk the rest of the
                // list: `checkCancel` and [Downloader] both signal a
                // cancel as an IOException with the thread interrupt
                // still set, which is exactly what distinguishes it from
                // a dead mirror's timeout.
                if (Thread.currentThread().isInterrupted) throw e
                lastError = e
                log("[mirror] ${target.label} failed: ${e.message}")
                // A half-written or poisoned cache entry would make the
                // next source skip the download entirely (the size check
                // matches), so it has to go before we move on.
                cache.evict(distro.cacheKey, bootstrap.format)
            }
        }
        val cacheFile = verified
            ?: throw (lastError ?: IOException("no source could serve the bootstrap"))

        // The record was written before the probe — it has to exist
        // while the install runs — so correct its URL now that we know
        // which host the network actually picked. Otherwise the
        // completion summary would name a host this install never
        // touched.
        if (chosen?.base != null) {
            val used = (chosen.bootstrap as TarballBootstrap).url
            log("[mirror] using $used")
            store.update(id) { it.copy(sourceUrl = used) }
        }

        checkCancel()
        // Stage 3: extract. The rootfs dir does not exist yet — the
        // gate only invokes install on a `(no dir)` slot — so the
        // method's extractor lays everything onto a fresh tree.
        // Neither extractor wipes; never has reason to. For zstd
        // bootstraps we pass the cache-owned FIFO path (used by the
        // chroot path; proot ignores it and decompresses via
        // zstd-jni) so all `cache/install/` files have one owner.
        extractInto(
            archive = cacheFile,
            format = bootstrap.format,
            stripPrefix = bootstrap.stripPrefix,
            rootfsPath = rootfsPath,
            report = report,
            log = log,
        )

        // The winner first (it's the one we know works right now),
        // then the other measured sources in throughput order. Capped:
        // pacman walks this list in order, so a long tail buys nothing
        // but slower failure.
        return buildList {
            chosen?.base?.let { add(it) }
            targets.filter { it.rateBps > 0 }.mapNotNull { it.base }.forEach {
                if (it !in this) add(it)
            }
        }.take(PACKAGE_MIRROR_COUNT)
    }

    /**
     * Where this install may fetch the bootstrap from, best first.
     *
     * The candidate pool is [MirrorPresets] composed with the distro's
     * own mirror layout — never a hardcoded list here, so a mirror
     * added for the install form is measured automatically.
     */
    private fun mirrorTargets(
        bootstrap: TarballBootstrap,
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ): List<MirrorProbe.Target> {
        val pinned = BootstrapMirror.normalise(Settings.bootstrapMirror)
        if (pinned.isNotEmpty()) {
            val single = MirrorProbe.pinned(bootstrap, distro.bootstrapMirrorPrefix, pinned)
            if (single.isNotEmpty()) return single
            // Fall through: the setting names a base this distro's
            // bootstrap can't be re-rooted onto, so honouring it is
            // impossible. Measuring is strictly better than silently
            // downloading from upstream at 6 KB/s.
            log("[mirror] ${distro.key} cannot be mirrored onto $pinned; measuring instead")
        }
        checkCancel()
        report(
            InstallStage.PROBING_MIRROR,
            context.getString(R.string.install_progress_probing_mirrors),
            null,
        )
        return MirrorProbe.order(
            bases = distro.bootstrapMirrorPath?.let { MirrorPresets.basesFor(it) } ?: emptyList(),
            bootstrap = bootstrap,
            prefix = distro.bootstrapMirrorPrefix,
            log = log,
        )
    }

    /**
     * The mirror the user pinned by hand, as a package-mirror base, or
     * empty when nothing is pinned.
     *
     * Only the routes that install **the distro the pin was typed for**
     * may use this: the pin arrives through the custom install's form,
     * which names one base for one distro. Both of those routes
     * ([TarballBootstrap] and [PackageBootstrap]) re-root their own
     * download onto it, so the package manager following it there is the
     * same decision, not a second guess.
     */
    private fun pinnedMirrors(): List<String> =
        listOfNotNull(BootstrapMirror.normalise(Settings.bootstrapMirror).takeIf { it.isNotEmpty() })

    /**
     * Where an imported pack's *package manager* may fetch from, best
     * first — the counterpart of [mirrorTargets] for the flavor that
     * downloads nothing.
     *
     * There is no bootstrap archive to race, so the candidate pool is
     * the repository URLs the distro would have pacman read
     * ([Distro.packageProbeUrls]): the measurement is still owed, it
     * just has to be made against the thing pacman will actually fetch.
     * Without it the pack installs in seconds and then runs `pacman
     * -Syu` against ALARM's six US/EU hosts — the 11 KB/s of §9.6,
     * arriving by the one route that section didn't cover.
     *
     * **A hand-pinned mirror is deliberately not consulted here**,
     * unlike in [pinnedMirrors]. The pin names a base for the distro the
     * install form was showing, and a pack arrives with a distro of its
     * own — one the form never displayed. A base re-rooted onto the
     * wrong distro's directory is a mirrorlist that 404s on every
     * package, and it would be reached through a screen that says
     * nothing about a mirror at all. Measuring is both the honest
     * default and, on the evidence of §9.6, the better answer anyway.
     *
     * @return the mirror bases the package manager should use, best
     *   first. Only sources this run actually *measured*: a host that was
     *   merely declared is worse evidence than the distro's own
     *   mirrorlist, which §9.6 tuned by hand. Empty when nothing was
     *   measured, which leaves `configure` with the distro's list.
     */
    private fun packageMirrorsForPack(
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ): List<String> {
        checkCancel()
        report(
            InstallStage.PROBING_MIRROR,
            context.getString(R.string.install_progress_probing_mirrors),
            null,
        )
        val bases = distro.bootstrapMirrorPath?.let { MirrorPresets.basesFor(it) } ?: emptyList()
        val sources = distro.packageProbeUrls(bases)
        if (sources.isEmpty()) return emptyList()

        return MirrorProbe.orderSources(sources, log)
            .filter { it.rateBps > 0 }
            .map { it.base }
            .distinct()
            .take(PACKAGE_MIRROR_COUNT)
    }

    /**
     * Download [bootstrap] into the cache and verify it.
     *
     * One evict-and-retry against the same source is kept from the
     * original loop: the cached tarball survives across
     * uninstall+reinstall cycles at a path keyed by arch, and
     * [Downloader]'s "skip when the size matches Content-Length" check
     * happily reuses a corrupt blob whose on-wire size happens to match
     * — without the retry, one bad download (or a stale entry served by
     * a dev cache proxy) would stick forever. Twice is the limit: after
     * that the source is a better explanation than the bytes, and the
     * caller has another source to try.
     */
    private fun fetchVerified(
        bootstrap: TarballBootstrap,
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ): File {
        // Funnel the bootstrap fetch through the dev-time mirror cache
        // when set. The proxy URL is only what the wire request goes to;
        // metadata.json's [Installation.sourceUrl] records the canonical
        // URL, so the install record reads sensibly across runs
        // with/without the proxy.
        val url = mirrorProxy?.wrap(bootstrap.url) ?: bootstrap.url
        var evicted = false
        while (true) {
            checkCancel()
            // Stage 1: download. BootstrapCache owns the cache dir
            // entirely — filename scheme, freshness mtime, TTL janitor —
            // so the installer just hands it (cacheKey, url, format).
            report(
                InstallStage.DOWNLOADING,
                context.getString(R.string.install_progress_downloading_arch_bootstrap, distro.linuxArch),
                null,
            )
            log("download: $url" + if (evicted) " (after evicting a bad cache entry)" else "")
            val cf = cache.download(distro.cacheKey, url, bootstrap.format) { read, total ->
                val pct = total?.let { ((read * 100) / it).toInt().coerceIn(0, 100) }
                val totalLabel = total?.let { HumanSize.format(it) }
                    ?: context.getString(R.string.distro_info_unknown)
                report(
                    InstallStage.DOWNLOADING,
                    context.getString(
                        R.string.install_progress_downloading_bootstrap,
                        HumanSize.format(read),
                        totalLabel,
                    ),
                    pct,
                )
            }

            checkCancel()
            // Stage 2: integrity check. Verify the just-downloaded
            // tarball against the distro's [BootstrapVerification] before
            // any byte hits the rootfs. Throws on mismatch / missing
            // signature key / forged blob / a leftover
            // ResolvedAtInstallTime placeholder — and parks the install
            // in FAILED upstream so the user can uninstall + retry from
            // a clean tree. See notes/installation.md "Bootstrap
            // integrity" for what each distro declares.
            report(
                InstallStage.VERIFYING,
                context.getString(R.string.install_progress_verifying_bootstrap),
                null,
            )
            log("verify: ${bootstrap.verification::class.simpleName}")
            try {
                SignatureVerifier.verify(context, cf, bootstrap.verification, mirrorProxy)
                return cf
            } catch (e: IOException) {
                if (evicted) {
                    if (mirrorProxy != null) {
                        log(
                            "verify: failed twice through the dev cache proxy at " +
                                "${mirrorProxy.base} — its cached entries (tarball + " +
                                "digests) appear out of sync with each other. " +
                                "Ask the user to clear build/cache-proxy/cache/ and retry.",
                        )
                    }
                    throw e
                }
                log("verify: failed (${e.message}); evicting local cache and retrying once")
                cache.evict(distro.cacheKey, bootstrap.format)
                evicted = true
            }
        }
    }

    /**
     * Imported-pack stages 1-2: bring the user's file into the cache,
     * then extract it.
     *
     * There is no download and no signature check — see [ImportedPack]
     * for why the second is an explicit property of the type rather
     * than a step someone forgot.
     *
     * The file is copied into [BootstrapCache] instead of being
     * extracted straight from the URI so that everything downstream —
     * the shared FIFO path, eviction, the TTL janitor, a re-install
     * after a failure — sees exactly what a fetched bootstrap would
     * have left behind. The copy lands via `.part` and is renamed only
     * once complete, because a cancelled import that left a short file
     * at the real name would be extracted on the next attempt as if it
     * were whole.
     */
    private fun installImportedPack(
        pack: ImportedPack,
        rootfsPath: String,
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ) {
        val dest = cache.pathFor(distro.cacheKey, pack.format)
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete()

        // Stage label reuses DOWNLOADING: it is the stage whose meaning
        // is "getting the archive onto this device, don't cancel yet",
        // and the label below is what the user actually reads.
        report(
            InstallStage.DOWNLOADING,
            context.getString(R.string.install_progress_reading_pack, pack.displayName),
            null,
        )
        log("import: ${pack.displayName} -> ${dest.name}")

        // SIZE, when the provider knows it, is only used for the
        // progress readout — a provider that doesn't report it still
        // copies fine, it just shows bytes instead of a percentage.
        val total = try {
            context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(pack.uri), "r")
                ?.use { it.length.takeIf { len -> len > 0 } }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        var read = 0L
        try {
            val input = context.contentResolver.openInputStream(android.net.Uri.parse(pack.uri))
                ?: throw IOException("cannot open ${pack.displayName}")
            input.use { source ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("import cancelled by user")
                        }
                        val n = source.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = total?.let { ((read * 100) / it).toInt().coerceIn(0, 100) }
                        report(
                            InstallStage.DOWNLOADING,
                            context.getString(
                                R.string.install_progress_reading_pack_progress,
                                HumanSize.format(read),
                                total?.let { HumanSize.format(it) }
                                    ?: context.getString(R.string.distro_info_unknown),
                            ),
                            pct,
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        if (read == 0L) {
            tmp.delete()
            throw IOException("${pack.displayName} is empty")
        }
        dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("failed to move the imported pack into the cache")
        }
        log("import: ${HumanSize.format(read)} read")

        checkCancel()
        extractInto(
            archive = dest,
            format = pack.format,
            // A pack is a rootfs, so it is flat by construction — the
            // build script tars `-C rootfs .` and nothing else. A
            // wrapped tarball (upstream Arch x86's `root.x86_64/`) is
            // not a pack and is not accepted as one.
            stripPrefix = null,
            rootfsPath = rootfsPath,
            report = report,
            log = log,
        )
    }

    /**
     * Stage 3 for both archive-based flavors. Split out so the
     * downloaded and the imported path can't drift: they differ only in
     * where the archive came from, and the FIFO/extractor mechanics are
     * identical afterwards.
     */
    private fun extractInto(
        archive: File,
        format: BootstrapFormat,
        stripPrefix: String?,
        rootfsPath: String,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ) {
        report(
            InstallStage.EXTRACTING,
            context.getString(R.string.install_progress_extracting_rootfs),
            null,
        )
        log("extract: ${archive.name} -> $rootfsPath (strip=$stripPrefix, method=${method.key})")
        method.extractBootstrap(
            tarball = archive,
            rootfs = rootfsPath,
            format = format,
            stripPrefix = stripPrefix,
            tempFifo = cache.tempFifoFor(distro.cacheKey),
        ) { line ->
            log("tar: $line")
        }
    }

    /**
     * Permanently remove [id]: state → UNINSTALLING, [RootfsCleaner.wipe],
     * then the directory (including metadata.json) is gone. On a
     * `(no dir)` slot this is a no-op. Throws on wipe failure; the
     * service wraps as `FAILED` so a subsequent uninstall can retry.
     *
     * No [Distro] is needed — the wipe engine is distro-agnostic,
     * parameterised only by the method's capability flags.
     */
    fun uninstall(
        progress: (InstallProgress) -> Unit,
        log: (String) -> Unit,
    ) {
        if (!store.installationDir(id).exists()) {
            progress(InstallProgress(InstallStage.DONE, context.getString(R.string.install_progress_nothing_to_delete)))
            return
        }
        store.setState(id, Installation.State.UNINSTALLING)

        // The UNMOUNTING stage is meaningful for chroot (real bind
        // mounts to tear down). Proot has no global mounts, just an
        // app-uid recursive delete — but the stage rolls past quickly,
        // and the install pipeline / UI is structured around these
        // labels, so we keep both for symmetry.
        progress(InstallProgress(InstallStage.UNMOUNTING, context.getString(R.string.install_progress_unmounting_chroot)))
        progress(InstallProgress(InstallStage.DELETING, context.getString(R.string.install_progress_deleting_rootfs)))
        RootfsCleaner.wipe(store, id, log)

        // The wipe removed the ando dir; drop this install's broker
        // listener and any test-mode override now that its metadata is
        // gone, so neither survives into a reinstall of the same id.
        // See notes/ando.md.
        InstallationStore.clearAndoOverride(id)
        AndoBrokers.refresh(context)

        progress(InstallProgress(InstallStage.DONE, context.getString(R.string.install_progress_deleted)))
    }

    private companion object {
        /**
         * Mirror bases written into the rootfs's package-manager config.
         * pacman walks `Server =` lines in order, so the value is in the
         * first couple (skip past a stale mirror, keep going); a longer
         * tail only makes a real failure slower to surface.
         */
        const val PACKAGE_MIRROR_COUNT = 3
    }
}

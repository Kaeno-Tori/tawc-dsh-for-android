package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import java.io.IOException

/**
 * Turns a freshly-installed rootfs into one that can actually run the
 * harness: install the Node runtime and npm, then DSH itself, then pnpm.
 *
 * Why this step exists: every other stage of an install stops at "a
 * Linux userland is on disk". Nothing in a distro's base package set is
 * Node, and [me.phie.tawc.dsh.DshService] only ever *looks for* `dsh` —
 * so without this the one-tap install finishes by handing the user a
 * harness that dies at `exit=127`, with no in-app way out
 * (`TAWC_DSH_DESIGN.md` §9.7, which records the manual recipe this
 * automates).
 *
 * **The npm registry is measured, not assumed.** The candidates in
 * [NpmRegistries] are all real registries; which ones this network can
 * use is a separate question, and one only measurement answers —
 * measured on this device: the official registry at 32 KB/s (488
 * packages would never finish), npmmirror advertising a version whose
 * tarball it doesn't serve, Huawei's packument for some
 * `@deepseek-ai/…` packages resolving to no versions at all. So the
 * order comes from [MirrorProbe.orderSources], the same two-phase
 * measurement the distro mirrors get.
 *
 * **Measurement alone would not be enough**, which is why the loop
 * below walks the whole ordered list rather than taking the winner: a
 * registry can win the probe and still be missing the *tarball* it
 * advertises, and npm only finds that out when it asks for it. A
 * registry that can't serve this version costs a retry, not the
 * install — the same "walk the candidates on failure" shape the
 * bootstrap download uses, for the same reason.
 *
 * A data pack skips this entirely: a pack is by definition already
 * provisioned (`TAWC_DSH_DESIGN.md` §9.8), and re-downloading the
 * runtime and DSH on top of one would give back the whole download the
 * pack exists to avoid.
 */
internal class NodeProvisioner(
    private val context: Context,
    private val distro: Distro,
    private val method: InstallationMethod,
    private val rootfsPath: String,
) {

    /** Throws on failure. Reports its own stage messages via [report]. */
    fun install(
        checkCancel: () -> Unit,
        report: (InstallStage, String, Int?) -> Unit,
        log: (String) -> Unit,
    ) {
        checkCancel()
        report(
            InstallStage.PROVISIONING,
            context.getString(R.string.install_progress_installing_runtime),
            null,
        )
        distro.installPackages(method, rootfsPath, distro.runtimePackages, log)

        checkCancel()
        report(
            InstallStage.PROVISIONING,
            context.getString(R.string.install_progress_installing_dsh),
            null,
        )
        installDsh(MirrorProbe.orderSources(NpmRegistries.probeSources(PACKAGE), log), log)
    }

    /**
     * Install [PACKAGE] (and pnpm) from the first registry that can
     * serve it, and leave that registry in the rootfs's `.npmrc`.
     */
    private fun installDsh(registries: List<MirrorProbe.Source>, log: (String) -> Unit) {
        var lastError: IOException? = null
        for ((index, registry) in registries.withIndex()) {
            // Every candidate after the first is a fallback, so say
            // which one we're on — otherwise a slow install that moves
            // on after an invisible failure looks like one long hang.
            log("[npm] registry ${index + 1}/${registries.size}: ${registry.base}")
            try {
                installFrom(registry.base, log)
                return
            } catch (e: IOException) {
                // A cancelled install must not walk the rest of the
                // list: the interrupt is still set on a cancel, which is
                // exactly what distinguishes it from a registry that
                // timed out.
                if (Thread.currentThread().isInterrupted) throw e
                lastError = e
                log("[npm] ${registry.label} failed: ${e.message}")
            }
        }
        throw lastError ?: IOException("no npm registry could provide $PACKAGE")
    }

    /**
     * One registry, one transaction: install, then verify, then pin.
     *
     * The verification is not redundant with npm's exit code. A
     * registry answering a packument it can't back with tarballs makes
     * npm fail *loudly* (that is the observed `ETARGET`), but a global
     * install that silently places no binary would be indistinguishable
     * from success at this level — and the failure it would cause
     * (harness exits 127 on every launch) is far away from here. So the
     * last thing the script does is prove `dsh` runs.
     *
     * `--allow-scripts` is load-bearing, not a convenience: npm 12
     * blocks install-time lifecycle scripts by default, and the blocked
     * ones here are the spawn helpers DSH runs subprocesses *through*.
     * Left blocked, this installs successfully and produces a DSH whose
     * bash tool cannot start anything — see §9.7.
     */
    private fun installFrom(registry: String, log: (String) -> Unit) {
        val res = method.runInside(
            rootfsPath,
            """
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            set -e
            mkdir -p /root
            npm install -g --registry=$registry \
              --allow-scripts=${ALLOW_SCRIPTS.joinToString(",")} \
              --fetch-retries=5 --fetch-retry-maxtimeout=60000 \
              --no-fund --no-audit \
              $PACKAGE@$VERSION pnpm
            command -v dsh >/dev/null
            dsh --version
            # Left behind so the *next* npm/pnpm invocation — DSH's
            # plugin manager installing a bundle, `npx` from the
            # terminal — starts from a registry this network has already
            # proved rather than npm's default. Written only once the
            # registry has been shown to work.
            printf 'registry=%s\n' "$registry" > /root/.npmrc
            """.trimIndent(),
            onLine = { log("npm: $it") },
        )
        if (!res.ok) {
            throw IOException("npm install from $registry failed (exit=${res.exitCode})")
        }
    }

    private companion object {
        /**
         * The DSH release this build installs.
         *
         * Pinned rather than `latest`, because the app is written
         * against one known CLI surface — the launcher argv
         * (`dsh web --no-open --port`), `DSH_PERMISSION_MODE`, and the
         * WebView bridge of §9.4 — and a release that renames a flag
         * would then break *new* installs silently, on the day it
         * ships, with no app update involved. Same version the shipped
         * data pack carries, so both routes hand the user the same
         * thing.
         */
        const val PACKAGE = "@deepseek-ai/dsh"
        const val VERSION = "0.1.6-alpha.2"

        /**
         * The only packages npm may run install scripts for. See
         * [installFrom] — the default (block them all) is what makes
         * this list necessary rather than a nicety.
         *
         * `pnpm` is on it because it ships a `install.js`, and npm
         * prints a five-line warning about every package it blocked:
         * leaving it off doesn't break pnpm (measured: `pnpm --version`
         * works with the script blocked), it just makes a clean install
         * log look like a failed one.
         */
        val ALLOW_SCRIPTS = listOf(
            "@deepseek-ai/dsh-subprocess-local",
            "koffi",
            "node-pty",
            "@google/genai",
            "protobufjs",
            "pnpm",
        )
    }
}

/**
 * The npm registries [NodeProvisioner] may install DSH from, in
 * declared order.
 *
 * Curated, not scraped — the same policy
 * [me.phie.tawc.install.distro.MirrorPresets] follows, for the same
 * reason: a probe can only rank the URLs it is handed, and a candidate
 * that is fast but isn't what it claims to be is worse than no
 * candidate at all. Order is "the Chinese mirrors first, upstream
 * last": upstream is the one registry every package is certainly on,
 * which makes it the right *last resort*, and it was also the slowest
 * measured here (32 KB/s), which makes it the wrong first choice.
 *
 * The list is short on purpose. [NodeProvisioner] walks it in order and
 * each extra candidate is another chance to hit a registry that fails
 * slowly — but too short a list has nowhere to go when the fast one is
 * broken, and "the fast one is broken" is not hypothetical: it is what
 * npmmirror did to alpha.2's tarball.
 */
internal object NpmRegistries {
    private val ALL = listOf(
        "https://registry.npmmirror.com" to "npmmirror",
        "https://mirrors.cloud.tencent.com/npm" to "tencent",
        "https://mirrors.huaweicloud.com/repository/npm" to "huawei",
        "https://registry.npmjs.org" to "npmjs",
    )

    /**
     * One probe candidate per registry, each pointed at the packument
     * of [packageName] — the exact document npm resolves a version
     * range against.
     *
     * That URL rather than the registry root, because the two failure
     * modes that matter are both "this registry doesn't have *this*
     * package": a 404 there drops the candidate in the probe, and a
     * packument whose body resolves to no versions still costs only the
     * probe rather than a full install attempt.
     *
     * The scope separator is percent-encoded, as the registry API
     * requires — `/@scope%2Fname`, not `/@scope/name`.
     */
    fun probeSources(packageName: String): List<MirrorProbe.Source> {
        val path = packageName.replace("/", "%2F")
        return ALL.map { (base, label) ->
            MirrorProbe.Source(base = base, label = label, url = "$base/$path")
        }
    }
}

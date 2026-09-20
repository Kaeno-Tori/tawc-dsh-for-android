package me.phie.tawc.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Unpacking of the driver assets tawc ships inside the APK into the
 * app's `filesDir`, which is where the [me.phie.tawc.install]
 * InstallProviders walk from when copying them into each rootfs.
 *
 * **Deliberately display-free.** All of this used to live in
 * `CompositorService`'s companion object, because libhybris was
 * historically only ever loaded to render Linux GUI programs. DSH needs
 * the compute half of these assets with no compositor anywhere in the
 * picture (`DshService` enters the rootfs directly — TAWC_DSH_DESIGN.md
 * §11), so the extraction had to survive the compositor's removal.
 * Nothing here may grow a compositor or Wayland dependency.
 *
 * Whatever stays in this file must be something the DSH path needs.
 * `gfxstream` / Mesa-Zink / Xwayland extraction was left behind in
 * `CompositorService` for exactly that reason.
 */
internal object TawcAssets {
    private const val TAG = "tawc"

    /** Lock for [ensureLibhybrisExtracted] — see its KDoc. */
    private val LIBHYBRIS_EXTRACT_LOCK = Any()

    /** Lock for [ensureTurnipExtracted] — see its KDoc. */
    private val TURNIP_EXTRACT_LOCK = Any()

    /**
     * Stamp value written to `<destDir>/.version` to gate
     * re-extraction. Combines `longVersionCode` and `lastUpdateTime`
     * (epoch ms): the former gives a human-readable bump on real
     * version changes, the latter ensures `adb install -r` of the
     * same `versionCode` still triggers re-extraction (the system
     * bumps `lastUpdateTime` on every reinstall). Without the
     * `lastUpdateTime` component, devs iterating on libhybris / Turnip
     * without bumping `versionCode` silently run against the previous
     * build's binary.
     */
    fun currentExtractStamp(context: Context): String {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return "v0@0"
        }
        return "v${info.longVersionCode}@${info.lastUpdateTime}"
    }

    /** Asset names whose "nothing shipped for this ABI" line has
     *  already been logged in this process. The `ensure*Extracted`
     *  helpers run per spawn (see [isStampStale]), and on a build/ABI
     *  that ships no asset the miss is permanent — one line is the
     *  whole story. */
    private val loggedMissingAssets: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun logMissingAssetOnce(name: String, message: String) {
        if (loggedMissingAssets.add(name)) Log.i(TAG, message)
    }

    /**
     * Returns true if `<destDir>/.version` is missing or doesn't
     * match [currentStamp]. Logs the comparison when it decides to
     * extract, so a stale extract is one logcat line away if this
     * ever misfires again. The up-to-date case stays silent: since
     * [me.phie.tawc.install.TawcrootMethod.assetBinds] calls the
     * `ensure*Extracted` helpers to guarantee its bind sources,
     * this runs a few times per spawn and logging it would be pure
     * noise (the on-disk stamp equals the current one by
     * definition, so there's nothing to report).
     */
    fun isStampStale(name: String, destDir: File, currentStamp: String): Boolean {
        val versionFile = File(destDir, ".version")
        val onDisk = if (versionFile.exists()) versionFile.readText().trim() else "missing"
        val stale = onDisk != currentStamp
        if (stale) {
            Log.i(TAG, "$name extract: stamp=$currentStamp on-disk=$onDisk; extracting")
        }
        return stale
    }

    /**
     * Atomically swap [stagingDir] into place at [destDir]. If
     * [destDir] already has contents, move it aside via `rename`
     * (which works at the directory-entry level, regardless of
     * whether the tree has un-walkable / un-deletable entries) and
     * delete it as a best-effort cleanup afterwards.
     *
     * `File.deleteRecursively()` followed by `renameTo` was the
     * obvious encoding, but `deleteRecursively` returns Boolean
     * silently — when it can't traverse into a legacy symlink tree
     * (e.g. SELinux quirks, fs context oddities) it leaves the
     * destDir half-populated and the subsequent rename fails with
     * EEXIST / ENOTEMPTY. The rename-aside path doesn't depend on
     * walking the contents.
     */
    fun atomicReplaceDir(stagingDir: File, destDir: File) {
        val asideDir = File(destDir.parentFile, "${destDir.name}.old")
        asideDir.deleteRecursively()
        if (destDir.exists() && !destDir.renameTo(asideDir)) {
            throw java.io.IOException(
                "Could not move $destDir aside to $asideDir prior to swap " +
                    "(SELinux denial? cross-fs?). Try clearing app storage."
            )
        }
        if (!stagingDir.renameTo(destDir)) {
            // Best-effort restore so we don't leave an empty destDir
            // that the next start would treat as "extracted".
            asideDir.renameTo(destDir)
            throw java.io.IOException(
                "rename $stagingDir -> $destDir failed; refusing to fall back to a " +
                    "symlink-flattening copy. Try clearing app storage and reinstalling."
            )
        }
        // The aside dir may still contain legacy entries we couldn't
        // delete via Java; that's fine — the rename has already
        // taken effect, and a stuck `.old` only costs disk.
        asideDir.deleteRecursively()
    }

    /**
     * Walk a tar asset into [stagingDir], preserving symlinks.
     *
     * The containment guard is defence-in-depth against a build that
     * ever produces a tar entry with `..` in its path. The assets are
     * built from our own cross-compiles so it shouldn't fire, but
     * [me.phie.tawc.install.ProotArchiveExtractor] does the same check;
     * keep both consistent.
     */
    private fun extractTarAsset(context: Context, assetPath: String, stagingDir: File) {
        val stagingReal = stagingDir.canonicalFile
        val stagingPrefix = stagingReal.absolutePath + File.separator
        context.assets.open(assetPath).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val outFile = File(stagingDir, entry.name).canonicalFile
                    val abs = outFile.absolutePath
                    if (abs != stagingReal.absolutePath && !abs.startsWith(stagingPrefix)) {
                        throw java.io.IOException("$assetPath tar entry escapes staging: ${entry.name}")
                    }
                    when {
                        entry.isDirectory -> outFile.mkdirs()
                        entry.isSymbolicLink -> {
                            outFile.parentFile?.mkdirs()
                            // Os.symlink errors if target exists; in
                            // a fresh staging dir it never does.
                            Os.symlink(entry.linkName, outFile.absolutePath)
                        }
                        else -> {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out -> tar.copyTo(out) }
                            if ((entry.mode and 0b001_001_001) != 0) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assetExists(context: Context, path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (_: java.io.IOException) {
        false
    }

    /**
     * Extract `assets/libhybris/<abi>.tar` into `filesDir/libhybris/`,
     * preserving symlinks. Idempotent — gated by [currentExtractStamp]
     * via a `.version` stamp written last, so a partial extract is
     * indistinguishable from "never extracted" and gets retried.
     *
     * The tar's contents are flat (libEGL.so, libhybris/, gl-shims/,
     * … at the tar root) — see `app/build.gradle.kts` `packLibhybris`
     * — so the extracted tree sits directly at `<filesDir>/libhybris/`,
     * which is where [me.phie.tawc.install.LibhybrisInstallProvider]
     * walks from to copy into each rootfs.
     *
     * Called from [me.phie.tawc.install.TawcInstaller] during install /
     * APK upgrade and from [me.phie.tawc.install.TawcrootMethod] per
     * spawn. On a fresh device with both happening at the same time
     * we'd have two extractors racing the same staging dir —
     * synchronize on a process-level lock so only one runs.
     *
     * Returns true if extracted (or was already up-to-date), false
     * if no asset is shipped for this ABI (e.g. emulator build).
     */
    fun ensureLibhybrisExtracted(context: Context): Boolean = synchronized(LIBHYBRIS_EXTRACT_LOCK) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val assetPath = "libhybris/$abi.tar"
        if (!assetExists(context, assetPath)) {
            logMissingAssetOnce("libhybris", "No libhybris asset shipped for ABI $abi; skipping extract")
            return false
        }

        val destDir = File(context.filesDir, "libhybris")
        val currentStamp = currentExtractStamp(context)
        if (!isStampStale("libhybris", destDir, currentStamp)) {
            return true
        }

        // Stage into `.new` and swap, so a partial extract from a
        // prior interrupted run gets cleaned up rather than read.
        val stagingDir = File(context.filesDir, "libhybris.new")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        extractTarAsset(context, assetPath, stagingDir)

        // No copy fallback: `copyRecursively` follows symlinks instead
        // of preserving them, which would silently turn libhybris's
        // symlink topology into duplicate file copies.
        atomicReplaceDir(stagingDir, destDir)
        File(destDir, ".version").writeText(currentStamp)
        Log.i(TAG, "Extracted libhybris ($abi) to $destDir")
        true
    }

    /**
     * Names of the three raw assets shipped under
     * `assets/turnip/<abi>/` by Gradle's `packTurnip`.
     *
     * No tar wrapper here, unlike libhybris: the Turnip build stages
     * three plain files with no symlink topology to preserve, so the
     * Android asset packager can carry them as-is.
     */
    const val TURNIP_LIB_ASSET = "libvulkan_freedreno.so"
    const val TURNIP_ICD_ASSET = "freedreno_icd.json"
    const val TURNIP_LOADER_ASSET = "libvulkan.so.1"

    /**
     * Extract `assets/turnip/<abi>/{libvulkan_freedreno.so,
     * freedreno_icd.json, libvulkan.so.1}` into `<filesDir>/turnip/`.
     * Picks the asset subdir by [Build.SUPPORTED_ABIS][0]. Same
     * versioned-stamp + atomic-rename pattern as
     * [ensureLibhybrisExtracted].
     *
     * Returns true if extracted (or already up to date), false if no
     * asset is shipped for this device's ABI (Turnip is aarch64-only,
     * so an x86_64 build has none).
     */
    fun ensureTurnipExtracted(context: Context): Boolean = synchronized(TURNIP_EXTRACT_LOCK) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val assetDir = "turnip/$abi"
        val assets = listOf(TURNIP_LIB_ASSET, TURNIP_ICD_ASSET, TURNIP_LOADER_ASSET)
        if (!assetExists(context, "$assetDir/$TURNIP_LIB_ASSET")) {
            logMissingAssetOnce(
                "turnip",
                "No turnip asset shipped for ABI $abi; TURNIP backend unavailable",
            )
            return false
        }

        val destDir = File(context.filesDir, "turnip")
        val currentStamp = currentExtractStamp(context)
        if (!isStampStale("turnip", destDir, currentStamp)) {
            return true
        }

        val stagingDir = File(context.filesDir, "turnip.new")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        for (name in assets) {
            context.assets.open("$assetDir/$name").use { input ->
                File(stagingDir, name).outputStream().use { out -> input.copyTo(out) }
            }
        }
        atomicReplaceDir(stagingDir, destDir)
        File(destDir, ".version").writeText(currentStamp)
        Log.i(TAG, "Extracted turnip ($abi) to $destDir")
        true
    }
}

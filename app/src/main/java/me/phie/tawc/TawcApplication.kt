package me.phie.tawc

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import me.phie.tawc.install.BootstrapCache
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.InterruptedInstalls
import me.phie.tawc.install.RootfsTmpSweeper
import me.phie.tawc.install.TawcInstaller
import me.phie.tawc.install.VulkanProvisionOp
import me.phie.tawc.ops.OperationsNotificationCenter
import kotlin.concurrent.thread

/**
 * Process-wide entry point. Used for cheap startup chores that have no
 * UI and shouldn't block onCreate of the first Activity:
 *
 *  - Sweep stale bootstrap-tarball cache entries — the OS only evicts
 *    `cacheDir` under storage pressure, so a 200 MB tarball can squat
 *    on disk for months without our own TTL ([BootstrapCache.sweepStale]).
 *  - Start the dev exec broker (debug builds only — [DevHooks]).
 *
 * It also applies the saved [ThemeMode] — not a chore, but the same "before
 * any Activity exists" slot, which is the only place it can be done without
 * recreating whatever is already on screen.
 *
 * Per-install `nativeLibraryDir` (which moves between APKs as
 * `/data/app/~~<hash>/...`) is resolved fresh in
 * [InstallationMethod.startInside] each entry, so there's nothing
 * persistent to refresh here.
 */
class TawcApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppVisibility)
        // Bind the SharedPreferences instance early so non-Activity
        // code (RootfsEnv on the broker thread, etc.) can read settings
        // without a Context handle. Cheap (memory-mapped pref file).
        Settings.init(this)
        // Before the first Activity exists: setDefaultNightMode only decides
        // the theme of Activities created after it (or recreates the ones
        // already up), so a cold start that skipped this would come up in
        // the system theme and then flip.
        AppCompatDelegate.setDefaultNightMode(Settings.themeMode.delegateMode)
        // Start the per-op notification center before any service can
        // register an Operation. The center is the single owner of the
        // `tawc-operations` notification channel and the
        // OperationsRegistry → notification fan-out — see
        // me.phie.tawc.ops package KDoc.
        OperationsNotificationCenter.start(this)
        thread(name = "tawc-startup", isDaemon = true) {
            // Reconcile records left mid-flight by a previous process
            // *before* anything else touches the install dirs. The
            // in-flight state lives in metadata.json while the operation
            // that owns it lives in memory, so a killed process leaves a
            // record nothing can finish — and MainActivity, which
            // reads the record, then shows a progress page with no way
            // out of it. See [InterruptedInstalls] for the full chain.
            try {
                val store = InstallationStore(this)
                val n = InterruptedInstalls.recover(store) { interrupted ->
                    getString(
                        when (interrupted) {
                            Installation.State.UNINSTALLING ->
                                R.string.install_interrupted_uninstall
                            else -> R.string.install_interrupted_install
                        }
                    )
                }
                if (n > 0) Log.i(TAG, "recovered $n interrupted install record(s)")
            } catch (t: Throwable) {
                Log.w(TAG, "interrupted-install recovery failed", t)
            }
            // Production ando broker (run Android commands from rootfs
            // guests; notes/ando.md). Per-distro: one listener per
            // ando-enabled install. All build types; alive whenever the
            // app process is. On this thread because it does disk IO
            // and calls into native code, neither of which belongs on
            // onCreate. It goes through [me.phie.tawc.ando.NativeAndoBridge]
            // (libandobridge.so), not the compositor bridge — see that
            // object's KDoc for why that split exists.
            try {
                val appPaths = AppPaths.from(this)
                appPaths.shareDir.mkdirs()
                // Unlink the single shared ando socket older versions
                // bound here (before ando went per-distro).
                appPaths.legacyAndoSocket.delete()
                AndoBrokers.refresh(this)
            } catch (t: Throwable) {
                Log.w(TAG, "ando broker start failed", t)
            }
            try {
                val n = BootstrapCache(this).sweepStale()
                if (n > 0) Log.i(TAG, "Bootstrap cache: evicted $n stale entries")
            } catch (t: Throwable) {
                Log.w(TAG, "Bootstrap cache sweep failed", t)
            }
            // Refresh tawc-installed files in every existing rootfs
            // when the app version stamp has changed since the last
            // install/refresh. No-op on cold app starts that follow a
            // run with the same `versionCode + lastUpdateTime` pair
            // (see TawcAssets.currentExtractStamp). Per-rootfs
            // failures are logged and swallowed inside [installAll].
            try {
                TawcInstaller.installAll(this, InstallationStore(this))
            } catch (t: Throwable) {
                Log.w(TAG, "TawcInstaller.installAll failed", t)
            }
            // Then make each READY container's Vulkan wiring agree with the
            // saved pick ([Settings.vulkanDriver]). The settings screen can
            // only reach containers that exist when the user picks one, so a
            // container installed afterwards has no wiring at all while the
            // setting still claims a driver. A container that already agrees
            // costs one file probe — see [VulkanProvisionOp.reconcile].
            try {
                VulkanProvisionOp.reconcile(this)
            } catch (t: Throwable) {
                Log.w(TAG, "vulkan reconcile failed", t)
            }
            // Age-sweep every install's flash-backed /tmp (no init in
            // the rootfs means nothing else ever clears it). See
            // [RootfsTmpSweeper] for the design constraints.
            try {
                RootfsTmpSweeper.sweepAll(InstallationStore(this))
            } catch (t: Throwable) {
                Log.w(TAG, "rootfs /tmp sweep failed", t)
            }
        }
        // Dev-only exec broker and its action handlers. [DevHooks] has
        // two implementations at the same FQCN — the real one in
        // `src/debug/java`, an empty one in `src/release/java` — so the
        // broker isn't in the release APK at all. See notes/exec-broker.md.
        DevHooks.start(this)
    }

    companion object {
        private const val TAG = "tawc-install"
    }
}

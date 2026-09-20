package me.phie.tawc.dev

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.phie.tawc.AndoBrokers
import me.phie.tawc.Settings
import me.phie.tawc.install.ChrootMethod
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.tasks.ProcessScanner

/**
 * Broker actions that don't belong to a more specific handler: test
 * scaffolding plus one static app fact the host can't cheaply learn over
 * adb. Registered from [me.phie.tawc.TawcApplication.onCreate] (debug
 * builds only).
 *
 * This file used to hold the compositor input / IME / clipboard drivers
 * (`ic-*`, `hardware-key`, `back`, `inject-touch`, `inject-pointer`,
 * `query-state`, the `focused-*` probes, `clipboard-*`). They went away
 * with the display stack (TAWC_DSH_DESIGN.md §11): there is no
 * compositor to drive, no client window to focus, and no IME bridge to
 * talk to.
 *
 * | Action | Args | Effect |
 * |--------|------|--------|
 * | `app-info` | — | prints `nativeLibraryDir=<path>`; the host-side tawcroot prod-env tests exec `libtawcroot.so` from there |
 * | `cleanup-rootfs` | `installId` | SIGKILL every process rooted in that install's rootfs |
 * | `test-init` | optional `installId` | enter in-memory test settings, close lingering op-log screens, drop ando overrides, optionally clean the rootfs |
 */
internal object InputActions {
    fun registerAll() {
        ActionRegistry.register("app-info", AppInfoAction)
        ActionRegistry.register("cleanup-rootfs", CleanupRootfsAction)
        ActionRegistry.register("test-init", TestInitAction)
    }

    // -- Helpers ------------------------------------------------------------

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Run [block] on the main thread and wait for it. We block the broker
     * thread because actions are one-shot — the host expects a single
     * exit code and the action shouldn't return before the work has
     * actually run. The wait has a 5s safety timeout to avoid hanging
     * the broker on a frozen main loop.
     */
    private fun onMainBlocking(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try { block() } finally { latch.countDown() }
        }
        return latch.await(5, TimeUnit.SECONDS)
    }

    /**
     * `app-info` — static app facts the host can't cheaply learn via adb.
     * `nativeLibraryDir` is where the APK's jniLibs land on this device
     * (ABI-dependent); the tawcroot prod-env integration tests exec
     * `libtawcroot.so` from there through the broker. kv lines so more
     * fields can be added without breaking parsers.
     */
    private object AppInfoAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            ctx.out("nativeLibraryDir=${ctx.appContext.applicationInfo.nativeLibraryDir}")
            return 0
        }
    }

    private fun cleanupRootfs(ctx: ActionContext, installId: String): Int {
        val store = InstallationStore(ctx.appContext)
        val rootfs = store.rootfsDir(installId)
        val includeChroot = store.load(installId)?.method == ChrootMethod.KEY
        return ProcessScanner.killAllInRootfs(
            rootfsPath = rootfs.absolutePath,
            installId = installId,
            includeChroot = includeChroot,
            log = {},
        )
    }

    private object CleanupRootfsAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val installId = args["installId"]
                ?: return ctx.fail("cleanup-rootfs: --arg installId=... required")
            val killed = cleanupRootfs(ctx, installId)
            ctx.out("rootfs_killed=$killed")
            return 0
        }
    }

    /**
     * `test-init` — fast per-test reset. Nothing here writes
     * SharedPreferences; app process death restores normal persisted
     * settings.
     */
    private object TestInitAction : BrokerAction {
        override fun run(args: Map<String, String>, ctx: ActionContext): Int {
            val installId = args["installId"]
            val ran = onMainBlocking {
                // Close op log screens left on top of the task by broker
                // install/uninstall/run actions, restoring whatever was
                // beneath (MainActivity for foreground-app invocations).
                DevActivityTracker.liveActivities()
                    .filterIsInstance<LogScreenActivity>()
                    .forEach { it.finish() }
                Settings.enterTestMode()
            }
            if (!ran) return ctx.fail("main loop did not run test-init within 5s")
            var killedRootfs = 0
            if (!installId.isNullOrBlank()) {
                killedRootfs = cleanupRootfs(ctx, installId)
            }
            // Drop any per-distro ando override a prior test set and
            // reconcile the broker back to metadata-backed defaults, so
            // ando listeners don't leak across tests. notes/ando.md.
            InstallationStore.clearAndoOverrides()
            AndoBrokers.refresh(ctx.appContext)
            ctx.out("rootfs_killed=$killedRootfs")
            return 0
        }
    }

    private fun ActionContext.fail(msg: String): Int {
        err(msg)
        return 2
    }
}

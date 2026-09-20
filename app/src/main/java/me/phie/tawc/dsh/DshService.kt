package me.phie.tawc.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.phie.tawc.R
import me.phie.tawc.install.Installation
import me.phie.tawc.install.InstallationMethod
import me.phie.tawc.install.InstallationStore
import java.io.BufferedReader
import java.io.IOException

/**
 * Where the harness is in its lifecycle. Published as a [StateFlow] so the
 * WebView Activity can render each phase instead of guessing from a blank
 * page (a wrong token and a not-yet-started server look identical
 * otherwise).
 */
sealed interface DshState {
    /** Nothing started yet. */
    data object Idle : DshState

    /** Container entered, harness booting. */
    data object Starting : DshState

    /** `dsh web` printed its URL and the port is accepting. */
    data class Ready(val url: String) : DshState

    /**
     * Terminal — the harness is not running and will not come back on its
     * own. Carries the [recoveries] worth offering, not just prose: a
     * dead end that only *describes* itself leaves the user with a
     * message and no move.
     */
    data class Failed(
        val message: String,
        val recoveries: List<DshRecovery>,
    ) : DshState
}

/**
 * A way out of a [DshState.Failed], or of [DshState.Idle].
 *
 * The list is chosen by the party that *observed* the failure — the
 * service, which knows which step failed — and merely rendered by the
 * Activity. That split is the point: "the container has no `dsh` in it"
 * and "the port is taken" are the same `Failed` shape but need opposite
 * advice, and the difference is only visible where the failure happened.
 *
 * Deliberately not `Set`: order is the priority, and the Activity makes
 * the first one the prominent button.
 */
enum class DshRecovery {
    /** Start the harness again — [DshService.ensureRunning] is idempotent. */
    RESTART,

    /**
     * No usable container exists, so go where one gets made. Safe from
     * any "no container" failure, which is exactly when it is offered:
     * [me.phie.tawc.MainActivity] forwards straight back to this screen
     * only for a `READY` record, and there is none.
     */
    INSTALL,

    /**
     * A container exists but is unusable, and the only supported repair
     * is to rebuild it — [me.phie.tawc.install.DistroInfoActivity] is
     * where that lives. Restarting cannot help here (the harness would
     * re-read the same broken rootfs), which is why this replaces
     * [RESTART] rather than joining it.
     */
    REINSTALL,

    /** Open a shell in the container, for the failures a user can fix. */
    TERMINAL,
}

/**
 * Boots DSH inside an installed rootfs and keeps `dsh web` alive for the
 * lifetime of the app's foreground service.
 *
 * **Why this is a service and not an Activity concern**: the container
 * outlives any single Activity (rotation, task switching), and the process
 * tree dies with the app process regardless — see the design doc's
 * "容器寿命 ≤ app 进程寿命". A foreground service is what keeps *both* the
 * app process and its guest tree off the LMK/freezer path, and it is also
 * required for the guest to keep network access once the app is
 * backgrounded: without an FGS the light-Doze `fw_dozable` chain cuts the
 * uid's traffic after about a minute and every model request fails with a
 * DNS-shaped error.
 *
 * **Headless.** The UI is a WebView talking HTTP to loopback, so we call
 * [InstallationMethod.startInside] directly and never involve a
 * compositor — there isn't one any more (TAWC_DSH_DESIGN.md §11).
 *
 * See `TAWC_DSH_DESIGN.md` §4 (lifecycle) and §6 (communication).
 */
class DshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null

    /** The live `dsh web` process; null when not running. */
    @Volatile
    private var harness: Process? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopHarness()
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: the system recreates the service after a kill, and
        // recreate-then-reboot-the-container is exactly the recovery path we
        // want (harness state lives on disk, not in this process).
        startHarness()
        return START_STICKY
    }

    override fun onDestroy() {
        stopHarness()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------
    // Harness lifecycle
    // ---------------------------------------------------------------------

    private fun startHarness() {
        if (runJob?.isActive == true) return
        runJob = scope.launch {
            _state.value = DshState.Starting

            val store = InstallationStore(this@DshService)
            val install = pickInstall(store)
            if (install == null) {
                _state.value = DshState.Failed(
                    getString(R.string.dsh_failed_no_distro),
                    listOf(DshRecovery.INSTALL),
                )
                return@launch
            }
            val method = InstallationMethod.forKey(this@DshService, install.method)
            if (method == null) {
                _state.value = DshState.Failed(
                    getString(R.string.dsh_failed_unknown_method, install.id, install.method),
                    // No RESTART: `forKey` reads the method key off disk,
                    // so a second attempt reads the same unsupported
                    // value. Rebuilding the container is the only repair.
                    listOf(DshRecovery.REINSTALL),
                )
                return@launch
            }

            val rootfs = store.rootfsDir(install.id).absolutePath
            val proc = try {
                // `null` = the configured backend
                // (`graphics ?: RootfsEnv.defaultBackend()` in every
                // InstallationMethod), which follows the settings screen's
                // Vulkan pick — on aarch64 that is Turnip by default, the
                // Mesa freedreno driver over /dev/kgsl-3d0.
                //
                // Do NOT pass CPU here. That branch forces
                // LIBGL_ALWAYS_SOFTWARE=1 + GALLIUM_DRIVER=llvmpipe and
                // deliberately keeps libhybris off LD_LIBRARY_PATH, so
                // every tool the agent runs would be pinned to software
                // rasterization. "No display" is not "no GPU": the
                // container still needs the driver for Vulkan compute
                // (llama.cpp and friends), and headless Vulkan needs no
                // surface, so no compositor is involved on this path.
                method.startInside(rootfs, HARNESS_COMMAND, null)
            } catch (e: Exception) {
                Log.e(TAG, "failed to enter rootfs ${install.id}", e)
                _state.value = DshState.Failed(
                    getString(R.string.dsh_failed_enter, e.message ?: e.javaClass.simpleName),
                    // All three: entering can fail for a transient reason
                    // (retry), for a broken rootfs (rebuild) or for
                    // something only the user can see from inside
                    // (terminal). The service cannot tell which, so it
                    // does not pretend to.
                    listOf(DshRecovery.RESTART, DshRecovery.REINSTALL, DshRecovery.TERMINAL),
                )
                return@launch
            }
            harness = proc

            // Drain stderr (so the pipe can't fill and wedge the harness) and
            // scan stdout for the URL. Both readers must run before waitFor.
            pumpLines(proc.errorStream.bufferedReader()) { line ->
                Log.w(TAG, "harness stderr: $line")
            }
            pumpLines(proc.inputStream.bufferedReader()) { line ->
                Log.i(TAG, "harness: $line")
                parseUrl(line)?.let { url ->
                    _state.value = DshState.Ready(url)
                    notifyUrlReady()
                }
            }

            val code = try {
                proc.waitFor()
            } catch (e: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                return@launch
            }
            harness = null

            // The harness exiting on its own is not something a restart
            // always fixes, and *which* recovery is worth offering turns
            // on the exit code — see [exitFailure].
            val (messageRes, recoveries) = exitFailure(code)
            _state.value = DshState.Failed(getString(messageRes, code), recoveries)
            Log.w(TAG, "harness exited with $code")
        }
    }

    private fun stopHarness() {
        runJob?.cancel()
        runJob = null
        harness?.let { proc ->
            // The harness is a setsid session leader; destroying the direct
            // child is enough for the normal path. Any straggler inside the
            // rootfs is reaped when the app process dies (the guest tree
            // lives in its cgroup), which is also why this service does not
            // need its own pidfile bookkeeping.
            proc.destroy()
            Log.i(TAG, "harness destroy() sent")
        }
        harness = null
        _state.value = DshState.Idle
    }

    private fun pickInstall(store: InstallationStore): Installation? =
        store.list().firstOrNull { it.state == Installation.State.READY }

    /** Read [reader] to EOF on its own thread, applying [onLine] per line. */
    private fun pumpLines(reader: BufferedReader, onLine: (String) -> Unit) {
        Thread {
            try {
                reader.forEachLine(onLine)
            } catch (e: IOException) {
                Log.w(TAG, "reader ended: $e")
            }
        }.apply {
            isDaemon = true
            name = "dsh-harness-reader"
            start()
        }
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    /** Re-post so the notification text tracks [DshState]. */
    private fun notifyUrlReady() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH", NotificationManager.IMPORTANCE_LOW).apply {
                description = "DSH 智能体在后台运行时的常驻通知。"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DshActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (val s = _state.value) {
            DshState.Idle -> getString(R.string.dsh_notification_stopped)
            DshState.Starting -> getString(R.string.dsh_status_starting)
            is DshState.Ready -> getString(R.string.dsh_notification_running)
            is DshState.Failed -> s.message
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.title_dsh))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.dsh_action_stop), stop).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "tawc-dsh"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "tawc_dsh"
        private const val ACTION_STOP = "me.phie.tawc.dsh.STOP"

        /**
         * Fixed, not `--port 0`. The browser-trust cookie is bound to the
         * authority (host:port), so an OS-assigned port would invalidate it
         * on every restart and force a fresh token handshake. 17800 is
         * deliberately off the common list (3080 is `dsh web`'s own
         * default and would collide with a dev server).
         */
        private const val PORT = 17800

        /**
         * The exit code [HARNESS_COMMAND] uses when the container has no
         * `dsh` in it. Interpolated into the script rather than written
         * there as a literal, so the two cannot drift apart — the value
         * is load-bearing for [exitFailure], and `internal` so the test
         * that pins that behavior reads it instead of a copy.
         */
        internal const val EXIT_DSH_MISSING = 127

        /**
         * The in-rootfs command. `startInside` hands this to `bash -lc` as a
         * single argv element, so a multi-line script is safe here.
         *
         * `DSH_PERMISSION_MODE=danger-full-access` is required, not a
         * preference: `sandbox-local`'s runner chain is `bwrap → Landlock`,
         * and on Android both are dead (unprivileged user namespaces are
         * disabled; `CONFIG_SECURITY_LANDLOCK is not set`). Confinement
         * therefore fails *closed* and the default `workspace-write` would
         * make every bash tool call refuse to run. See
         * `TAWC_DSH_DESIGN.md` §5.6.
         */
        private val HARNESS_COMMAND = """
            export DSH_PERMISSION_MODE=danger-full-access

            DSH_BIN="${'$'}(command -v dsh 2>/dev/null || ls -1 /root/.npm/_npx/*/node_modules/.bin/dsh 2>/dev/null | head -n1)"
            if [ -z "${'$'}DSH_BIN" ]; then
              echo "dsh: not found in the rootfs" >&2
              exit $EXIT_DSH_MISSING
            fi
            echo "dsh: using ${'$'}DSH_BIN"
            exec "${'$'}DSH_BIN" web --no-open --port $PORT
        """.trimIndent()

        /**
         * How to present a harness that exited with [code]: the message
         * resource (formatted with the code) and the recoveries worth
         * offering.
         *
         * Split out as a pure function because the interesting part is
         * the *branch*, and it is the kind of branch that rots: the two
         * cases look identical in the log and want opposite advice.
         *
         *  - **[EXIT_DSH_MISSING]** is raised by [HARNESS_COMMAND]
         *    itself, before `exec`, when no `dsh` is on the path. The
         *    container is otherwise fine, so this is not transient: a
         *    restart runs the same lookup and finds the same nothing.
         *    Hence no [DshRecovery.RESTART] — the two real fixes are to
         *    rebuild the container, or to go install DSH by hand from a
         *    shell. (Reachable on any container built before the
         *    `PROVISIONING` stage existed, and on one whose `npm install`
         *    failed.)
         *  - **Anything else** came from `dsh` itself, after it was
         *    found: a port already in use, bad config, a crash. Those
         *    genuinely can be transient, so restart leads, with the
         *    terminal for whatever the log cannot explain.
         */
        internal fun exitFailure(code: Int): Pair<Int, List<DshRecovery>> =
            if (code == EXIT_DSH_MISSING) {
                R.string.dsh_failed_no_harness to
                    listOf(DshRecovery.REINSTALL, DshRecovery.TERMINAL)
            } else {
                R.string.dsh_failed_exit to
                    listOf(DshRecovery.RESTART, DshRecovery.TERMINAL)
            }

        private val _state = MutableStateFlow<DshState>(DshState.Idle)

        /** Observable harness state; the Activity renders this. */
        val state: StateFlow<DshState> = _state.asStateFlow()

        /** `dsh web: http://127.0.0.1:17800/?token=…` → the URL. */
        private val URL_RE = Regex("""https?://127\.0\.0\.1:\d+/\S*""")

        internal fun parseUrl(line: String): String? =
            URL_RE.find(line)?.value

        /** Start the harness if it isn't already running. Idempotent. */
        fun ensureRunning(context: Context) {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, DshService::class.java)
            )
        }

        /** Stop the harness and the service. */
        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, DshService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }
}

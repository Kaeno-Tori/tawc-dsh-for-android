package me.phie.tawc.install

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import me.phie.tawc.R
import me.phie.tawc.VulkanDriver
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ops.MutableOperation
import me.phie.tawc.ops.OperationProgress
import me.phie.tawc.ops.OperationStage
import me.phie.tawc.ops.OperationsRegistry
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Provisions a [VulkanDriver] pick into every `READY` rootfs, by running
 * the matching script from `app/src/main/assets/vulkan-scripts/` inside it.
 *
 * The scripts are what actually touch the rootfs — they install an ICD
 * loader at `/usr/lib/libvulkan.so.1` and, for Turnip, a manifest under
 * `/usr/share/vulkan/icd.d/`, backing up whatever the distro had so the
 * pick can be turned off again. This object's job is only the plumbing:
 * land the script files, run the right one, stream the output into an
 * [me.phie.tawc.ops.Operation] so the user sees exactly what changed.
 *
 * Three cases, mirroring the three scripts:
 *
 *   off  -> system|turnip   `enable-<target>.sh`
 *   a    -> b               `switch.sh <b>`   (cleanup, then enable)
 *   a    -> off             `cleanup.sh`
 *
 * The scripts are materialised into the rootfs rather than piped to a
 * shell for two reasons: they stay re-runnable by hand afterwards (the
 * user can read what the app did), and the writes happen as the in-rootfs
 * uid, which is root — the rootfs's own `/usr/lib` is root-owned, so the
 * app uid couldn't write there directly.
 *
 * There are two entry points, and the difference between them is who asked:
 *
 *  - [start] — the settings screen picked something. The run is wrapped in
 *    an [me.phie.tawc.ops.Operation] with the log screen open, because the
 *    user asked for *this* change and should see what it did.
 *  - [reconcile] — nobody asked; a cold start noticed a container that
 *    disagrees with the saved pick. Silent, and it does nothing at all for a
 *    container that already agrees.
 *
 * See TAWC_DSH_DESIGN.md §11.4.
 */
internal object VulkanProvisionOp {

    private const val TAG = "tawc-vulkan"

    /** Asset dir, and the dir the scripts are copied to inside the rootfs. */
    private const val ASSET_DIR = "vulkan-scripts"
    private const val GUEST_DIR = "/usr/lib/tawc/vulkan"

    /**
     * The scripts' own record of which pick is live, as a path relative to a
     * rootfs — the same directory as [GUEST_DIR] (that one is absolute and
     * guest-side, this one is joined onto a rootfs dir on the host side).
     * `enable-*.sh` writes it, `cleanup.sh` removes it, and [reconcile] reads
     * it to decide whether a container needs anything at all.
     */
    private const val STATE_FILE = "usr/lib/tawc/vulkan/state"

    /**
     * Every script, written on each run so `switch.sh` finds `cleanup.sh`
     * and the two `enable-*.sh` next to itself.
     */
    private val SCRIPTS = listOf("cleanup.sh", "enable-system.sh", "enable-turnip.sh", "switch.sh")

    /**
     * Heredoc marker for the in-rootfs write. Must not appear in any script
     * — [buildCommand] checks rather than trusting that.
     */
    private const val SENTINEL = "TAWC_VULKAN_SCRIPT_EOF"

    private val seq = AtomicLong(0)

    fun start(context: Context, from: VulkanDriver, to: VulkanDriver) {
        val app = context.applicationContext
        val opId = "vulkan:${seq.incrementAndGet()}"
        val sink = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 1024)
        val cancelled = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val procRef = AtomicReference<Process?>()
        val opRef = AtomicReference<MutableOperation?>()
        fun finish(stage: OperationStage, message: String) {
            val currentOp = opRef.get() ?: return
            if (terminal.compareAndSet(false, true)) {
                terminate(currentOp, opId, sink, stage, message)
            }
        }

        val op = MutableOperation(
            id = opId,
            title = app.getString(R.string.operation_title_vulkan, app.getString(labelOf(to))),
            log = sink,
            cancelHandler = {
                if (cancelled.compareAndSet(false, true)) {
                    val proc = procRef.get()
                    if (proc != null) proc.destroyForcibly()
                    else finish(OperationStage.FAILED, app.getString(R.string.operation_status_cancelled))
                }
            },
        )
        opRef.set(op)
        OperationsRegistry.register(op)
        op.publish(OperationProgress(OperationStage.RUNNING, app.getString(R.string.operation_status_running)))

        try {
            app.startActivity(
                LogScreenActivity.intentFor(app, opId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (t: Throwable) {
            // BAL refusal etc. — the op stays registered, so the per-op
            // notification's tap action still reaches the panel.
            Log.w(TAG, "open log screen: ${t.message}")
        }

        thread(name = "tawc-vulkan-starter", isDaemon = true) {
            val scripts = try {
                readScripts(app)
            } catch (t: Throwable) {
                Log.w(TAG, "read scripts", t)
                finish(
                    OperationStage.FAILED,
                    app.getString(R.string.vulkan_status_scripts_unreadable, t.message ?: "?"),
                )
                return@thread
            }

            val store = InstallationStore(app)
            val targets = store.list().filter { it.state == Installation.State.READY }
            if (targets.isEmpty()) {
                finish(OperationStage.FAILED, app.getString(R.string.vulkan_status_no_container))
                return@thread
            }

            var failed = 0
            for (installation in targets) {
                if (cancelled.get()) break
                sink.tryEmit("=== ${installation.id} ===")
                val rootfs = store.rootfsDir(installation.id).absolutePath
                val method = InstallationMethod.forKey(app, installation.method)
                if (method == null) {
                    sink.tryEmit(app.getString(R.string.operation_status_unknown_install_method, installation.method))
                    failed++
                    continue
                }
                val exit = runOne(app, method, rootfs, scripts, from, to, sink, procRef, cancelled)
                if (exit != 0) failed++
            }

            if (cancelled.get()) {
                finish(OperationStage.FAILED, app.getString(R.string.operation_status_cancelled))
            } else if (failed != 0) {
                finish(
                    OperationStage.FAILED,
                    app.getString(R.string.vulkan_status_failed, failed, targets.size),
                )
            } else {
                finish(OperationStage.DONE, app.getString(R.string.operation_status_done))
            }
        }
    }

    /**
     * Re-apply [VulkanDriver.effective] to every `READY` rootfs that isn't
     * wired for it.
     *
     * The settings screen only reaches containers that exist *at the moment
     * the user picks* — a container installed afterwards comes up with
     * whatever its distro shipped and no sign of the pick. That drift is
     * what this repairs, and it is why [TawcApplication] calls it on every
     * cold start, right after [TawcInstaller.installAll], and why
     * [Installer] calls it the moment an install flips to `READY`.
     *
     * What it wants is the *effective* driver, not the saved pick: on a
     * device whose build defaults to Turnip, a user who never opened
     * settings has no saved pick at all, and the app has been running its
     * spawns with Turnip the whole time. Asking only the pref meant such a
     * container was never wired — nothing at `/usr/lib/libvulkan.so.1` for
     * programs started inside it — until someone re-picked the accelerator
     * by hand.
     *
     * The check is a file read rather than a spawn: `enable-*.sh` leaves
     * `/usr/lib/tawc/vulkan/state` behind and `cleanup.sh` deletes it, so the
     * file's contents *are* the container's own answer to "which driver are
     * you wired for". A container that already agrees costs one `isFile`
     * probe and nothing else.
     *
     * Deliberately no UI and no [me.phie.tawc.ops.Operation]: the user did
     * not ask for this run, and a log screen opening itself on cold start
     * would be worse than the drift it fixes. Failures go to logcat.
     */
    fun reconcile(app: Context) {
        val want = VulkanDriver.effective()
        // `cleanup.sh` removes the state file, so "off" is represented by
        // absence — the same thing [readState] returns for a container that
        // was never touched.
        val wantKey = want.takeIf { it != VulkanDriver.OFF }?.key

        val store = InstallationStore(app)
        val targets = store.list().filter { it.state == Installation.State.READY }
        if (targets.isEmpty()) return

        val scripts = try {
            readScripts(app)
        } catch (t: Throwable) {
            Log.w(TAG, "reconcile: read scripts", t)
            return
        }

        // Nobody subscribes and `replay` is 0, so these emissions are
        // dropped — the scripts' exit codes are what this path reports on.
        val sink = MutableSharedFlow<String>(extraBufferCapacity = 256)

        for (installation in targets) {
            val rootfsDir = store.rootfsDir(installation.id)
            val haveKey = readState(File(rootfsDir, STATE_FILE))
            if (haveKey == wantKey) continue

            val method = InstallationMethod.forKey(app, installation.method)
            if (method == null) {
                Log.w(TAG, "reconcile: unknown method ${installation.method} on ${installation.id}")
                continue
            }
            // What the container has *now* is what picks the script: an
            // enable when it was off, `switch.sh` between two live picks.
            val from = VulkanDriver.entries.firstOrNull { it.key == haveKey } ?: VulkanDriver.OFF
            Log.i(TAG, "reconcile ${installation.id}: ${from.key} -> ${want.key}")
            val exit = runOne(app, method, rootfsDir.absolutePath, scripts, from, want, sink)
            if (exit != 0) Log.w(TAG, "reconcile ${installation.id}: script exited $exit")
        }
    }

    /**
     * The container's own record of which driver it is wired for, or `null`
     * when there is none: the file is gone ([VulkanDriver.OFF], or a
     * container nothing has ever touched), or it is unreadable — which for
     * this purpose is the same answer.
     */
    private fun readState(file: File): String? = try {
        if (file.isFile) file.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (t: Throwable) {
        Log.w(TAG, "read ${file.path}", t)
        null
    }

    /** Returns the exit code, or non-zero if the spawn itself failed. */
    private fun runOne(
        app: Context,
        method: InstallationMethod,
        rootfs: String,
        scripts: Map<String, String>,
        from: VulkanDriver,
        to: VulkanDriver,
        sink: MutableSharedFlow<String>,
        // Null on the [reconcile] path: it is not cancellable and has no
        // process for a cancel handler to reach.
        procRef: AtomicReference<Process?>? = null,
        cancelled: AtomicBoolean? = null,
    ): Int {
        val command = buildCommand(scripts, from, to)
        val proc = try {
            // The provisioning scripts are plain shell; no compositor.
            method.startInside(rootfs, command, null)
        } catch (t: Throwable) {
            Log.w(TAG, "startInside", t)
            sink.tryEmit(
                app.getString(
                    R.string.operation_status_failed_to_start,
                    t.javaClass.simpleName,
                    t.message ?: app.getString(R.string.operation_status_no_detail),
                )
            )
            return 1
        }
        procRef?.set(proc)
        if (cancelled?.get() == true) {
            try {
                proc.destroyForcibly()
                proc.waitFor()
            } catch (_: Throwable) {
            }
            return 1
        }
        // Close stdin so nothing in the script can block on a read.
        try { proc.outputStream.close() } catch (_: Throwable) {}
        val out = thread(name = "tawc-vulkan-stdout", isDaemon = true) { relayLines(proc.inputStream, sink) }
        val err = thread(name = "tawc-vulkan-stderr", isDaemon = true) { relayLines(proc.errorStream, sink) }
        val exit = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
        out.join(RELAY_JOIN_MS)
        err.join(RELAY_JOIN_MS)
        return exit
    }

    /**
     * Materialise every script into [GUEST_DIR], then run the one the
     * [from] -> [to] transition calls for. `set -e` so a failed write can't
     * be masked by whatever the entry script then does.
     */
    private fun buildCommand(
        scripts: Map<String, String>,
        from: VulkanDriver,
        to: VulkanDriver,
    ): String = buildString {
        appendLine("set -e")
        appendLine("mkdir -p $GUEST_DIR")
        for ((name, content) in scripts) {
            appendLine("cat > $GUEST_DIR/$name <<'$SENTINEL'")
            append(content)
            if (!content.endsWith("\n")) appendLine()
            appendLine(SENTINEL)
        }
        appendLine("chmod 755 $GUEST_DIR/*.sh")
        appendLine("sh ${entryScript(from, to)}")
    }

    /**
     * The [from] -> [to] transition's script, per [VulkanProvisionOp]'s
     * class doc. `switch.sh` is used rather than a bare enable so the
     * previous pick's loader is cleaned up before the new one claims the
     * backup slot.
     */
    private fun entryScript(from: VulkanDriver, to: VulkanDriver): String = when {
        to == VulkanDriver.OFF -> "$GUEST_DIR/cleanup.sh"
        from == VulkanDriver.OFF -> "$GUEST_DIR/enable-${to.key}.sh"
        else -> "$GUEST_DIR/switch.sh ${to.key}"
    }

    private fun readScripts(app: Context): Map<String, String> = SCRIPTS.associateWith { name ->
        val content = app.assets.open("$ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        // A sentinel inside the payload would truncate the heredoc and feed
        // the rest of the script to the shell as commands. Our own assets
        // can't contain it; this is here so that stays true.
        require(!content.lineSequence().any { it.trim() == SENTINEL }) {
            "$ASSET_DIR/$name contains the heredoc sentinel '$SENTINEL'"
        }
        content
    }

    private fun labelOf(driver: VulkanDriver): Int = when (driver) {
        VulkanDriver.OFF -> R.string.vulkan_driver_off
        VulkanDriver.SYSTEM -> R.string.vulkan_driver_system
        VulkanDriver.TURNIP -> R.string.vulkan_driver_turnip
    }

    private fun relayLines(stream: InputStream, sink: MutableSharedFlow<String>) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    sink.tryEmit(line)
                }
            }
        } catch (_: Throwable) {
            // EOF / process death — the caller publishes the terminal stage.
        }
    }

    private fun terminate(
        op: MutableOperation,
        opId: String,
        sink: MutableSharedFlow<String>,
        stage: OperationStage,
        msg: String,
    ) {
        if (stage == OperationStage.FAILED) sink.tryEmit("[vulkan] $msg")
        op.publish(OperationProgress(stage, msg))
        // Give a fresh LogScreenActivity time to bind before the op leaves
        // the registry — same grace period as RunCommandOp, and for the
        // same reason: a fast run would otherwise finish before the screen
        // takes its first frame and the user sees the cold-open placeholder.
        thread(name = "tawc-vulkan-cleanup", isDaemon = true) {
            try { Thread.sleep(POST_TERMINATE_GRACE_MS) } catch (_: InterruptedException) {}
            OperationsRegistry.unregister(opId)
        }
    }

    private const val POST_TERMINATE_GRACE_MS = 2_000L
    private const val RELAY_JOIN_MS = 500L
}

package me.phie.tawc.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.phie.tawc.AppVisibility
import me.phie.tawc.MainActivity
import me.phie.tawc.R

/**
 * Application-singleton that keeps Android notifications in sync with
 * [OperationsRegistry]. Started from [me.phie.tawc.TawcApplication.onCreate]
 * via [start].
 *
 * Per registered op:
 *   - Posts an ongoing notification on the `tawc-operations` channel.
 *   - Updates its content text from [Operation.progress.value.message]
 *     on every emit.
 *   - Tap PendingIntent → [LogScreenActivity] for that op (the only
 *     side-effect of opening that activity is rendering the panel —
 *     no operation triggered).
 *   - Cancel action button → [CancelOperationReceiver], which calls
 *     [Operation.cancel] without a confirm dialog (the notification
 *     tap is itself the deliberate decision).
 *   - When the op leaves the registry (terminal-then-unregister), the
 *     ongoing notification is cancelled and — if the app isn't in the
 *     foreground — replaced by a plain, dismissible **completion
 *     notification** on the `tawc-operation-results` channel
 *     ([postCompletion]).
 *
 * The foreground check is what makes cancellation-for-free: a user who
 * taps Cancel is by definition looking at the app, so the terminal
 * state is already on screen and no notification is raised for it.
 * A job that finishes while the app is backgrounded is exactly the
 * case a notification exists for.
 *
 * [fgsAnchorFor] is for services that want to use one of their
 * registered ops' notifications as their `startForeground` anchor —
 * see [me.phie.tawc.install.InstallationService.startInstall]. The
 * notification id is stable per op, so the FGS anchor and the
 * standalone notification are the same notification.
 */
object OperationsNotificationCenter {

    private const val TAG = "tawc-ops"
    const val CHANNEL_ID = "tawc-operations"

    /**
     * Channel for post-terminal ("it finished") notifications. Separate
     * from [CHANNEL_ID] because that one is `IMPORTANCE_LOW` — correct
     * for an ongoing progress line the user can see in the shade
     * whenever they want, wrong for a result they need to be told
     * about: a low-importance notification never surfaces a status-bar
     * icon and never makes a sound, so a completion would be
     * invisible unless the user happened to pull the shade down.
     *
     * `IMPORTANCE_DEFAULT` means status-bar icon + shade entry + sound,
     * but no heads-up takeover of whatever the user is doing. If a
     * future release wants heads-up, this is the one constant to bump —
     * and it has to be a new channel id, since importance is immutable
     * after creation.
     */
    const val RESULT_CHANNEL_ID = "tawc-operation-results"

    /**
     * Notification IDs are derived from the op id's hash with a fixed
     * high-bit prefix to keep them out of the way of any other
     * NotificationManager.notify caller in the app. Stable per op id
     * so the same notification gets updated rather than spawning
     * duplicates.
     */
    private const val NOTIFICATION_ID_PREFIX = 0x6F00_0000.toInt()

    /**
     * Second id namespace for the completion notification of the same
     * op. Distinct from the ongoing id on purpose: the ongoing one is
     * (or was) a foreground-service anchor, and posting a non-ongoing
     * auto-cancel notification over it in place would race with
     * `stopForeground`. Two ids let the anchor die on its own schedule
     * while the result lives independently.
     */
    private const val RESULT_ID_PREFIX = 0x6F40_0000.toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Per-op collector job, so we can cancel one when its op is unregistered. */
    private val collectors = mutableMapOf<String, Job>()

    /**
     * Currently-registered ops, kept so the departure path can read an
     * op's final state after it has left [OperationsRegistry].
     */
    private val liveOps = mutableMapOf<String, Operation>()

    @Volatile private var started = false
    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        ensureChannel(context)
        scope.launch {
            OperationsRegistry.ops.collect { ops -> reconcile(ops) }
        }
    }

    /**
     * Build a notification for the registered op named [opId] and return
     * `(notificationId, notification)` so a caller can pass them to
     * `Service.startForeground`. The standalone notification posted by
     * the registry-watcher uses the same id, so the FGS anchor and the
     * tray notification are the same notification (Android merges them
     * by id).
     */
    fun fgsAnchorFor(opId: String): Pair<Int, Notification> {
        val ctx = appContext ?: error("OperationsNotificationCenter not started")
        val op = OperationsRegistry.get(opId)
            ?: error("op '$opId' is not registered")
        return notificationIdFor(opId) to buildNotification(ctx, op)
    }

    /**
     * Build a placeholder notification for an op that's about to be
     * registered, so a service can satisfy `startForeground`'s 5-second
     * deadline before its validation / coroutine-launch code runs. The
     * id matches what [fgsAnchorFor] will return for the same [opId];
     * once the op is registered, the registry-watcher's `nm.notify`
     * with the same id seamlessly replaces this placeholder content.
     *
     * No PendingIntents — the placeholder's lifetime is sub-second;
     * the per-op build replaces those once the op is real.
     */
    fun placeholderForegroundFor(
        ctx: Context,
        opId: String,
        title: String,
        message: String = ctx.getString(R.string.operation_starting),
    ): Pair<Int, Notification> {
        ensureChannel(ctx)
        return notificationIdFor(opId) to buildNotificationContent(
            ctx = ctx,
            opId = opId,
            title = title,
            message = message,
            withActions = false,
        )
    }

    private fun reconcile(ops: Map<String, Operation>) {
        // Spawn collectors for newly-registered ops.
        for ((id, op) in ops) {
            liveOps[id] = op
            if (id !in collectors) {
                collectors[id] = scope.launch { collectFor(op) }
            }
        }
        // Cancel collectors and the ongoing notification for departed
        // ops, then raise a result notification if it ended terminal.
        val gone = collectors.keys - ops.keys
        for (id in gone) {
            collectors.remove(id)?.cancel()
            val ctx = appContext ?: continue
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: continue
            nm.cancel(notificationIdFor(id))
            // Read the final state off the op rather than from the
            // collector's last emit: `conflate` in [collectFor] means a
            // terminal emit can still be queued when we cancel the job,
            // whereas a StateFlow's value is current no matter who has
            // collected it (and [MutableOperation.publish] is
            // synchronous, so terminal-then-unregister is ordered).
            val op = liveOps.remove(id) ?: continue
            val last = op.progress.value
            if (last.stage.isTerminal) postCompletion(ctx, op, last)
        }
    }

    /**
     * Post the "it finished" notification for [op]. Skipped when the
     * user is already looking at the app — the result is on screen, so
     * a notification would be noise (this is also what makes a
     * user-initiated cancel silent), and skipped when notifications
     * are turned off for the app anyway.
     *
     * Fire-and-forget: the operation is already over by the time we
     * get here, so a failed post is logged and swallowed.
     */
    private fun postCompletion(ctx: Context, op: Operation, p: OperationProgress) {
        if (AppVisibility.isForeground) return
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        try {
            ctx.getSystemService(NotificationManager::class.java)
                ?.notify(resultIdFor(op.id), buildCompletionNotification(ctx, op, p))
        } catch (t: Throwable) {
            Log.w(TAG, "completion notification for ${op.id} failed", t)
        }
    }

    private suspend fun collectFor(op: Operation) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // Throttle: a fast-cached download or pacman line stream can
        // emit progress at hundreds of hertz; Android's notifier rate-
        // limits and silently drops everything past ~5 Hz, so most of
        // the work was wasted. distinctUntilChanged drops repeat-content
        // emits (eg. the same "Installing base packages…" message
        // re-published with a new InstallStage internal field), conflate
        // collapses bursts to the latest, and the post-emit delay keeps
        // us under the system's drop rate. Terminal stages skip the
        // delay so the final "Installed"/"Failed"/"Deleted" message
        // never sits behind a 250 ms timer.
        op.progress
            .map { p -> NotificationSnapshot(p.stage.isTerminal, p.message) }
            .distinctUntilChanged()
            .conflate()
            .collect { snap ->
                nm.notify(notificationIdFor(op.id), buildNotification(ctx, op))
                if (!snap.terminal) delay(NOTIFICATION_THROTTLE_MS)
            }
    }

    private data class NotificationSnapshot(val terminal: Boolean, val message: String)
    private const val NOTIFICATION_THROTTLE_MS: Long = 250

    private fun buildNotification(ctx: Context, op: Operation): Notification =
        buildNotificationContent(
            ctx = ctx,
            opId = op.id,
            title = op.title,
            message = op.progress.value.message,
            withActions = true,
        )

    /**
     * The dismissible "it finished" notification: no Cancel action
     * (there is nothing left to cancel) and no ongoing flag, so
     * swiping it away sticks. Tapping it opens [MainActivity], which
     * is the app's own idea of "where the result lives" — for an
     * install that's the completion summary ([me.phie.tawc.MainActivity]
     * renders one on the INSTALLING → READY transition), for anything
     * else it's at least the right app.
     *
     * Same per-op data URI trick as [buildNotificationContent]: result
     * ids are a 24-bit hash of the op id too, so two ops colliding on
     * that hash would otherwise share (and rewrite) one PendingIntent.
     */
    private fun buildCompletionNotification(
        ctx: Context,
        op: Operation,
        p: OperationProgress,
    ): Notification {
        val done = p.stage == OperationStage.DONE
        val opUri = Uri.parse("tawc://operation-result/" + Uri.encode(op.id))
        val tap = PendingIntent.getActivity(
            ctx, resultIdFor(op.id),
            Intent(ctx, MainActivity::class.java)
                .setData(opUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(ctx, RESULT_CHANNEL_ID)
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(op.title)
            .setContentText(p.message)
            .setStyle(Notification.BigTextStyle().bigText(p.message))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOngoing(false)
            .setShowWhen(true)
            .build()
    }

    /**
     * Single notification builder used both by the registry-watcher
     * (with tap + Cancel actions) and by the placeholder path used by
     * services to satisfy the FGS-within-5-seconds rule (no actions —
     * the op doesn't exist yet, so the PendingIntents would target
     * nothing).
     */
    private fun buildNotificationContent(
        ctx: Context,
        opId: String,
        title: String,
        message: String,
        withActions: Boolean,
    ): Notification {
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
        if (withActions) {
            // Per-op data URI on both intents: notification ids (and so
            // the request codes) are a 24-bit hash of the op id, and on
            // a hash collision between two live ops FLAG_UPDATE_CURRENT
            // would rewrite the colliding PendingIntent's extras in
            // place (Intent.filterEquals ignores extras) — one
            // notification's Cancel then cancels the *other* op. A
            // distinct URI makes the intents non-equal, so each op
            // keeps its own PendingIntent regardless of id collisions.
            val opUri = android.net.Uri.parse("tawc://operation/" + android.net.Uri.encode(opId))
            val tap = PendingIntent.getActivity(
                ctx, notificationIdFor(opId),
                LogScreenActivity.intentFor(ctx, opId)
                    .setData(opUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val cancel = PendingIntent.getBroadcast(
                ctx, notificationIdFor(opId) xor 0x1,
                Intent(ctx, CancelOperationReceiver::class.java)
                    .setAction(CancelOperationReceiver.ACTION)
                    .setData(opUri)
                    .putExtra(CancelOperationReceiver.EXTRA_OPERATION_ID, opId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder
                .setContentIntent(tap)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.action_cancel), cancel).build())
        }
        return builder.build()
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    ctx.getString(R.string.operation_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = ctx.getString(R.string.operation_channel_description) }
            )
        }
        if (nm.getNotificationChannel(RESULT_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    ctx.getString(R.string.operation_result_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = ctx.getString(R.string.operation_result_channel_description) }
            )
        }
        // Clean up the legacy install-only channel from before the ops
        // refactor. Safe to call repeatedly; no-op if it never existed.
        try { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) } catch (_: Throwable) {}
    }

    /**
     * Notification id in the result namespace — see [RESULT_ID_PREFIX].
     */
    private fun resultIdFor(opId: String): Int =
        RESULT_ID_PREFIX or (opId.hashCode() and 0x00FF_FFFF)

    private fun notificationIdFor(opId: String): Int =
        NOTIFICATION_ID_PREFIX or (opId.hashCode() and 0x00FF_FFFF)

    private const val LEGACY_CHANNEL_ID = "tawc-install"
}

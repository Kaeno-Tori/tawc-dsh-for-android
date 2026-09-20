package me.phie.tawc.install

import android.util.Log

/**
 * Clean up install records left mid-flight by a previous app process.
 *
 * **The bug this exists for.** An install's in-progress state lives in
 * exactly one place a reader can find it — `metadata.json`'s `state` —
 * while the *operation* that owns it lives only in
 * [me.phie.tawc.ops.OperationsRegistry], a plain in-memory map. Kill the
 * app process mid-install (LMK, the user swiping the task away, a
 * crash, a reboot) and the two disagree permanently: the record still
 * says `INSTALLING`, but nothing is running.
 *
 * [me.phie.tawc.MainActivity] derives its screen from the record, so it
 * renders the progress page — and then cannot get out of it:
 *
 *  - [me.phie.tawc.MainActivity.bindOp] finds no operation, so the
 *    panel is empty and `boundOperation` is null — while the Cancel
 *    button stays *visible* (its visibility tracks the panel's own
 *    terminal flag, which `unbind()` does not touch) and does nothing
 *    when tapped.
 *  - Starting over is refused by [InstallationService], which sees
 *    `INSTALLING` and rejects the id as already in use.
 *  - Deleting the container — the one transition the state machine
 *    *does* allow out of `INSTALLING` — lives in
 *    [me.phie.tawc.install.DistroInfoActivity], which
 *    [me.phie.tawc.MainActivity] has no way to reach.
 *
 * So the user gets a screen with a dead button and no exit. Rewriting
 * the record to `FAILED` at process start drops them onto the existing
 * failure screen, whose "start over" button already does the right
 * thing (uninstall the half-written slot, then install again). No new
 * UI — the state machine already had the recovery edge; it was just
 * unreachable.
 *
 * **Why "the registry is empty" is a safe test.** `state` and the
 * operation are written by two different objects, so their ordering
 * matters and is relied on here. [InstallationService] registers the
 * operation *before* its worker writes `INSTALLING`, and on the way out
 * writes the terminal state *before* unregistering — so a live
 * operation is never absent while its record says `INSTALLING`. Both
 * orders are deliberate (see the comment in [InstallationService] about
 * registering first) and both are load-bearing for this recovery, which
 * is the fragile part worth re-reading before changing either.
 *
 * On top of that, this runs from [me.phie.tawc.TawcApplication] at
 * process start, where the registry is empty *by construction* — any
 * record in a mid-flight state at that moment is by definition from a
 * process that no longer exists. The container's service and its
 * operations live in this same process, so there is no "still running
 * elsewhere" case to miss.
 *
 * **Not tuned for the case where the install was killed and the app is
 * never opened again** — nothing would have observed the stale record
 * anyway.
 */
internal object InterruptedInstalls {

    /**
     * States that mean "an operation was in flight", i.e. the ones that
     * can be left behind by a dying process.
     *
     * `READY`/`FAILED`/`CORRUPT` are terminal (a process death there
     * loses nothing) and must be left alone — `FAILED` in particular
     * carries the real failure text, which rewriting would destroy.
     */
    fun isInterrupted(state: Installation.State): Boolean =
        state == Installation.State.INSTALLING || state == Installation.State.UNINSTALLING

    /**
     * Rewrite every interrupted record under [store] to `FAILED`, using
     * [reason] to build the message shown on the failure screen.
     *
     * Uninstall is treated the same as install on purpose: a half-erased
     * slot has no supported in-place repair either, and the "start over"
     * affordance behind `FAILED` handles it correctly (it uninstalls
     * first, which is idempotent, then reinstalls).
     *
     * Best-effort per record: one unwritable `metadata.json` must not
     * cost the user the recovery of the others. The caller
     * ([me.phie.tawc.TawcApplication]) runs this off the main thread.
     *
     * @return how many records were rewritten.
     */
    fun recover(store: InstallationStore, reason: (Installation.State) -> String): Int {
        var recovered = 0
        for (install in store.list()) {
            if (!isInterrupted(install.state)) continue
            val was = install.state
            try {
                // `failure` is overwritten wholesale, not merged: the
                // record has no text of its own to preserve here, since
                // a process that died mid-step never got to write one.
                store.setState(install.id, Installation.State.FAILED, reason(was))
                recovered++
                Log.i(TAG, "recovered interrupted record ${install.id}: $was -> FAILED")
            } catch (t: Throwable) {
                // Nothing on this path is load-bearing enough to abort
                // startup over (unlike, say, reading the metadata).
                Log.w(TAG, "failed to recover interrupted record ${install.id}", t)
            }
        }
        return recovered
    }

    private const val TAG = "tawc-install"
}

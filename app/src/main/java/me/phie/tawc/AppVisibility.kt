package me.phie.tawc

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether any of our activities is currently started, so
 * non-UI code can tell "the user is looking at the app" apart from
 * "the app is backgrounded".
 *
 * Used by [me.phie.tawc.ops.OperationsNotificationCenter] to decide
 * whether a terminal operation should raise a completion
 * notification: posting one while the user is staring at the
 * progress screen is pure noise — the screen already shows the
 * result.
 *
 * Registered once from [TawcApplication.onCreate]. The started-activity
 * counter (rather than a boolean set in onResume/onPause) is what makes
 * activity-to-activity transitions not flicker: the next activity's
 * onActivityStarted runs before the previous one's onActivityStopped.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    @Volatile
    var startedActivities: Int = 0
        private set

    val isForeground: Boolean get() = startedActivities > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

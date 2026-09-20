package me.phie.tawc.dsh

import me.phie.tawc.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [DshService.exitFailure] — the mapping from a harness exit code to
 * "what should the user be offered".
 *
 * Worth testing because both branches are plausible-looking prose on a
 * screen, and the wrong one is not visibly wrong: offering **Restart**
 * for a container that has no `dsh` in it just fails again, which reads
 * to the user as the app being broken rather than as bad advice.
 */
class DshServiceTest {

    @Test
    fun aContainerWithoutDshIsNotOfferedARestart() {
        val (messageRes, recoveries) = DshService.exitFailure(DshService.EXIT_DSH_MISSING)

        assertEquals(R.string.dsh_failed_no_harness, messageRes)
        assertEquals(listOf(DshRecovery.REINSTALL, DshRecovery.TERMINAL), recoveries)
        // The load-bearing assertion: that exit code is raised before
        // `exec`, by the script's own `command -v dsh` check, so a
        // restart runs the identical lookup and finds the identical
        // nothing.
        assertFalse(
            "restarting cannot produce a dsh that is not in the container",
            recoveries.contains(DshRecovery.RESTART),
        )
    }

    @Test
    fun anExitFromDshItselfLeadsWithARestart() {
        // 0 included on purpose: `dsh web` exiting cleanly is still a
        // harness that stopped, and restart is the first thing to try.
        for (code in listOf(0, 1, 2, 130, 137, 143)) {
            val (messageRes, recoveries) = DshService.exitFailure(code)
            assertEquals("code=$code", R.string.dsh_failed_exit, messageRes)
            assertEquals(
                "code=$code",
                listOf(DshRecovery.RESTART, DshRecovery.TERMINAL),
                recoveries,
            )
        }
    }
}

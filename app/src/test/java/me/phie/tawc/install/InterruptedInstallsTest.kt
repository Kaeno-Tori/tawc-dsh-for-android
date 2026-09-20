package me.phie.tawc.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InterruptedInstalls.isInterrupted] — the classification that decides
 * which records get rewritten at process start.
 *
 * Both directions are worth pinning. Too narrow and the user is left on
 * a progress page whose only button does nothing; too wide and a
 * container that is merely *broken* gets its real failure text
 * overwritten with "the app stopped", which is what [Installation.failure]
 * carries for the failure screen.
 */
class InterruptedInstallsTest {

    @Test
    fun midFlightStatesAreInterrupted() {
        assertTrue(InterruptedInstalls.isInterrupted(Installation.State.INSTALLING))
        assertTrue(InterruptedInstalls.isInterrupted(Installation.State.UNINSTALLING))
    }

    @Test
    fun terminalStatesAreLeftAlone() {
        // Worth their own assertions rather than a folded-in set
        // comparison: FAILED is the one a too-greedy implementation
        // would eat, and the symptom (a real error message replaced by
        // "the app stopped") is hard to connect back to here.
        assertFalse(InterruptedInstalls.isInterrupted(Installation.State.READY))
        assertFalse(InterruptedInstalls.isInterrupted(Installation.State.FAILED))
        assertFalse(InterruptedInstalls.isInterrupted(Installation.State.CORRUPT))
    }

    @Test
    fun theClassificationCoversEveryState() {
        // A new state has to be classified deliberately. If one is added
        // that *can* outlive its operation, this test is the reminder
        // that the recovery pass needs to know about it.
        val interrupted = Installation.State.values()
            .filter { InterruptedInstalls.isInterrupted(it) }
            .toSet()

        assertEquals(
            setOf(Installation.State.INSTALLING, Installation.State.UNINSTALLING),
            interrupted,
        )
    }
}

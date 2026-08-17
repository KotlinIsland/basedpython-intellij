package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a suspension is shown and when it is held back.
 *
 * The platform asks one question — is the session suspended — and holds *everything* back when the
 * answer is yes. That is right for a second thread stopping while you read the first, and wrong for
 * the thread you are already on: DAP prescribes a `stopped` event after `restartFrame` and `goto`,
 * and it means "this thread moved", not "this thread stopped". Held back, the editor stays where the
 * code no longer is — and the platform drains that queue only in `resume`, so the next Resume shows
 * the stale position instead of running the program on.
 */
class ByDapSessionTest {

    @Test
    fun `a stop while nothing is suspended is shown`() {
        assertTrue(shouldApplyNow(suspended = false, displayedThreadId = null, stoppedThreadId = 1))
        assertTrue(shouldApplyNow(suspended = false, displayedThreadId = 1, stoppedThreadId = 2))
    }

    /**
     * The bug. A restart moves the thread the user is looking at and reports it as a `stopped`; it
     * has to reach the editor now, not on some later Resume.
     */
    @Test
    fun `the thread already on screen moving is shown`() {
        assertTrue(shouldApplyNow(suspended = true, displayedThreadId = 1, stoppedThreadId = 1))
    }

    /**
     * And the case the platform's rule was written for, which must keep working: bpd holds one
     * thread and lets the rest run, so another can stop at any moment. Yanking the editor away from
     * what is being read would be worse than a delay.
     */
    @Test
    fun `a different thread stopping is held back`() {
        assertFalse(shouldApplyNow(suspended = true, displayedThreadId = 1, stoppedThreadId = 2))
    }

    /**
     * Suspended with nothing identifiable on screen: show it. There is no view to protect, and the
     * failure that matters here is a stop nobody is ever told about — the queue is only drained by a
     * Resume, so a suspension held back with nothing to resume from would be held for ever.
     */
    @Test
    fun `a suspension with no displayed thread is shown`() {
        assertTrue(shouldApplyNow(suspended = true, displayedThreadId = null, stoppedThreadId = 3))
    }

    /**
     * An unidentifiable stop while a thread is on screen is held back, which is the conservative
     * half of the same judgement: it may be another thread, and stealing the view on a guess is the
     * one outcome the user cannot undo.
     */
    @Test
    fun `a stop with no thread id is held back while one is displayed`() {
        assertFalse(shouldApplyNow(suspended = true, displayedThreadId = 1, stoppedThreadId = null))
    }
}

package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When *Reset Frame* is offered.
 *
 * The action exists here at all because the platform's DAP client never wires `restartFrame` to
 * [com.intellij.xdebugger.frame.XDropFrameHandler] — bpd implements the request and advertises the
 * capability, and nothing asks. What is under test is the one way this must still decline, since an
 * enabled action that fails is worse than a grey one: the platform swallows a failed request's
 * message, so a wrong "yes" here looks like a button that does nothing.
 */
class ByRestartFrameTest {

    /**
     * And it is asked nothing about the frame, which is the point rather than an omission. bpd
     * reaches a frame below the top by forcing the frames above it out, so a caller is as
     * restartable as the executing frame — every refusal past this one is bpd's, decided off the
     * bytecode of the frames involved and reported as a refused request. A copy of that list here
     * would be a second, slower, wrong one, and while a copy of the *old* limit lived here it kept
     * the action grey on every frame but the top long after bpd had lifted it.
     */
    @Test
    fun `an adapter that offers the request can restart any frame`() {
        assertNull(ByRestartFrame.decline(advertised = true))
    }

    /**
     * debugpy is the case: pydevd reports `supportsRestartFrame` as false. Asked of the advertised
     * capability rather than of the backend, because the wire carries the answer.
     */
    @Test
    fun `an adapter that does not offer the request declines`() {
        val why = ByRestartFrame.decline(advertised = false)
        assertNotNull(why)
        assertTrue(why!!.contains("restartFrame"), why)
    }

    /**
     * Before `initialize` is answered there is no capability yet. That is "not yet", never "no" —
     * and it has to decline, because the alternative is an action that is briefly wrong rather than
     * briefly grey.
     */
    @Test
    fun `an adapter that has not answered yet declines`() {
        assertNotNull(ByRestartFrame.decline(advertised = null))
    }
}

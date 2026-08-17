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
 * capability, and nothing asks. What is under test is the two ways this must still decline, since
 * an enabled action that fails is worse than a grey one: the platform swallows a failed request's
 * message, so a wrong "yes" here looks like a button that does nothing.
 */
class ByRestartFrameTest {

    private fun decline(advertised: Boolean? = true, executing: Boolean = true) =
        ByRestartFrame.decline(advertised, executing)

    @Test
    fun `an executing frame on an adapter that offers the request can be restarted`() {
        assertNull(decline())
    }

    /**
     * debugpy is the case: pydevd reports `supportsRestartFrame` as false. Asked of the advertised
     * capability rather than of the backend, because the wire carries the answer.
     */
    @Test
    fun `an adapter that does not offer the request declines`() {
        val why = decline(advertised = false)
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
        assertNotNull(decline(advertised = null))
    }

    /**
     * bpd refuses any frame but the executing one, and the reason is CPython's: a frame below the
     * top is suspended inside a call, and assigning to its `f_lineno` is *accepted* while leaving it
     * running on with a value stack that no longer matches. Confirmed against bpd, which answers a
     * caller's frame id with exactly that explanation.
     */
    @Test
    fun `a caller's frame declines`() {
        val why = decline(executing = false)
        assertNotNull(why)
        assertTrue(why!!.contains("executing"), why)
    }

    /** Both wrong is still one refusal, and the adapter's own limitation is the one worth saying. */
    @Test
    fun `an unsupported adapter is the reason given even for a caller's frame`() {
        assertTrue(decline(advertised = false, executing = false)!!.contains("restartFrame"))
    }
}

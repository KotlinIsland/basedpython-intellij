package dev.basedpython.pycharm.statusbar

import com.intellij.platform.lsp.api.LspServerState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's whole job is to stay quiet when things are fine and speak up when they aren't, so
 * the cases that matter most here are the ones that must *not* collapse together: a server that
 * was switched off versus one that died on its own.
 */
class ServerLightMappingTest {

    private fun light(
        enabled: Boolean = true,
        binaryMissing: Boolean = false,
        state: LspServerState? = null,
    ) = ServerLightMapping.lightFor(enabled, binaryMissing, state)

    @Test
    fun `running server is healthy`() {
        assertEquals(ServerLight.RUNNING, light(state = LspServerState.Running))
    }

    @Test
    fun `initializing server is treated as healthy, not as a problem`() {
        // Otherwise the widget would flash a failure glyph on every start.
        assertEquals(ServerLight.RUNNING, light(state = LspServerState.Initializing))
    }

    @Test
    fun `server that died on its own is a problem`() {
        assertEquals(ServerLight.PROBLEM, light(state = LspServerState.ShutdownUnexpectedly))
    }

    @Test
    fun `server we stopped cleanly is not a problem`() {
        assertEquals(ServerLight.STOPPED, light(state = LspServerState.ShutdownNormally))
    }

    @Test
    fun `missing binary is a problem`() {
        assertEquals(ServerLight.PROBLEM, light(binaryMissing = true))
    }

    @Test
    fun `disabled server is never a problem, even with a missing binary`() {
        assertEquals(ServerLight.STOPPED, light(enabled = false, binaryMissing = true))
        assertEquals(
            ServerLight.STOPPED,
            light(enabled = false, state = LspServerState.ShutdownUnexpectedly),
        )
    }

    /**
     * The regression this whole state split exists for: enabled, binary present, but no server was
     * ever created. That used to render identically to a healthy server.
     */
    @Test
    fun `enabled but never started is stopped, distinct from running`() {
        assertEquals(ServerLight.STOPPED, light(state = null))
    }
}

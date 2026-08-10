package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pydevd filter ids an exception breakpoint selects.
 *
 * Every id here was confirmed against a live debugpy session rather than read off the spec, because
 * one that looked right — `raised:ignoreLibraries` — turns out never to fire: the transpiled
 * program lives in a temp directory pydevd does not count as project code.
 */
class ByExceptionBreakpointTest {

    /**
     * Matching PyCharm, and reachable despite basedpython's checked exceptions: the compiler
     * rejects an escaping `ValueError`, but a `KeyError` from a dict lookup compiles and stops
     * here at runtime.
     */
    @Test
    fun `the default stops on uncaught exceptions only`() {
        val properties = ByExceptionBreakpointProperties()
        assertEquals(listOf("uncaught"), properties.filters())
    }

    @Test
    fun `both flags select both filters`() {
        val properties = ByExceptionBreakpointProperties().apply { notifyOnRaise = true }
        assertEquals(listOf("raised", "uncaught"), properties.filters())
    }

    /** DAP has no combined filter: `setExceptionBreakpoints` takes a list of separate ids. */
    @Test
    fun `raise alone selects only raised`() {
        val properties = ByExceptionBreakpointProperties().apply {
            notifyOnRaise = true
            notifyOnTerminate = false
        }
        assertEquals(listOf("raised"), properties.filters())
    }

    /** Turning both off must send nothing rather than a filter that means "everything". */
    @Test
    fun `no flags means no filters`() {
        val properties = ByExceptionBreakpointProperties().apply {
            notifyOnRaise = false
            notifyOnTerminate = false
        }
        assertTrue(properties.filters().isEmpty())
    }

    /** The breakpoint is persisted, so a round trip has to keep what the user chose. */
    @Test
    fun `state round-trips`() {
        val saved = ByExceptionBreakpointProperties().apply {
            notifyOnRaise = true
            notifyOnTerminate = false
        }
        val loaded = ByExceptionBreakpointProperties().apply { loadState(saved.state) }
        assertEquals(saved.filters(), loaded.filters())
    }

    /** No filter may carry pydevd's `:ignoreLibraries` suffix; with it nothing ever fires. */
    @Test
    fun `no filter asks pydevd to ignore library code`() {
        val everything = ByExceptionBreakpointProperties().apply { notifyOnRaise = true }
        assertTrue(everything.filters().none { it.contains("ignoreLibraries") })
    }
}

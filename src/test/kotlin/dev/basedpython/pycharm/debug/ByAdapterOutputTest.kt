package dev.basedpython.pycharm.debug

import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What becomes of a debug adapter's `output` events.
 *
 * Two decisions, and the first one is the one that was wrong: the events were dropped
 * unconditionally, which is right for debugpy and deletes the program's entire output under bpd —
 * bpd starts the interpreter itself and captures its streams, so `print` reaches the IDE only this
 * way. Confirmed live against a `by run` session: the program's `total=3` arrived as
 * `('stdout', 'total=3\n')` and as nothing else.
 */
class ByAdapterOutputTest {

    // ------------------------------------------------------------------
    // whether the events are ours at all
    // ------------------------------------------------------------------

    /** bpd owns the interpreter's streams, so its events are the only copy there is. */
    @Test
    fun `bpd's output events are the program's only voice`() {
        assertTrue(ByDebugBackend.BPD.ownsDebuggeeOutput)
    }

    /**
     * debugpy runs inside a process `by run` started and the console is attached to that process,
     * so printing these too would double every line — and debugpy opens each session with two bare
     * events reading `ptvsd` and `debugpy`.
     */
    @Test
    fun `debugpy's are a second copy of what the console already has`() {
        assertFalse(ByDebugBackend.DEBUGPY.ownsDebuggeeOutput)
    }

    /**
     * The question is asked of the backend rather than of `DapStartRequest`, which today picks out
     * the same one. This is the guard on the two staying distinguishable: a backend added without
     * an answer cannot compile, and one added with the *wrong* answer at least fails here.
     */
    @Test
    fun `exactly one backend owns the debuggee's output`() {
        assertEquals(
            listOf(ByDebugBackend.BPD),
            ByDebugBackend.entries.filter { it.ownsDebuggeeOutput },
        )
    }

    // ------------------------------------------------------------------
    // where each one goes
    // ------------------------------------------------------------------

    @Test
    fun `the program's own output is ordinary output`() {
        assertEquals(ByOutputRegister.NORMAL, ByAdapterOutput.registerFor(ByAdapterOutput.STDOUT))
    }

    @Test
    fun `the adapter talking about itself is system output`() {
        assertEquals(ByOutputRegister.SYSTEM, ByAdapterOutput.registerFor(ByAdapterOutput.CONSOLE))
    }

    /** DAP names `console` as the default for an omitted category, not `stdout`. */
    @Test
    fun `an omitted category is console`() {
        assertEquals(ByOutputRegister.SYSTEM, ByAdapterOutput.registerFor(null))
    }

    @Test
    fun `stderr is prominent`() {
        assertEquals(ByOutputRegister.PROMINENT, ByAdapterOutput.registerFor(ByAdapterOutput.STDERR))
    }

    /**
     * The one the platform's own mapping gets wrong, and the reason this mapping exists. DAP
     * defines `important` as what a user should see even with the console collapsed, and bpd sends
     * a blind spot in subprocess tracking, a refused code replacement and "recording is on and
     * costing four times a bare run" that way. Filed as ordinary stdout each of those scrolls past
     * under the program's own output — which is exactly what the category is for avoiding.
     */
    @Test
    fun `important is prominent rather than ordinary output`() {
        assertEquals(
            ByOutputRegister.PROMINENT,
            ByAdapterOutput.registerFor(ByAdapterOutput.IMPORTANT),
        )
    }

    /**
     * DAP's `telemetry` is data for the client, not text for a person. The platform's mapping
     * prints it, which puts adapter bookkeeping in the middle of the program's output.
     */
    @Test
    fun `telemetry is never shown`() {
        assertEquals(
            ByOutputRegister.HIDDEN,
            ByAdapterOutput.registerFor(ByAdapterOutput.TELEMETRY),
        )
    }

    /**
     * DAP lets an adapter invent a category, so meeting one means the vocabulary grew rather than
     * that something went wrong. Text of unknown importance is still text somebody wrote to be
     * read, and dropping it is the one outcome nothing later can recover from — the failure this
     * whole class exists because of.
     */
    @Test
    fun `an unknown category is shown rather than dropped`() {
        assertEquals(ByOutputRegister.NORMAL, ByAdapterOutput.registerFor("progress"))
        assertEquals(ByOutputRegister.NORMAL, ByAdapterOutput.registerFor(""))
    }

    /** Categories are matched as DAP spells them; nothing here is case-insensitive on the wire. */
    @Test
    fun `the categories are the ones DAP names`() {
        assertEquals(
            listOf("console", "important", "stdout", "stderr", "telemetry"),
            listOf(
                ByAdapterOutput.CONSOLE,
                ByAdapterOutput.IMPORTANT,
                ByAdapterOutput.STDOUT,
                ByAdapterOutput.STDERR,
                ByAdapterOutput.TELEMETRY,
            ),
        )
    }
}

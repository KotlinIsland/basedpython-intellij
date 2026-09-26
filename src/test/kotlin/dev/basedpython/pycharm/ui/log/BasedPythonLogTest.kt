package dev.basedpython.pycharm.ui.log

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.util.Disposer
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [BasedPythonLog] buffers lines emitted before the tool window is opened and flushes them when the
 * console attaches — the tool window is created lazily, so almost everything a server says arrives
 * before there is anywhere to put it.
 */
@TestFixtures
class BasedPythonLogTest {

    private val fixture by codeInsightFixture()

    private val log: BasedPythonLog get() = BasedPythonLog.getInstance(fixture.project)

    /**
     * The regression this exists for: server output must not go through [BasedPythonLog.error],
     * which calls Logger.error and raises an IDE fatal-error report. `by` logs ERROR lines for
     * ordinary problems in the user's code, and panics on some files, so routing those to
     * Logger.error would fire a dialog per line.
     *
     * The platform's TestLoggerInterceptor fails a test that logs an error, so this passing *is*
     * the assertion.
     */
    @Test
    fun `server error output does not raise an ide error`() = onEdt {
        log.serverOutput("by", "2026-01-01 00:00:00 ERROR something broke", isError = true)
        log.serverOutput("by", "request handler panicked at folding_range.rs:439", isError = true)
    }

    @Test
    fun `server output buffers before console exists and does not throw`() = onEdt {
        // No console has been created (the tool window was never opened).
        log.serverOutput("by", "INFO Version: ruff/0.15.20", isError = false)
        log.info("plugin-side line")
        // Creating the console flushes the buffer; it must not throw on replay.
        val console = log.getOrCreateConsole()
        assertNotNull(console)
        // Subsequent lines go straight to the console.
        log.serverOutput("buff", "INFO Registering workspace", isError = false)
    }

    /** A console is a disposable, and one nobody disposes outlives the project it printed for. */
    @Test
    fun `the console is disposed with the log`() = onEdt {
        val parent = Disposer.newCheckedDisposable()
        try {
            // The log is a project service and goes with the project; stand in for it with a
            // disposable of our own rather than disposing the shared light project's service.
            val stand = BasedPythonLog(fixture.project)
            Disposer.register(parent, stand)
            // Disposed exactly when the console is, being its child.
            val console = Disposer.newCheckedDisposable(stand.getOrCreateConsole())
            assertFalse(console.isDisposed)
            Disposer.dispose(parent)
            assertTrue(console.isDisposed)
        } finally {
            if (!parent.isDisposed) Disposer.dispose(parent)
        }
    }

    @Test
    fun `lines held before the window opens are bounded, newest kept`() = onEdt {
        val pending = PendingLines(capacity = 3)
        for (i in 1..5) pending.add("line $i\n", ConsoleViewContentType.NORMAL_OUTPUT)

        val (dropped, lines) = pending.drain()

        assertEquals(2, dropped)
        assertEquals(listOf("line 3\n", "line 4\n", "line 5\n"), lines.map { it.first })
        assertEquals(0 to emptyList<Pair<String, ConsoleViewContentType>>(), pending.drain())
    }

    @Test
    fun `console is reused across calls`() = onEdt {
        assertSame(
            log.getOrCreateConsole(),
            log.getOrCreateConsole(),
            "a second tool window open must not create a competing console",
        )
    }
}

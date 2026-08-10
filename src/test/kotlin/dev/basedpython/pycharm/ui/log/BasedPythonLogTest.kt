package dev.basedpython.pycharm.ui.log

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * [BasedPythonLog] buffers lines emitted before the tool window is opened and flushes them when the
 * console attaches — the tool window is created lazily, so almost everything a server says arrives
 * before there is anywhere to put it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
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
    fun `server error output does not raise an ide error`() {
        log.serverOutput("by", "2026-01-01 00:00:00 ERROR something broke", isError = true)
        log.serverOutput("by", "request handler panicked at folding_range.rs:439", isError = true)
    }

    @Test
    fun `server output buffers before console exists and does not throw`() {
        // No console has been created (the tool window was never opened).
        log.serverOutput("by", "INFO Version: ruff/0.15.20", isError = false)
        log.info("plugin-side line")
        // Creating the console flushes the buffer; it must not throw on replay.
        val console = log.getOrCreateConsole()
        assertNotNull(console)
        // Subsequent lines go straight to the console.
        log.serverOutput("buff", "INFO Registering workspace", isError = false)
    }

    @Test
    fun `console is reused across calls`() {
        assertSame(
            log.getOrCreateConsole(),
            log.getOrCreateConsole(),
            "a second tool window open must not create a competing console",
        )
    }
}

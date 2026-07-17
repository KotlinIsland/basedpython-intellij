package dev.basedpython.pycharm.ui.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [BasedPythonLog] buffers lines emitted before the tool window is opened and flushes them when the
 * console attaches — the tool window is created lazily, so almost everything a server says arrives
 * before there is anywhere to put it.
 */
class BasedPythonLogTest : BasePlatformTestCase() {

    private val log: BasedPythonLog get() = BasedPythonLog.getInstance(project)

    /**
     * The regression this exists for: server output must not go through [BasedPythonLog.error],
     * which calls Logger.error and raises an IDE fatal-error report. `by` logs ERROR lines for
     * ordinary problems in the user's code, and panics on some files, so routing those to
     * Logger.error would fire a dialog per line.
     *
     * BasePlatformTestCase fails a test that logs an error, so this passing *is* the assertion.
     */
    fun testServerErrorOutputDoesNotRaiseAnIdeError() {
        log.serverOutput("by", "2026-01-01 00:00:00 ERROR something broke", isError = true)
        log.serverOutput("by", "request handler panicked at folding_range.rs:439", isError = true)
    }

    fun testServerOutputBuffersBeforeConsoleExistsAndDoesNotThrow() {
        // No console has been created (the tool window was never opened).
        log.serverOutput("by", "INFO Version: ruff/0.15.20", isError = false)
        log.info("plugin-side line")
        // Creating the console flushes the buffer; it must not throw on replay.
        val console = log.getOrCreateConsole()
        assertNotNull(console)
        // Subsequent lines go straight to the console.
        log.serverOutput("buff", "INFO Registering workspace", isError = false)
    }

    fun testConsoleIsReusedAcrossCalls() {
        assertSame(
            "a second tool window open must not create a competing console",
            log.getOrCreateConsole(),
            log.getOrCreateConsole(),
        )
    }
}

package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What *View Collection Output* shows, which is the only answer to "why not the tests I expected". */
class ByCollectionOutputTest {

    private val run = ByCollectionRun(
        commandLine = "/p/.venv/bin/by run pytest --collect-only -q",
        workingDirectory = "/p",
        exitCode = 5,
        stdout = "no tests collected in 0.01s",
        stderr = "",
        durationMillis = 1234,
        startedAt = "09:41:07",
    )

    @Test
    fun `the command, where it ran and how it ended come first`() {
        val text = ByCollectionOutput.render(run)
        assertTrue(text.startsWith("$ /p/.venv/bin/by run pytest --collect-only -q"), text)
        assertTrue(text.contains("working directory: /p"), text)
        assertTrue(text.contains("exit code 5"), text)
        assertTrue(text.contains("1234 ms"), text)
        // The tab is a snapshot; the stamp is how a stale one admits it.
        assertTrue(text.contains("started at 09:41:07"), text)
    }

    @Test
    fun `both streams are shown, empty ones said so rather than left blank`() {
        val text = ByCollectionOutput.render(run)
        assertTrue(text.contains("--- stdout ---\nno tests collected in 0.01s"), text)
        assertTrue(text.contains("--- stderr ---\n(empty)"), text)
    }

    @Test
    fun `the footer explains what makes this differ from running pytest by hand`() {
        val text = ByCollectionOutput.render(run)
        // The three differences that actually bite: rootdir, .py files, and the interpreter.
        assertTrue(text.contains("rootdir"), text)
        assertTrue(text.contains("only .by files are transpiled"), text)
        assertTrue(text.contains("pytest importable"), text)
    }

    @Test
    fun `a run that never started says so instead of reporting an exit code`() {
        val text = ByCollectionOutput.render(run.copy(failure = "by binary not found"))
        assertTrue(text.contains("did not run: by binary not found"), text)
        assertFalse(text.contains("exit code"), text)
        // Nor does the footer apply: nothing ran, so nothing about pytest explains it.
        assertFalse(text.contains("rootdir"), text)
    }

    @Test
    fun `with no collection yet it says what to press`() {
        val text = ByCollectionOutput.render(null)
        assertTrue(text.contains("Refresh"), text)
    }
}

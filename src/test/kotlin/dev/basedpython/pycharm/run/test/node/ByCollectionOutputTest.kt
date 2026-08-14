package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What *View Collection Output* shows, which is the only answer to "why not the tests I expected". */
class ByCollectionOutputTest {

    private val run = ByCollectionRun(
        label = "by run pytest",
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
        val text = ByCollectionOutput.render(listOf(run))
        assertTrue(text.startsWith("=== by run pytest ==="), text)
        assertTrue(text.contains("$ /p/.venv/bin/by run pytest --collect-only -q"), text)
        assertTrue(text.contains("working directory: /p"), text)
        assertTrue(text.contains("exit code 5"), text)
        assertTrue(text.contains("1234 ms"), text)
        // The tab is a snapshot; the stamp is how a stale one admits it.
        assertTrue(text.contains("started at 09:41:07"), text)
    }

    @Test
    fun `both streams are shown, empty ones said so rather than left blank`() {
        val text = ByCollectionOutput.render(listOf(run))
        assertTrue(text.contains("--- stdout ---\nno tests collected in 0.01s"), text)
        assertTrue(text.contains("--- stderr ---\n(empty)"), text)
    }

    @Test
    fun `the footer explains why there are two runs and what each one misses`() {
        val text = ByCollectionOutput.render(listOf(run))
        assertTrue(text.contains("rootdir"), text)
        assertTrue(text.contains("tests written in .py are not"), text)
        assertTrue(text.contains("ignores out/"), text)
        assertTrue(text.contains("pytest importable"), text)
    }

    @Test
    fun `both halves are shown, each under its own heading`() {
        val plain = run.copy(
            label = "plain pytest",
            commandLine = "/p/.venv/bin/python -m pytest --collect-only -q --ignore=out",
            exitCode = 0,
            stdout = "test_main.py::test_asdf",
        )
        val text = ByCollectionOutput.render(listOf(run, plain))
        assertTrue(text.contains("=== by run pytest ==="), text)
        assertTrue(text.contains("=== plain pytest ==="), text)
        assertTrue(text.contains("test_main.py::test_asdf"), text)
        // The footer explains the pair, so it belongs once, at the end.
        assertEquals(1, Regex("why two runs").findAll(text).count(), text)
    }

    @Test
    fun `a run that never started says so instead of reporting an exit code`() {
        val text = ByCollectionOutput.render(listOf(run.copy(failure = "by binary not found")))
        assertTrue(text.contains("did not run: by binary not found"), text)
        assertFalse(text.contains("exit code"), text)
    }

    @Test
    fun `with no collection yet it says what to press`() {
        val text = ByCollectionOutput.render(emptyList())
        assertTrue(text.contains("Refresh"), text)
    }
}

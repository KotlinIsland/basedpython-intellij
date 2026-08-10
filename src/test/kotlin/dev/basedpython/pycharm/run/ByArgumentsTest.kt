package dev.basedpython.pycharm.run

import junit.framework.TestCase

/**
 * Argument order for the `by` CLI.
 *
 * `by` is a `clap` multi-command binary: `--min-version` belongs to `run`/`build`/`transpile`, not
 * to `by` itself, so it only parses when it follows the subcommand. The old command line put it
 * first and every run configuration with a Python version set died with
 * `error: unexpected argument '--min-version' found`.
 */
class ByArgumentsTest : TestCase() {

    fun `test the version flag follows the subcommand, not the executable`() {
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "3.14",
            subcommandArgs = listOf("main"),
            extraArgs = "",
        )
        assertEquals(listOf("run", "--min-version", "3.14", "main"), args)
    }

    fun `test a blank version emits no flag`() {
        val args = byArguments("run", "--min-version", "   ", listOf("main"), "")
        assertEquals(listOf("run", "main"), args)
    }

    fun `test the version is trimmed`() {
        val args = byArguments("build", "--min-version", " 3.12 ", emptyList(), "")
        assertEquals(listOf("build", "--min-version", "3.12"), args)
    }

    fun `test a subcommand with no version flag never emits one`() {
        // Not every subcommand takes a version flag; the value is still persisted on the
        // shared options either way.
        val args = byArguments("test", null, "3.14", listOf("tests"), "")
        assertEquals(listOf("test", "tests"), args)
    }

    fun `test check spells the version differently`() {
        // `by check` resolves types rather than emitting code, so it takes --python-version.
        val args = byArguments("check", "--python-version", "3.13", listOf("src"), "")
        assertEquals(listOf("check", "--python-version", "3.13", "src"), args)
    }

    fun `test extra args come last and are split like a shell`() {
        val args = byArguments("run", "--min-version", "3.14", listOf("main"), "--soundness none")
        assertEquals(listOf("run", "--min-version", "3.14", "main", "--soundness", "none"), args)
    }

    fun `test quoted extra args stay one argument`() {
        val args = byArguments("check", null, "", emptyList(), """--exclude "a b/**"""")
        assertEquals(listOf("check", "--exclude", "a b/**"), args)
    }
}

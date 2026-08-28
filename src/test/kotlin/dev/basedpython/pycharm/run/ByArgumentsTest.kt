package dev.basedpython.pycharm.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Argument order for the `by` CLI.
 *
 * `by` is a `clap` multi-command binary: `--min-version` belongs to `run`/`build`/`transpile`, not
 * to `by` itself, so it only parses when it follows the subcommand. The old command line put it
 * first and every run configuration with a Python version set died with
 * `error: unexpected argument '--min-version' found`.
 */
class ByArgumentsTest {

    @Test
    fun `the version flag follows the subcommand, not the executable`() {
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "3.14",
            subcommandArgs = listOf("main"),
            extraArgs = "",
        )
        assertEquals(listOf("run", "--min-version", "3.14", "main"), args)
    }

    @Test
    fun `a blank version emits no flag`() {
        val args = byArguments("run", "--min-version", "   ", listOf("main"), "")
        assertEquals(listOf("run", "main"), args)
    }

    @Test
    fun `the version is trimmed`() {
        val args = byArguments("build", "--min-version", " 3.12 ", emptyList(), "")
        assertEquals(listOf("build", "--min-version", "3.12"), args)
    }

    @Test
    fun `a subcommand with no version flag never emits one`() {
        // Not every subcommand takes a version flag; the value is still persisted on the
        // shared options either way.
        val args = byArguments("test", null, "3.14", listOf("tests"), "")
        assertEquals(listOf("test", "tests"), args)
    }

    @Test
    fun `check spells the version differently`() {
        // `by check` resolves types rather than emitting code, so it takes --python-version.
        val args = byArguments("check", "--python-version", "3.13", listOf("src"), "")
        assertEquals(listOf("check", "--python-version", "3.13", "src"), args)
    }

    @Test
    fun `the debugger's flags come before the module, never after it`() {
        // `by run` forwards everything after the module to the program, so a `--python` behind it
        // would reach the debuggee as an argument instead of `by` as an option — and the wrapper
        // would never be started at all.
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "3.14",
            subcommandArgs = listOf("main", "--user-flag"),
            extraArgs = "",
            infrastructureArgs = listOf("--python", "/tmp/bpd-python"),
        )
        assertEquals(
            listOf("run", "--min-version", "3.14", "--python", "/tmp/bpd-python", "main", "--user-flag"),
            args,
        )
    }

    @Test
    fun `a run with no debugger attached carries no extra flags`() {
        val args = byArguments("run", "--min-version", "3.14", listOf("main"), "")
        assertEquals(listOf("run", "--min-version", "3.14", "main"), args)
    }

    @Test
    fun `extra args come last and are split like a shell`() {
        val args = byArguments("run", "--min-version", "3.14", listOf("main"), "--soundness none")
        assertEquals(listOf("run", "--min-version", "3.14", "main", "--soundness", "none"), args)
    }

    @Test
    fun `quoted extra args stay one argument`() {
        val args = byArguments("check", null, "", emptyList(), """--exclude "a b/**"""")
        assertEquals(listOf("check", "--exclude", "a b/**"), args)
    }

    @Test
    fun `a run passes the module first and the program's arguments after it`() {
        // `by run` takes one positional; everything past it is the program's `sys.argv[1:]`.
        val options = ByRunOptions().apply {
            module = " pkg.main "
            programArgs = """--name "two words" --count 3"""
        }
        assertEquals(listOf("pkg.main", "--name", "two words", "--count", "3"), runSubcommandArgs(options))
    }

    @Test
    fun `a run with no program arguments is just the module`() {
        assertEquals(listOf("pkg.main"), runSubcommandArgs(ByRunOptions().apply { module = "pkg.main" }))
    }
}

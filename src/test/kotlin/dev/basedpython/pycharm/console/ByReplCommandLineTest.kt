package dev.basedpython.pycharm.console

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Process-free unit tests for [ByReplCommandLine]. These never spawn a process —
 * they only assert on the deterministic argument vector / command line that the
 * pure builder produces from a resolved launch + settings.
 */
class ByReplCommandLineTest {

    private val byExe = Paths.get("/opt/venv/bin/by")

    /** A plain, directly-executable launch — no argument prefix, no activation. */
    private fun launchOf(
        exe: Path,
        prependArgs: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ) = ByLaunch(exe, prependArgs, env, venvRoot = null, kind = ByEnvironmentKind.PATH)

    @Test
    fun `default subcommand is repl`() {
        assertEquals("repl", ByReplCommandLine.DEFAULT_SUBCOMMAND)
    }

    @Test
    fun `fallback subcommand is run`() {
        assertEquals("run", ByReplCommandLine.FALLBACK_SUBCOMMAND)
    }

    @Test
    fun `parameters with default subcommand no extra args`() {
        val params = ByReplCommandLine.parameters("repl", "")
        assertEquals(listOf("repl"), params)
    }

    @Test
    fun `parameters with fallback subcommand`() {
        val params = ByReplCommandLine.parameters("run", "")
        assertEquals(listOf("run"), params)
    }

    @Test
    fun `blank subcommand is omitted`() {
        val params = ByReplCommandLine.parameters("", "")
        assertTrue(params.isEmpty(), "blank subcommand should yield no parameters")
    }

    @Test
    fun `blank subcommand with extra args`() {
        val params = ByReplCommandLine.parameters("   ", "--verbose")
        assertEquals(listOf("--verbose"), params)
    }

    @Test
    fun `extra args are split honoring quotes`() {
        val params = ByReplCommandLine.parameters("repl", "--flag \"a b\" --x=1")
        assertEquals(listOf("repl", "--flag", "a b", "--x=1"), params)
    }

    @Test
    fun `subcommand is trimmed`() {
        val params = ByReplCommandLine.parameters("  repl  ", "")
        assertEquals(listOf("repl"), params)
    }

    @Test
    fun `blank extra args adds nothing`() {
        val params = ByReplCommandLine.parameters("repl", "    ")
        assertEquals(listOf("repl"), params)
    }

    @Test
    fun `build sets exe path`() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "")
        assertEquals(byExe.toString(), cmd.exePath)
    }

    @Test
    fun `build sets charset utf8`() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "")
        assertEquals(Charsets.UTF_8, cmd.charset)
    }

    @Test
    fun `build parameters match parameters function`() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "--verbose")
        assertEquals(listOf("repl", "--verbose"), cmd.parametersList.parameters)
    }

    @Test
    fun `build with work dir`() {
        val wd = Paths.get("/work/dir")
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "", wd)
        assertNotNull(cmd.workDirectory)
        assertEquals(wd.toFile(), cmd.workDirectory)
    }

    @Test
    fun `build without work dir leaves it unset`() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "", null)
        // No work directory configured -> null (inherits from launcher).
        assertNull(cmd.workDirectory)
    }

    @Test
    fun `build with fallback subcommand`() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),ByReplCommandLine.FALLBACK_SUBCOMMAND, "")
        assertEquals(listOf("run"), cmd.parametersList.parameters)
    }

    @Test
    fun `build puts launch prefix before the subcommand`() {
        // For a uv launch the exe is `uv` and the prefix names `by`. If `repl` came first this
        // would run `uv repl` — a uv subcommand, not the basedpython REPL.
        val uv = Paths.get("/usr/local/bin/uv")
        val cmd = ByReplCommandLine.build(
            launchOf(uv, prependArgs = listOf("run", "--project", "/w", "by")),
            "repl",
            "--verbose",
        )
        assertEquals(uv.toString(), cmd.exePath)
        assertEquals(
            listOf("run", "--project", "/w", "by", "repl", "--verbose"),
            cmd.parametersList.parameters.toList(),
        )
    }

    @Test
    fun `build carries the activation environment`() {
        val cmd = ByReplCommandLine.build(
            launchOf(byExe, env = mapOf("VIRTUAL_ENV" to "/opt/venv")),
            "repl",
            "",
        )
        assertEquals("/opt/venv", cmd.environment["VIRTUAL_ENV"])
    }

    @Test
    fun `preview default`() {
        assertEquals("by repl", ByReplCommandLine.preview("repl", ""))
    }

    @Test
    fun `preview with extra args`() {
        assertEquals("by repl --verbose", ByReplCommandLine.preview("repl", "--verbose"))
    }

    @Test
    fun `preview bare when blank subcommand`() {
        assertEquals("by", ByReplCommandLine.preview("", ""))
    }
}

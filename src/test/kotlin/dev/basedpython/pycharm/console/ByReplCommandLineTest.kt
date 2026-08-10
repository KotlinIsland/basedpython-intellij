package dev.basedpython.pycharm.console

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import junit.framework.TestCase
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Process-free unit tests for [ByReplCommandLine]. These never spawn a process —
 * they only assert on the deterministic argument vector / command line that the
 * pure builder produces from a resolved launch + settings.
 */
class ByReplCommandLineTest : TestCase() {

    private val byExe = Paths.get("/opt/venv/bin/by")

    /** A plain, directly-executable launch — no argument prefix, no activation. */
    private fun launchOf(
        exe: Path,
        prependArgs: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
    ) = ByLaunch(exe, prependArgs, env, venvRoot = null, kind = ByEnvironmentKind.PATH)

    fun testDefaultSubcommandIsRepl() {
        assertEquals("repl", ByReplCommandLine.DEFAULT_SUBCOMMAND)
    }

    fun testFallbackSubcommandIsRun() {
        assertEquals("run", ByReplCommandLine.FALLBACK_SUBCOMMAND)
    }

    fun testParametersWithDefaultSubcommandNoExtraArgs() {
        val params = ByReplCommandLine.parameters("repl", "")
        assertEquals(listOf("repl"), params)
    }

    fun testParametersWithFallbackSubcommand() {
        val params = ByReplCommandLine.parameters("run", "")
        assertEquals(listOf("run"), params)
    }

    fun testBlankSubcommandIsOmitted() {
        val params = ByReplCommandLine.parameters("", "")
        assertTrue("blank subcommand should yield no parameters", params.isEmpty())
    }

    fun testBlankSubcommandWithExtraArgs() {
        val params = ByReplCommandLine.parameters("   ", "--verbose")
        assertEquals(listOf("--verbose"), params)
    }

    fun testExtraArgsAreSplitHonoringQuotes() {
        val params = ByReplCommandLine.parameters("repl", "--flag \"a b\" --x=1")
        assertEquals(listOf("repl", "--flag", "a b", "--x=1"), params)
    }

    fun testSubcommandIsTrimmed() {
        val params = ByReplCommandLine.parameters("  repl  ", "")
        assertEquals(listOf("repl"), params)
    }

    fun testBlankExtraArgsAddsNothing() {
        val params = ByReplCommandLine.parameters("repl", "    ")
        assertEquals(listOf("repl"), params)
    }

    fun testBuildSetsExePath() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "")
        assertEquals(byExe.toString(), cmd.exePath)
    }

    fun testBuildSetsCharsetUtf8() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "")
        assertEquals(Charsets.UTF_8, cmd.charset)
    }

    fun testBuildParametersMatchParametersFunction() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "--verbose")
        assertEquals(listOf("repl", "--verbose"), cmd.parametersList.parameters)
    }

    fun testBuildWithWorkDir() {
        val wd = Paths.get("/work/dir")
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "", wd)
        assertNotNull(cmd.workDirectory)
        assertEquals(wd.toFile(), cmd.workDirectory)
    }

    fun testBuildWithoutWorkDirLeavesItUnset() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),"repl", "", null)
        // No work directory configured -> null (inherits from launcher).
        assertNull(cmd.workDirectory)
    }

    fun testBuildWithFallbackSubcommand() {
        val cmd = ByReplCommandLine.build(launchOf(byExe),ByReplCommandLine.FALLBACK_SUBCOMMAND, "")
        assertEquals(listOf("run"), cmd.parametersList.parameters)
    }

    fun testBuildPutsLaunchPrefixBeforeTheSubcommand() {
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

    fun testBuildCarriesTheActivationEnvironment() {
        val cmd = ByReplCommandLine.build(
            launchOf(byExe, env = mapOf("VIRTUAL_ENV" to "/opt/venv")),
            "repl",
            "",
        )
        assertEquals("/opt/venv", cmd.environment["VIRTUAL_ENV"])
    }

    fun testPreviewDefault() {
        assertEquals("by repl", ByReplCommandLine.preview("repl", ""))
    }

    fun testPreviewWithExtraArgs() {
        assertEquals("by repl --verbose", ByReplCommandLine.preview("repl", "--verbose"))
    }

    fun testPreviewBareWhenBlankSubcommand() {
        assertEquals("by", ByReplCommandLine.preview("", ""))
    }
}

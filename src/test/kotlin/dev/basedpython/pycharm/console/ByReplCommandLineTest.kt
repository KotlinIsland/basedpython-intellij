package dev.basedpython.pycharm.console

import junit.framework.TestCase
import java.nio.file.Paths

/**
 * Process-free unit tests for [ByReplCommandLine]. These never spawn a process —
 * they only assert on the deterministic argument vector / command line that the
 * pure builder produces from a binary path + settings.
 */
class ByReplCommandLineTest : TestCase() {

    private val byExe = Paths.get("/opt/venv/bin/by")

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
        val cmd = ByReplCommandLine.build(byExe, "repl", "")
        assertEquals(byExe.toString(), cmd.exePath)
    }

    fun testBuildSetsCharsetUtf8() {
        val cmd = ByReplCommandLine.build(byExe, "repl", "")
        assertEquals(Charsets.UTF_8, cmd.charset)
    }

    fun testBuildParametersMatchParametersFunction() {
        val cmd = ByReplCommandLine.build(byExe, "repl", "--verbose")
        assertEquals(listOf("repl", "--verbose"), cmd.parametersList.parameters)
    }

    fun testBuildWithWorkDir() {
        val wd = Paths.get("/work/dir")
        val cmd = ByReplCommandLine.build(byExe, "repl", "", wd)
        assertNotNull(cmd.workDirectory)
        assertEquals(wd.toFile(), cmd.workDirectory)
    }

    fun testBuildWithoutWorkDirLeavesItUnset() {
        val cmd = ByReplCommandLine.build(byExe, "repl", "", null)
        // No work directory configured -> null (inherits from launcher).
        assertNull(cmd.workDirectory)
    }

    fun testBuildWithFallbackSubcommand() {
        val cmd = ByReplCommandLine.build(byExe, ByReplCommandLine.FALLBACK_SUBCOMMAND, "")
        assertEquals(listOf("run"), cmd.parametersList.parameters)
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

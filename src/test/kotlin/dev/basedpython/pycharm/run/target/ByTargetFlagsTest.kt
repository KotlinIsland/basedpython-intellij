package dev.basedpython.pycharm.run.target

import com.intellij.execution.process.ProcessOutput
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a run target's `by` answers to `by command-line-flags`, read as the flags it takes.
 *
 * The table is the one its language server answers `initialize` with (`by` 4a8b90853 and later print it
 * on one line); a `by` from before the command refuses it as clap refuses any unknown command, with
 * exit 2 and nothing on standard output.
 */
class ByTargetFlagsTest {

    private fun output(stdout: String, exitCode: Int, timeout: Boolean = false) =
        ProcessOutput(stdout, "", exitCode, timeout, false)

    @Test
    fun `a by that prints inPlace among run's flags runs the program in place`() {
        val flags = ByTargetFlags.read(
            output(
                """{"repl":{"events":true,"inPlace":true,"module":true,"sessionFile":true},""" +
                    """"run":{"coverage":true,"inPlace":true,"launcher":true,"notes":true,"profile":true}}""" + "\n",
                exitCode = 0,
            ),
        )
        assertTrue(flags.runsInPlace)
        assertTrue(flags.replsInPlace)
    }

    @Test
    fun `a by whose table does not name inPlace stages it`() {
        assertFalse(ByTargetFlags.read(output("""{"run":{"launcher":true}}""", exitCode = 0)).runsInPlace)
    }

    @Test
    fun `a by from before the command, which refuses it, says nothing`() {
        val refused = ProcessOutput("", "error: unrecognized subcommand 'command-line-flags'\n", 2, false, false)
        assertFalse(ByTargetFlags.read(refused).runsInPlace)
    }

    /** Only a `by` that ran the command answers it: what a failing one printed is not its answer. */
    @Test
    fun `a failed or unfinished answer says nothing, whatever it printed`() {
        val table = """{"run":{"inPlace":true}}"""
        assertFalse(ByTargetFlags.read(output(table, exitCode = 1)).runsInPlace)
        assertFalse(ByTargetFlags.read(output(table, exitCode = -1, timeout = true)).runsInPlace)
    }

    @Test
    fun `output that is not a json object says nothing`() {
        for (stdout in listOf("", "by 0.0.1\n", "[]", "{not json", "true")) {
            assertFalse(ByTargetFlags.read(output(stdout, exitCode = 0)).runsInPlace, stdout)
        }
    }
}

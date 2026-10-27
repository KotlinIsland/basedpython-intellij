package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ending `by`'s process kills it, except when what it ran has already ended — the debug session the
 * platform ends when the program finishes, whose kill turned every finished debug run into "exit
 * code 137 (SIGKILL)".
 */
@TestFixtures
class ByProcessHandlerTest {

    // The coloured handler needs an application.
    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @Test
    fun `ending a process whose program is still running kills it`() {
        val handler = started("sleep 600") { false }

        handler.destroyProcess()

        assertTrue(handler.waitFor(10_000), "the process was not killed")
        assertNotEquals(0, handler.exitCode)
    }

    @Test
    fun `ending a process whose program has ended leaves it to exit with its own code`() {
        val handler = started("sleep 1; exit 3") { true }

        handler.destroyProcess()

        assertTrue(handler.isProcessTerminating, "a process left to finish should read as ending")
        assertTrue(handler.waitFor(10_000))
        assertEquals(3, handler.exitCode, "the program's own exit code, not a signal's")
    }

    @Test
    fun `a second stop kills a process that was left to finish`() {
        val handler = started("sleep 600") { true }
        handler.destroyProcess()
        assertFalse(handler.waitFor(300), "the first request should have left it running")

        // What Stop does to a process that is still terminating.
        handler.killProcess()

        assertTrue(handler.waitFor(10_000), "the process was not killed")
    }

    @Test
    fun `whether the program has ended is read before the end is announced`() {
        // Stop's announcement is what stops the debug session; a session stopped by it is not one
        // that ended first, so what the announcement changes must not decide the kill.
        var sessionOver = false
        val handler = started("sleep 600") { sessionOver }
        handler.addProcessListener(object : ProcessListener {
            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                sessionOver = true
            }
        })

        handler.destroyProcess()

        assertTrue(handler.waitFor(10_000), "Stop left the program running")
    }

    private fun started(script: String, finishesOnItsOwn: () -> Boolean): ByProcessHandler =
        ByProcessHandler(GeneralCommandLine("sh", "-c", script), finishesOnItsOwn).also { it.startNotify() }
}

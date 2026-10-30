package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

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

    @TempDir
    lateinit var dir: Path

    /**
     * `by run` as its contract has it: it passes a SIGTERM on to the program it started and exits
     * with the program's status. The program counts what reaches it and ends a moment after the
     * first, so a second signal, sent to it directly, would be counted too.
     */
    @Test
    fun `Stop is one SIGTERM to by alone, which by passes on, and the exit code is the program's`() {
        val program = dir.resolve("program.sh").apply {
            writeText(
                """
                n=0
                trap 'n=${'$'}((n+1)); echo "program got TERM ${'$'}n"' TERM
                echo ready
                while [ "${'$'}n" -eq 0 ]; do sleep 0.05; done
                sleep 1
                exit 5
                """.trimIndent() + "\n",
            )
        }
        val by = dir.resolve("by.sh").apply {
            writeText(
                """
                sh '${'$'}program' &
                child=${'$'}!
                trap 'echo "by got TERM"; kill -TERM "${'$'}child"; wait "${'$'}child"; exit ${'$'}?' TERM
                wait "${'$'}child"
                """.trimIndent().replace("${'$'}program", program.toString()) + "\n",
            )
        }
        val output = StringBuffer()
        val handler = ByProcessHandler(GeneralCommandLine("sh", by.toString())) { false }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        val deadline = System.currentTimeMillis() + 10_000
        while ("ready" !in output && System.currentTimeMillis() < deadline) Thread.sleep(20)

        handler.destroyProcess()

        assertTrue(handler.waitFor(10_000), "Stop left the program running: $output")
        val lines = output.lines()
        assertEquals(1, lines.count { it == "by got TERM" }, "exit ${handler.exitCode}: $output")
        assertEquals(listOf("program got TERM 1"), lines.filter { it.startsWith("program got") }, output.toString())
        assertEquals(5, handler.exitCode, output.toString())
    }

    /**
     * What `by run` starts under bpd is bpd, which ends on a SIGTERM without passing it on (measured
     * against bpd 0.0.1-a3): a SIGTERM to `by` would leave the program running with no parent. So
     * when the signal does not reach the program, Stop destroys the tree.
     */
    @Test
    fun `when a SIGTERM would not reach the program, Stop leaves nothing of the tree running`() {
        val pidFile = dir.resolve("program.pid")
        // the launcher in front of the program: it ends on a SIGTERM and passes nothing on
        val by = dir.resolve("by.sh").apply {
            writeText(
                """
                sh -c 'trap "" TERM; echo ${'$'}${'$'} > "$pidFile"; while :; do sleep 0.05; done' &
                wait
                """.trimIndent() + "\n",
            )
        }
        val handler = ByProcessHandler(GeneralCommandLine("sh", by.toString()), signalReachesProgram = false) { false }
        handler.startNotify()
        val deadline = System.currentTimeMillis() + 10_000
        while (!java.nio.file.Files.isRegularFile(pidFile) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(100)
        val program = ProcessHandle.of(pidFile.toFile().readText().trim().toLong()).get()

        handler.destroyProcess()

        assertTrue(handler.waitFor(10_000), "Stop left the launcher running")
        assertTrue(program.onExit().completeOnTimeout(program, 10, java.util.concurrent.TimeUnit.SECONDS).get().let { !it.isAlive }, "Stop left the program running")
    }

    private fun started(script: String, finishesOnItsOwn: () -> Boolean): ByProcessHandler =
        ByProcessHandler(GeneralCommandLine("sh", "-c", script), finishesOnItsOwn = finishesOnItsOwn).also { it.startNotify() }
}

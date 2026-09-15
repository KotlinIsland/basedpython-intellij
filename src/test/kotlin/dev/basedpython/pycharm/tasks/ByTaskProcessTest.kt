package dev.basedpython.pycharm.tasks

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Stop on a hook task stops the hooks, not only the runner.
 *
 * pre-commit, prek and lefthook all run each hook as a child process — and `pw` is a shell script in
 * front of all of that — so the process the IDE holds is rarely the one doing the work.
 */
@TestFixtures
class ByTaskProcessTest {

    // The coloured handler needs an application.
    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var dir: Path

    @Test
    fun `stopping a task kills what the runner started`() {
        val shell = dir.resolve("shell.pid")
        val child = dir.resolve("child.pid")
        val handler = taskProcessHandler(
            GeneralCommandLine("sh", "-c", "echo $$ > '$shell'; sleep 600 & echo $! > '$child'; wait"),
        )
        handler.startNotify()
        val deadline = System.nanoTime() + 10_000_000_000
        while (!(Files.isRegularFile(child) && Files.readString(child).isNotBlank())) {
            check(System.nanoTime() < deadline) { "the task never started" }
            Thread.sleep(20)
        }
        val shellPid = Files.readString(shell).trim().toLong()
        val childPid = Files.readString(child).trim().toLong()
        assertTrue(alive(childPid))

        // What the Stop button does.
        handler.destroyProcess()

        assertTrue(handler.waitFor(10_000), "the runner did not stop")
        val gone = System.nanoTime() + 10_000_000_000
        while (alive(childPid) && System.nanoTime() < gone) Thread.sleep(20)
        assertFalse(alive(shellPid))
        assertFalse(alive(childPid), "a hook outlived Stop")
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}

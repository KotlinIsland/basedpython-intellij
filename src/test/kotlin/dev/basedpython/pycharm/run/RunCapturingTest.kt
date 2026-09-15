package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.GeneralCommandLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** [runCapturing] against real processes: what it returns, and what cancelling it leaves behind. */
class RunCapturingTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `it returns the exit code and what was printed`() = runBlocking {
        val output = runCapturing(GeneralCommandLine("sh", "-c", "echo out; echo err >&2; exit 3"))

        assertEquals(3, output.exitCode)
        assertEquals("out\n", output.stdout)
        assertEquals("err\n", output.stderr)
    }

    @Test
    fun `cancelling it kills the process and everything the process started`() = runBlocking {
        val shell = dir.resolve("shell.pid")
        val child = dir.resolve("child.pid")
        val cmd = GeneralCommandLine("sh", "-c", "echo $$ > '$shell'; sleep 600 & echo $! > '$child'; wait")

        val run = async(Dispatchers.IO) { runCapturing(cmd) }
        val (shellPid, childPid) = withTimeout(10.seconds) {
            while (!(Files.isRegularFile(child) && Files.readString(child).isNotBlank())) delay(20)
            Files.readString(shell).trim().toLong() to Files.readString(child).trim().toLong()
        }
        assertTrue(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))

        run.cancel()

        withTimeout(10.seconds) {
            while (alive(shellPid) || alive(childPid)) delay(20)
        }
        assertFalse(alive(childPid), "the build's own children must not outlive a cancel")
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}

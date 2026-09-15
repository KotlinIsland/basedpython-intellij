package dev.basedpython.pycharm.env.manager

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * How a command is run: what comes back, and what happens to the process when nobody is waiting for
 * it any more.
 *
 * The cancellation case is the one this exists for. *Sync* runs in a cancellable background task, and
 * the wait used to be a single fifteen-minute block that never looked at the indicator — so pressing
 * the task's stop button closed the progress bar and left `uv` resolving and installing behind it.
 */
@TestFixtures
class EnvRunnerTest {

    /**
     * For the application [ProgressManager] needs — through the same light fixture every other
     * platform test here uses. `@TestApplication` beside those tests reports their shared light
     * project as leaked when it shuts down — measured, running this beside BasedPythonLogTest.
     */
    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @BeforeEach
    fun posix() {
        assumeFalse(SystemInfo.isWindows, "drives `sh`")
    }

    private fun sh(script: String) = GeneralCommandLine("sh", "-c", script).withCharset(Charsets.UTF_8)

    @Test
    fun `output and exit code come back separately`() {
        val result = EnvRunner.execute(sh("echo out; echo err >&2; exit 3"), "sh", 10_000) { _, _ -> }

        assertEquals(3, result.exitCode)
        assertEquals("out\n", result.stdout)
        assertEquals("err\n", result.stderr)
    }

    /** Cancelling the task destroys the process and ends the wait with the cancellation itself. */
    @Test
    fun `cancelling the calling task destroys the process and rethrows`() {
        val indicator = EmptyProgressIndicator()
        val pid = CompletableFuture<Long>()

        val started = System.nanoTime()
        assertThrows(ProcessCanceledException::class.java) {
            ProgressManager.getInstance().runProcess(
                {
                    EnvRunner.execute(sh("echo $$; exec sleep 60"), "sleep", 600_000) { text, _ ->
                        text.trim().toLongOrNull()?.let {
                            pid.complete(it)
                            indicator.cancel()
                        }
                    }
                },
                indicator,
            )
        }

        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10), "the wait ended promptly")
        val process = ProcessHandle.of(pid.get(1, TimeUnit.SECONDS))
        assertFalse(process.map { it.isAlive }.orElse(false), "and the process went with it")
    }

    /** The hang guard ends the process too, and reads as "did not run" rather than as an exit code. */
    @Test
    fun `a command that outlives its timeout is destroyed`() {
        val pid = CompletableFuture<Long>()

        val result = EnvRunner.execute(sh("echo $$; exec sleep 60"), "sleep", 500) { text, _ ->
            text.trim().toLongOrNull()?.let { pid.complete(it) }
        }

        assertEquals(EnvResult.NOT_STARTED, result.exitCode)
        assertTrue(result.stderr.contains("timed out"), result.stderr)
        assertFalse(ProcessHandle.of(pid.get(1, TimeUnit.SECONDS)).map { it.isAlive }.orElse(false))
    }
}

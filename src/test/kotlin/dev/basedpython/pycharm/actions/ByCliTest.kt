package dev.basedpython.pycharm.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Every action that shells out runs under a cancellable progress, so Cancel has to reach the
 * process: `ExecUtil.execAndGetOutput`, which this used, waited on it regardless.
 */
@TestFixtures
class ByCliTest {

  @Suppress("unused")
  private val fixture by codeInsightFixture()

  @Test
  fun `cancelling the progress a command runs under stops it`() {
    val indicator = ProgressIndicatorBase()
    val started = System.nanoTime()
    val run = CompletableFuture.supplyAsync {
      ProgressManager.getInstance().runProcess<Any>(
        { ByCli.execute(GeneralCommandLine("sleep", "600"), timeoutMs = null) },
        indicator,
      )
    }

    Thread.sleep(500)
    indicator.cancel()

    val failure = runCatching { run.get(30, TimeUnit.SECONDS) }.exceptionOrNull()
    assertTrue(
      (failure as? ExecutionException)?.cause is ProcessCanceledException,
      "expected the run to end cancelled, got $failure",
    )
    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(30))
  }
}

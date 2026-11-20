package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What `ByInjections.askInBackground` rethrows its cancellation on: that the read action ends a
 * cancelled scope's coroutine with a [CancellationException], and that a coroutine ending in a
 * [ProcessCanceledException] ends quietly, as cancelled, rather than as a failure the scope's
 * exception handler is given and the platform logs as an error.
 */
@TestApplication
class ByInjectionsCancellationTest {

    private class Scope {
        val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> failures += e })

        fun join() = runBlocking { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
    }

    @Test
    fun `a read action in a scope that is cancelled ends with a cancellation`() {
        val it = Scope()
        val reading = CountDownLatch(1)
        var caught: Throwable? = null
        it.scope.launch(Dispatchers.IO) {
            try {
                readAction {
                    reading.countDown()
                    Thread.sleep(200)
                }
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }
        assertTrue(reading.await(10, TimeUnit.SECONDS), "the read action did not start")
        it.scope.cancel()
        it.join()

        assertTrue(caught is CancellationException, "the read action ended with $caught")
        assertEquals(emptyList<Throwable>(), it.failures)
    }

    @Test
    fun `a coroutine that ends in a ProcessCanceledException is cancelled, not failed`() {
        val it = Scope()
        val job = it.scope.launch(Dispatchers.IO) { throw ProcessCanceledException() }
        it.join()

        assertTrue(job.isCancelled, "the coroutine was not cancelled: $job")
        assertEquals(emptyList<Throwable>(), it.failures)
    }
}

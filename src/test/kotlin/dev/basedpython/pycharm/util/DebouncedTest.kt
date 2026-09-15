package dev.basedpython.pycharm.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DebouncedTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `a burst of requests from many threads is one run`() = runBlocking {
        val runs = AtomicInteger()
        val debounced = Debounced(scope, 100.milliseconds) { runs.incrementAndGet() }

        (1..8).map { Thread { repeat(50) { debounced.request() } }.apply { start() } }.forEach { it.join() }
        delay(600)

        assertEquals(1, runs.get())
    }

    @Test
    fun `requests during a run are one more run after it, never a second one alongside it`() = runBlocking {
        val runs = AtomicInteger()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val debounced = Debounced(scope, 20.milliseconds) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            if (runs.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                release.await()
            }
            inFlight.decrementAndGet()
        }

        debounced.request()
        withTimeout(5.seconds) { firstStarted.await() }
        repeat(20) { debounced.request() }
        delay(200)
        assertEquals(1, runs.get(), "a request must not start a run while one is in progress")

        release.complete(Unit)
        delay(500)

        assertEquals(2, runs.get())
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `a failing run does not stop later requests from being served`() = runBlocking {
        val runs = AtomicInteger()
        val debounced = Debounced(scope, 10.milliseconds) {
            if (runs.incrementAndGet() == 1) error("boom")
        }

        debounced.request()
        delay(300)
        debounced.request()
        delay(300)

        assertEquals(2, runs.get())
    }
}

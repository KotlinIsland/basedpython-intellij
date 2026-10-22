package dev.basedpython.pycharm.lsp.vision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The usage counts are a search of the whole workspace, which the two providers would each send on
 * every pass, and every pass the platform cancelled left running. What decides how many go out is
 * [SharedAsks], and it needs no IDE to show.
 */
class SharedAsksTest {

    private val file = Any()

    /** A request that counts how often it was sent and answers when [answer] is completed. */
    private class Request(val answer: CompletableDeferred<String> = CompletableDeferred()) {
        val sent = AtomicInteger()
        var cancelled = false

        suspend fun send(): String {
            sent.incrementAndGet()
            try {
                return answer.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    @Test
    fun `two askers about the same wait on one request`() = runBlocking {
        val asks = SharedAsks<Any, String>(this) { true }
        val request = Request()
        val first = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
        yield()
        request.answer.complete("3 usages")
        assertEquals("3 usages", first.await())
        assertEquals("3 usages", second.await())
        assertEquals(1, request.sent.get())

        // and a later ask about the same is answered without asking
        assertEquals("3 usages", asks.answer(file, "text 1") { request.send() })
        assertEquals(1, request.sent.get())
    }

    @Test
    fun `an ask about anything else is a new request`() = runBlocking {
        val asks = SharedAsks<Any, String>(this) { true }
        val request = Request(CompletableDeferred("answer"))
        asks.answer(file, "text 1") { request.send() }
        asks.answer(file, "text 2") { request.send() }
        assertEquals(2, request.sent.get())
    }

    @Test
    fun `an answer that may not be kept is asked again`() = runBlocking {
        val asks = SharedAsks<Any, String>(this) { it != "failed" }
        val request = Request(CompletableDeferred("failed"))
        asks.answer(file, "text 1") { request.send() }
        asks.answer(file, "text 1") { request.send() }
        assertEquals(2, request.sent.get())
    }

    /** One provider's pass stopping waiting does not take the answer from the other. */
    @Test
    fun `an asker that stops waiting leaves the request to the others`() = runBlocking {
        coroutineScope {
            val asks = SharedAsks<Any, String>(this) { true }
            val request = Request()
            val leaving = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
            val staying = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
            leaving.cancelAndJoin()
            assertFalse(request.cancelled)
            request.answer.complete("1 usage")
            assertEquals("1 usage", staying.await())
        }
    }

    /** A pass the platform cancelled cancels its request, rather than leaving the search running. */
    @Test
    fun `the request is cancelled when every asker has stopped waiting`() = runBlocking {
        coroutineScope {
            val asks = SharedAsks<Any, String>(this) { true }
            val request = Request()
            val first = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
            val second = async(start = CoroutineStart.UNDISPATCHED) { asks.answer(file, "text 1") { request.send() } }
            yield()
            first.cancelAndJoin()
            second.cancelAndJoin()
            yield()
            assertTrue(request.cancelled)

            // and the next ask about the same sends it again
            val again = Request(CompletableDeferred("2 usages"))
            assertEquals("2 usages", asks.answer(file, "text 1") { again.send() })
        }
    }
}

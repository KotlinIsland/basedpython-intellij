package dev.basedpython.pycharm.lsp

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * A run icon was missing for good when `by` answered too late for the line it is on, and the pass
 * waited two seconds over every line until it did. Both are [ByLateAnswers]'s to decide, and neither
 * needs a server to show.
 */
@TestApplication
class ByLateAnswersTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val late = java.util.Collections.synchronizedList(ArrayList<String>())
    private val lateOne = CountDownLatch(1)

    private val sent = AtomicInteger()
    private val answer = CompletableDeferred<String?>()

    private fun answers(patience: Duration) = ByLateAnswers<String>(scope, patience) {
        late.add(it)
        lateOne.countDown()
    }

    private suspend fun request(): String? {
        sent.incrementAndGet()
        return answer.await()
    }

    @AfterEach
    fun stop() = scope.cancel()

    @Test
    fun `an answer that comes in time is the answer, and nobody is told it was late`() {
        val answers = answers(10.seconds)
        answer.complete("tests")
        assertEquals("tests", answers.answer("file", 1, wait = true, ::request))
        assertEquals(emptyList<String>(), late)
    }

    @Test
    fun `a pass waits once, however many lines ask`() {
        val answers = answers(300.milliseconds)
        val (results, took) = measureTimedValue { List(20) { answers.answer("file", 1, wait = true, ::request) } }
        assertEquals(List(20) { null }, results)
        assertEquals(1, sent.get())
        assertTrue(took < 2.seconds, "twenty lines waited $took between them")
    }

    @Test
    fun `an answer that comes too late is announced, once`() {
        val answers = answers(50.milliseconds)
        repeat(3) { assertNull(answers.answer("file", 1, wait = true, ::request)) }
        assertEquals(emptyList<String>(), late)
        answer.complete("tests")
        assertTrue(lateOne.await(10, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(listOf("file"), late)
    }

    @Test
    fun `a request that fails late is not announced, and the next ask is a new one`() {
        val answers = answers(50.milliseconds)
        assertNull(answers.answer("file", 1, wait = true, ::request))
        answer.complete(null)
        Thread.sleep(200)
        assertEquals(emptyList<String>(), late)
        answers.answer("file", 1, wait = true, ::request)
        assertEquals(2, sent.get())
    }

    @Test
    fun `a caller that does not wait starts the request and misses nothing`() {
        val answers = answers(10.seconds)
        val (result, took) = measureTimedValue { answers.answer("file", 1, wait = false, ::request) }
        assertNull(result)
        assertTrue(took < 5.seconds, "it waited $took")
        answer.complete("tests")
        Thread.sleep(200)
        assertEquals(emptyList<String>(), late)
    }

    @Test
    fun `an ask about a newer revision replaces the request`() {
        val answers = answers(50.milliseconds)
        val old = CompletableDeferred<String?>()
        assertNull(answers.answer("file", 1, wait = true) { old.await() })
        assertNull(answers.answer("file", 2, wait = true, ::request))
        old.complete("the old tests")
        Thread.sleep(100)
        assertEquals(emptyList<String>(), late, "the old text's answer draws nothing")
        answer.complete("tests")
        assertTrue(lateOne.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("file"), late)
    }

    /** A pass an edit elsewhere cancelled, and the one that follows it. */
    @Test
    fun `a cancelled caller stops waiting and leaves the request to the next`() {
        val answers = answers(60.seconds)
        val stopped = AtomicBoolean()
        val took = measureTime {
            runBlocking {
                val pass = launch(Dispatchers.IO) {
                    coroutineToIndicator {
                        assertThrows(ProcessCanceledException::class.java) {
                            answers.answer("file", 1, wait = true, ::request)
                        }
                        stopped.set(true)
                    }
                }
                while (sent.get() == 0) delay(10.milliseconds)
                delay(100.milliseconds)
                pass.cancelAndJoin()
            }
        }
        assertTrue(stopped.get(), "the caller was not waiting when it was cancelled")
        assertTrue(took < 10.seconds, "cancelled after $took")
        answer.complete("tests")
        assertEquals("tests", answers.answer("file", 1, wait = true, ::request) ?: answers.answer("file", 1, wait = true, ::request))
        assertFalse(late.isNotEmpty())
    }
}

package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapMessageDirection
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import dev.basedpython.pycharm.debug.recompose.ByWatchRecompositionsArguments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * What [BySourceMapPublisher] sends when the adapter reports itself initialized, and that the
 * platform's breakpoints and `configurationDone` cannot be written ahead of it.
 *
 * The session here is a stand-in with the platform's own shape of executor: every command launched on
 * one single-threaded dispatcher, in the order posted, running exclusively only up to its first
 * suspension. That shape is where the publisher's guarantee comes from, so it is what these tests
 * reproduce.
 */
class BySourceMapPublisherTest {

    private val scope = CoroutineScope(SupervisorJob())

    /** The platform's command dispatcher: one thread's worth of `Dispatchers.IO`. */
    private val commands = Dispatchers.IO.limitedParallelism(1)

    /** Every request written to the adapter, in the order it was written. */
    private val written = CopyOnWriteArrayList<String>()

    /** Every request the adapter answered, in the order it answered them. */
    private val answered = CopyOnWriteArrayList<String>()

    /** How long the adapter takes over each request; long enough that a turn not held is lost. */
    private var latency = 0L

    private var refuse: (String, Any?) -> Boolean = { _, _ -> false }

    private val executor = object : DapSessionExecutor {
        override fun post(block: suspend DapSessionContext.() -> Unit) {
            scope.launch(commands) { context(this).block() }
        }

        override fun <T> postAsync(block: suspend DapSessionContext.() -> T): Deferred<T> =
            scope.async(commands) { context(this).block() }
    }

    private fun context(scope: CoroutineScope) = answeringContext(scope) { command, arguments ->
        written += command
        delay(latency)
        answered += command
        if (refuse(command, arguments)) refusal("no") else Result.success(null)
    }

    private val mappings = listOf(
        ByFileMapping("/abs/a.by", "/tmp/x/a.py", listOf(ByLineRun(1, 4, 4))),
        ByFileMapping("/abs/b.by", "/tmp/x/b.py", listOf(ByLineRun(1, 2, 7))),
    )

    private val readied = CopyOnWriteArrayList<String>()

    private val publisher = BySourceMapPublisher(
        executor = { executor },
        mappings = { mappings },
        onReady = {
            readied += "ready"
            send(ByDapRequests.watchRecompositions, ByWatchRecompositionsArguments(on = true).toJson())
        },
    )

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun initialized() = Json.parseToJsonElement("""{"seq":3,"type":"event","event":"initialized"}""")

    @Test
    fun `only the adapter's initialized event is recognised`() {
        assertTrue(BySourceMapPublisher.isInitializedEvent(initialized()))
        assertFalse(
            BySourceMapPublisher.isInitializedEvent(
                Json.parseToJsonElement("""{"seq":3,"type":"response","command":"initialized","success":true}"""),
            ),
        )
        assertFalse(
            BySourceMapPublisher.isInitializedEvent(
                Json.parseToJsonElement("""{"seq":3,"type":"event","event":"stopped","body":{}}"""),
            ),
        )
        assertFalse(BySourceMapPublisher.isInitializedEvent(Json.parseToJsonElement("[]")))
    }

    @Test
    fun `understands, the ready hook and every map go out, in that order`() = runBlocking {
        publisher.observe(DapMessageDirection.Incoming, initialized())
        awaitAnswers(4)
        assertEquals(
            listOf("bpd/understands", "bpd/watchRecompositions", "setPydevdSourceMap", "setPydevdSourceMap"),
            written,
        )
        assertEquals(listOf("ready"), readied)
    }

    @Test
    fun `nothing is sent for an outgoing message or another event`() = runBlocking {
        publisher.observe(DapMessageDirection.Outgoing, initialized())
        publisher.observe(
            DapMessageDirection.Incoming,
            Json.parseToJsonElement("""{"seq":4,"type":"event","event":"output","body":{"output":"x"}}"""),
        )
        delay(200)
        assertEquals(emptyList<String>(), answered)
    }

    @Test
    fun `a refused map costs that map and not the ones after it`() = runBlocking {
        refuse = { command, arguments ->
            command == "setPydevdSourceMap" &&
                (arguments as JsonObject)["source"].toString().contains("a.by")
        }
        publisher.observe(DapMessageDirection.Incoming, initialized())
        awaitAnswers(4)
        assertEquals(4, answered.size, "every request was answered: $answered")
        assertEquals(2, written.count { it == "setPydevdSourceMap" }, "the map after the refused one was still sent")
    }

    /**
     * The guarantee, in the platform's own sequence: its handler for `initialized` posts a command
     * after the publisher's, and that command wakes the sender that posts the breakpoints and
     * `configurationDone`. With every answer slow in coming, what that sender writes must still come
     * after every request here — sent one after another, the second would wait on the first's answer
     * and the breakpoints would go out in between.
     */
    @Test
    fun `every request is written before the configuration the platform releases`() = runBlocking {
        latency = 200
        publisher.observe(DapMessageDirection.Incoming, initialized())
        val configuration = CompletableDeferred<List<String>>()
        executor.post {
            // the release: wakes the configuration sender, which posts what it sends
            executor.post { configuration.complete(written.toList()) }
        }
        val seen = withTimeout(10.seconds) { configuration.await() }
        assertEquals(
            listOf("bpd/understands", "bpd/watchRecompositions", "setPydevdSourceMap", "setPydevdSourceMap"),
            seen,
        )
        assertTrue(answered.size < seen.size, "the requests were written without waiting on their answers: $answered")
    }

    @Test
    fun `the request bodies are the wire format pydevd and bpd read`() = runBlocking {
        val bodies = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val recording = object : DapSessionExecutor by executor {
            override fun post(block: suspend DapSessionContext.() -> Unit) {
                scope.launch(commands) {
                    answeringContext(this) { command, arguments ->
                        bodies += command to arguments as JsonObject
                        Result.success(null)
                    }.block()
                }
            }
        }
        BySourceMapPublisher({ recording }, { mappings.take(1) }).observe(DapMessageDirection.Incoming, initialized())
        withTimeout(10.seconds) { while (bodies.size < 2) delay(10) }

        val (understands, map) = bodies
        assertEquals("bpd/understands", understands.first)
        assertEquals(
            BySourceMapPublisher.UNDERSTOOD_EVENTS,
            understands.second.getValue("events").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("setPydevdSourceMap", map.first)
        assertEquals(
            Json.parseToJsonElement(
                """{"source":{"path":"/abs/a.by"},"pydevdSourceMaps":[""" +
                    """{"line":1,"endLine":4,"runtimeSource":{"path":"/tmp/x/a.py"},"runtimeLine":4}]}""",
            ),
            map.second,
        )
    }

    private suspend fun awaitAnswers(count: Int) = withTimeout(10.seconds) {
        while (answered.size < count) delay(10)
        // and nothing more arrives after the last one expected
        delay(100)
    }
}

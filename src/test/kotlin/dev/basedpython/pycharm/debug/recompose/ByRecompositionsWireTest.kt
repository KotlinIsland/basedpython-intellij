package dev.basedpython.pycharm.debug.recompose

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.basedpython.pycharm.debug.ByDapClient
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import dev.basedpython.pycharm.debug.BySourceMapPublisher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.debug.DebugLauncher
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** The `ui_tracing_off` refusal's sentence, from the wire document's refusal table. */
private const val TRACING_OFF = "tracing is off in the program's runtime; start it with trace=True"

/**
 * `bpd/recompositions` and `bpd/watchRecompositions` across a real lsp4j pair, because the declared
 * type is what decides whether the answer survives the trip.
 *
 * The same test `bpd/facts` has, for the same reason: a `CompletableFuture<Any?>` makes Gson build
 * a `LinkedTreeMap`, and `as? JsonObject` on that is null on every stop with nothing logged — a
 * debugger with no trace being the ordinary case this feature shrugs at. A test that called the
 * methods on a mock would agree with itself.
 *
 * The payload is the wire document's answer, not a capture: bpd's side is being built from the
 * same document, so the document is the contract.
 */
class ByRecompositionsWireTest {

    private val ANSWER = """
        { "format": 1, "runtimes": 1, "tracing": true,
          "records": { "kept": [
            { "record": "run", "runtime": 0, "frame": 3, "scope": 5, "parent": 0, "name": "Counter",
              "defined": { "file": "/app/examples/counter.by", "line": 9,
                           "generated": { "file": "/tmp/build/examples/counter.py", "line": 41 } },
              "called": { "file": "/app/examples/counter.by", "line": 24,
                          "generated": { "file": "/tmp/build/examples/counter.py", "line": 90 } },
              "key": null, "origin": "self",
              "causes": [ { "cause": "created" } ], "skipped": [], "disposed": [], "elapsed_ns": 12345 }
          ], "dropped": 0 },
          "mode": { "mode": "non_stop" } }
    """.trimIndent()

    /** The client end of a DAP pair: lsp4j needs an interface to proxy, and nothing calls back. */
    private interface NoClient

    /** Stands in for bpd, and records what it was asked so the outgoing half is pinned too. */
    private class FakeBpd(private val body: JsonObject, private val refuse: Boolean) {
        var pulled: JsonObject? = null
        var watched: JsonObject? = null

        @JsonRequest("bpd/recompositions")
        fun recompositions(args: JsonObject): CompletableFuture<JsonObject> {
            pulled = args
            if (refuse) {
                return CompletableFuture.failedFuture(
                    ResponseErrorException(ResponseError(ResponseErrorCode.InvalidRequest, TRACING_OFF, null)),
                )
            }
            return CompletableFuture.completedFuture(body)
        }

        @JsonRequest("bpd/watchRecompositions")
        fun watchRecompositions(args: JsonObject): CompletableFuture<JsonObject> {
            watched = args
            return CompletableFuture.completedFuture(JsonParser.parseString("""{"watching": true}""").asJsonObject)
        }
    }

    private fun <T> withPair(refuse: Boolean = false, block: (ByDebugProtocolServer, FakeBpd) -> T): T {
        val toClient = PipedInputStream()
        val fromAdapter = PipedOutputStream(toClient)
        val toAdapter = PipedInputStream()
        val fromClient = PipedOutputStream(toAdapter)

        val adapter = FakeBpd(JsonParser.parseString(ANSWER).asJsonObject, refuse)
        val client = DebugLauncher.createLauncher(Any(), ByDebugProtocolServer::class.java, toClient, fromClient)
        val server = DebugLauncher.createLauncher(adapter, NoClient::class.java, toAdapter, fromAdapter)
        val listeners = listOf(client.startListening(), server.startListening())
        try {
            return block(client.remoteProxy, adapter)
        } finally {
            listeners.forEach { it.cancel(true) }
        }
    }

    @Test
    fun `the answer arrives as something the record reader can read`() {
        // deliberately `Any?` and not the declared type: the subject is what the declaration makes
        // Gson build, and widening it here is what lets the assertion say so
        val reply: Any? = withPair { server, _ ->
            server.recompositions(ByRecompositionsArguments()).get(10, TimeUnit.SECONDS)
        }
        assertInstanceOf(
            JsonObject::class.java, reply,
            "the reply crossed the wire and arrived as a ${reply?.javaClass?.name}, which nothing " +
                "downstream reads — Gson builds what the declared return type asks for",
        )
        val read = ByRecompositions.parseAnswer(reply as JsonObject)
        assertInstanceOf(ByRecompositions.Reply.Read::class.java, read)
        val run = (read as ByRecompositions.Reply.Read).answer.kept.single() as ByRecord.Run
        assertEquals("Counter", run.name)
        assertEquals(ByCause.Created, run.causes.single())
        assertEquals(24, run.called?.line, "a run below the root has a call site")
    }

    @Test
    fun `the watch request carries the field bpd reads it by, and its answer is readable`() {
        // bpd looks the flag up by name (`arguments["on"]`), so a rename on this side is a refused
        // request rather than a field quietly ignored
        val (reply, watched) = withPair { server, adapter ->
            val reply: Any? = server.watchRecompositions(ByWatchRecompositionsArguments(on = true)).get(10, TimeUnit.SECONDS)
            reply to adapter.watched
        }
        assertNotNull(watched)
        assertEquals(true, watched!!.get("on").asBoolean)
        assertInstanceOf(JsonObject::class.java, reply)
        assertEquals(true, (reply as JsonObject).get("watching").asBoolean)
    }

    @Test
    fun `an empty object goes out as the pull's body`() {
        // `{}` and not `null`: bpd reads `arguments` as an object, and the class with no fields is
        // what makes lsp4j write one
        val pulled = withPair { server, adapter ->
            server.recompositions(ByRecompositionsArguments()).get(10, TimeUnit.SECONDS)
            adapter.pulled
        }
        assertNotNull(pulled)
        assertTrue(pulled!!.entrySet().isEmpty(), "expected {}, got $pulled")
    }

    @Test
    fun `a refusal is an error response, and its sentence is recovered from it`() {
        val thrown = runCatching {
            withPair(refuse = true) { server, _ -> server.recompositions(ByRecompositionsArguments()).get(10, TimeUnit.SECONDS) }
        }.exceptionOrNull()
        assertInstanceOf(ExecutionException::class.java, thrown)
        assertEquals(TRACING_OFF, ByRecompositionRequests.refusalOf(thrown!!))
    }

    /**
     * A refusal is an error response, and what the plugin's own `await()` sees of it is what decides
     * whether the sentence reaches the window or the log. Pinned here rather than assumed: the
     * data-flow sender catches `CompletionException`, and this checks that whichever wrapper
     * arrives, the sentence is recovered from it.
     */
    @Test
    fun `a refusal's sentence survives the trip whichever way it is awaited`() {
        val awaited = runCatching {
            withPair(refuse = true) { server, _ ->
                runBlocking { server.recompositions(ByRecompositionsArguments()).await() }
            }
        }.exceptionOrNull()
        assertNotNull(awaited, "a refused request must fail, not answer null")
        assertEquals(TRACING_OFF, ByRecompositionRequests.refusalOf(awaited!!), "seen through await(): ${awaited.javaClass.name}")

        assertNull(ByRecompositionRequests.refusalOf(IllegalStateException("not the adapter's doing")))
    }

    /**
     * The event this plugin says it understands has a handler, and the handler is bound to the same
     * name. Naming an event with no handler asks bpd for silence about something nobody hears.
     */
    @Test
    fun `the event bpd is told this reads is the one the client is bound to`() {
        val handler = ByDapClient::class.java.getMethod("recomposed", JsonObject::class.java)
        val bound = handler.getAnnotation(JsonNotification::class.java)
        assertNotNull(bound, "the client has no @JsonNotification for the recomposition event")
        assertEquals(ByRecompositions.EVENT, bound!!.value)
        assertTrue(
            ByRecompositions.EVENT in BySourceMapPublisher.UNDERSTOOD_EVENTS,
            "bpd is not told this client reads ${ByRecompositions.EVENT}, so it narrates every run on the console too",
        )
    }
}

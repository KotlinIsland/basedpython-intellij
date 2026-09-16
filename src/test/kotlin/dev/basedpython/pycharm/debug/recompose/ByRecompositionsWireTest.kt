package dev.basedpython.pycharm.debug.recompose

import com.intellij.platform.dap.DapSessionContext
import com.jetbrains.dap.impl.DapClientSession
import dev.basedpython.pycharm.debug.Answer
import dev.basedpython.pycharm.debug.ByDapRequests
import dev.basedpython.pycharm.debug.BySourceMapPublisher
import dev.basedpython.pycharm.debug.Received
import dev.basedpython.pycharm.debug.bpdEvents
import dev.basedpython.pycharm.debug.recompose.ByRecompositionRequests.Companion.ask
import dev.basedpython.pycharm.debug.sessionContext
import dev.basedpython.pycharm.debug.withFakeAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `ui_tracing_off` refusal's sentence, from the wire document's refusal table. */
private const val TRACING_OFF = "tracing is off in the program's runtime; start it with trace=True"

/**
 * `bpd/recompositions`, `bpd/watchRecompositions` and the `bpd/recomposition` event across the
 * platform's real DAP client, because what the request and event types declare is what decides
 * whether the answer survives the trip.
 *
 * The same test `bpd/facts` has, for the same reason: every reader downstream is handed an object
 * built in-process, and a session never hands it one. A test that called the requests on a stand-in
 * endpoint would agree with itself.
 *
 * The payload is the wire document's answer, not a capture: bpd's side is being built from the
 * same document, so the document is the contract.
 */
class ByRecompositionsWireTest {

    private val RUN = """
        { "record": "run", "runtime": 0, "frame": 3, "scope": 5, "parent": 0, "name": "Counter",
          "defined": { "file": "/app/examples/counter.by", "line": 9,
                       "generated": { "file": "/tmp/build/examples/counter.py", "line": 41 } },
          "called": { "file": "/app/examples/counter.by", "line": 24,
                      "generated": { "file": "/tmp/build/examples/counter.py", "line": 90 } },
          "key": null, "origin": "self",
          "causes": [ { "cause": "created" } ], "skipped": [], "disposed": [], "elapsed_ns": 12345 }
    """.trimIndent()

    private val ANSWER = """
        { "format": 1, "runtimes": 1, "tracing": true,
          "records": { "kept": [ $RUN ], "dropped": 0 },
          "mode": { "mode": "non_stop" } }
    """.trimIndent()

    private fun bpd(refuse: Boolean = false): (Received) -> Answer = { request ->
        when {
            refuse -> Answer.Refuse(TRACING_OFF)
            request.command == "bpd/watchRecompositions" -> Answer.Body(Json.parseToJsonElement("""{"watching": true}"""))
            else -> Answer.Body(Json.parseToJsonElement(ANSWER))
        }
    }

    private suspend fun DapClientSession.context(block: suspend DapSessionContext.() -> ByRecompositionAnswer) =
        coroutineScope { sessionContext(this, server, endpoint).block() }

    @Test
    fun `the answer arrives as something the record reader can read`() {
        val answer = withFakeAdapter(bpd()) { session, _ ->
            session.context { ask(ByDapRequests.recompositions, ByRecompositionsArguments.toJson()) }
        }
        assertInstanceOf(
            ByRecompositionAnswer.Answered::class.java, answer,
            "the reply crossed the wire and arrived as $answer, which nothing downstream reads",
        )
        val read = ByRecompositions.parseAnswer((answer as ByRecompositionAnswer.Answered).body)
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
        val (answer, received) = withFakeAdapter(bpd()) { session, adapter ->
            session.context {
                ask(ByDapRequests.watchRecompositions, ByWatchRecompositionsArguments(on = true).toJson())
            } to adapter.received.single()
        }
        assertEquals("bpd/watchRecompositions", received.command)
        assertEquals(true, received.arguments!!.getValue("on").jsonPrimitive.boolean)
        assertInstanceOf(ByRecompositionAnswer.Answered::class.java, answer)
        assertEquals(true, (answer as ByRecompositionAnswer.Answered).body.getValue("watching").jsonPrimitive.boolean)
    }

    @Test
    fun `an empty object goes out as the pull's body`() {
        val received = withFakeAdapter(bpd()) { session, adapter ->
            session.context { ask(ByDapRequests.recompositions, ByRecompositionsArguments.toJson()) }
            adapter.received.single()
        }
        assertEquals(emptyMap<String, Any>(), received.arguments, "expected {}, got ${received.arguments}")
    }

    @Test
    fun `a refusal is an error response, and its sentence is what the window is given`() {
        val answer = withFakeAdapter(bpd(refuse = true)) { session, _ ->
            session.context { ask(ByDapRequests.recompositions, ByRecompositionsArguments.toJson()) }
        }
        assertEquals(ByRecompositionAnswer.Refused(TRACING_OFF), answer)
        assertNull(ByDapRequests.refusalOf(IllegalStateException("not the adapter's doing")))
    }

    /**
     * The event this plugin says it understands reaches its reader when bpd sends it, over the
     * platform's own event dispatch. Naming an event nothing reads asks bpd for silence about
     * something nobody hears.
     */
    @Test
    fun `the event bpd is told this reads is the one that reaches the reader`() {
        val arrived = CompletableDeferred<ByEvent>()
        val events = bpdEvents(onMoved = {}, onRecomposed = { arrived.complete(it) })
        val event = withFakeAdapter(
            respond = bpd(),
            handlers = { events.forEach { bpdEvent -> event(bpdEvent.type) { body -> bpdEvent.observe(body) } } },
        ) { _, adapter ->
            adapter.event(ByRecompositions.EVENT, Json.parseToJsonElement("""{"record": $RUN, "dropped_before": 2}"""))
            arrived.await()
        }
        assertEquals("Counter", (event.record as ByRecord.Run).name)
        assertEquals(2L, event.droppedBefore)
        assertTrue(
            ByRecompositions.EVENT in BySourceMapPublisher.UNDERSTOOD_EVENTS,
            "bpd is not told this client reads ${ByRecompositions.EVENT}, so it narrates every run on the console too",
        )
    }
}

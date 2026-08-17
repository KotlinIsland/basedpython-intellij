package dev.basedpython.pycharm.debug.dfa

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.debug.DebugLauncher
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `bpd/facts` across a real lsp4j pair, because the declared type is what decides whether the
 * answer survives the trip.
 *
 * This is the test that would have caught the feature drawing nothing. Every other test of the
 * data-flow chain hands [ByDataFlowFacts] a `JsonObject` it built in-process, which is exactly the
 * one thing that never happens in a session: there, the object is whatever lsp4j asked Gson to
 * build from the reply body — and Gson builds what the **declared return type** tells it to. A
 * `CompletableFuture<Any?>` declares `Object`, and Gson's `Object` is a `LinkedTreeMap`. Asking
 * that for a `JsonObject` yields null, on every stop, with nothing logged: a debugger that answers
 * no facts is the ordinary case, so the feature is built to shrug at it.
 *
 * So the round trip is the point. A test that called `facts()` on a mock would agree with itself.
 *
 * ## the payload
 *
 * Captured off the wire from a real `by run` + `bpd` session stopped on `if a == 2:` in
 *
 * ```
 * def f(a=1):
 *     a += 1
 *     if a == 2:
 *         print("hi")
 *     else:
 *         print("bye")
 * ```
 *
 * — not written to suit this parser. It carries the two things that make the case realistic: three
 * readings of one name, of which the specific one has to win, and a name (`print`) bpd could say
 * nothing about, which arrives in `silent` rather than as an absence.
 */
class ByFactsWireTest {

    /**
     * What `bpd` answered, verbatim.
     *
     * Note `mode` is an object with its own tag and `observed` is internally tagged `snake_case` —
     * bpd's serde, not a shape chosen here.
     */
    private val CAPTURED = """
        {"mode":{"mode":"non_stop"},
         "proved":[
           {"name":"a","observed":{"class":{"module":"builtins","qualname":"int"},
            "observed":"is_exactly"},"scope":"local","stability":{"stability":"permanent"}},
           {"name":"a","observed":{"observed":"is_int","text":"2"},
            "scope":"local","stability":{"stability":"permanent"}},
           {"name":"a","observed":{"observed":"is_truthy","truthy":true},
            "scope":"local","stability":{"stability":"permanent"}}
         ],
         "silent":[{"name":"print","why":{"silence":"unbound"}}]}
    """.trimIndent()

    /** The client end of a DAP pair: lsp4j needs an interface to proxy, and nothing calls back. */
    private interface NoClient

    /** Stands in for bpd, and records the request so the outgoing half is pinned too. */
    private class FakeBpd(private val body: JsonObject) {
        var received: JsonObject? = null

        @JsonRequest("bpd/facts")
        fun facts(args: JsonObject): CompletableFuture<JsonObject> {
            received = args
            return CompletableFuture.completedFuture(body)
        }
    }

    private fun <T> withPair(block: (ByDebugProtocolServer, FakeBpd) -> T): T {
        val toClient = PipedInputStream()
        val fromAdapter = PipedOutputStream(toClient)
        val toAdapter = PipedInputStream()
        val fromClient = PipedOutputStream(toAdapter)

        val adapter = FakeBpd(JsonParser.parseString(CAPTURED).asJsonObject)
        val client = DebugLauncher.createLauncher(
            Any(), ByDebugProtocolServer::class.java, toClient, fromClient,
        )
        val server = DebugLauncher.createLauncher(
            adapter, NoClient::class.java, toAdapter, fromAdapter,
        )
        val listeners = listOf(client.startListening(), server.startListening())
        try {
            return block(client.remoteProxy, adapter)
        } finally {
            listeners.forEach { it.cancel(true) }
        }
    }

    @Test
    fun `the answer arrives as something the fact reader can read`() {
        // the regression. `as? JsonObject` on a `LinkedTreeMap` is null, and null here is
        // indistinguishable from "this adapter is debugpy and has no facts" — which is why the
        // feature drew nothing rather than reporting anything
        // deliberately `Any?` and not the declared type: this test's whole subject is what the
        // declaration makes Gson build, so widening it here is what lets the assertion below say
        // so rather than the compiler saying it for a reader who then never sees the reason
        val reply: Any? = withPair { server, _ ->
            server.facts(
                ByFactsArguments(frameId = 1, names = listOf("a", "print"), limit = ByFactsLimit(depth = 3)),
            ).get(10, TimeUnit.SECONDS)
        }

        assertInstanceOf(
            JsonObject::class.java, reply,
            "the reply crossed the wire and arrived as a ${reply?.javaClass?.name}, which nothing " +
                "downstream reads — Gson builds what the declared return type asks for",
        )
        val observations = ByDataFlowFacts.observationsOf(reply as JsonObject)
        assertEquals(1, observations.size, "one name was proved, so one observation is sendable: $observations")
        assertEquals("a", observations[0].name)
        assertEquals(
            "isInt", observations[0].observed,
            "`is_int` is the specific reading and has to beat the `is_exactly int` beside it",
        )
        assertEquals("2", observations[0].text)
    }

    @Test
    fun `the request carries the field names bpd reads it by`() {
        // the other half of the contract, and it has the same failure mode: bpd looks these up by
        // name (`arguments["frameId"]`, `arguments["names"]`), so a rename on this side is a
        // refused request rather than a field quietly ignored
        val received = withPair { server, adapter ->
            server.facts(
                ByFactsArguments(frameId = 7, names = listOf("a"), limit = ByFactsLimit(depth = 3)),
            ).get(10, TimeUnit.SECONDS)
            adapter.received
        }

        assertNotNull(received)
        assertEquals(7, received!!.get("frameId").asInt)
        assertEquals("a", received.getAsJsonArray("names")[0].asString)
        assertEquals(3, received.getAsJsonObject("limit").get("depth").asInt)
    }
}

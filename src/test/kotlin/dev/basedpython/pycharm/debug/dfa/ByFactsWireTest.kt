package dev.basedpython.pycharm.debug.dfa

import dev.basedpython.pycharm.debug.Answer
import dev.basedpython.pycharm.debug.ByDapRequests
import dev.basedpython.pycharm.debug.send
import dev.basedpython.pycharm.debug.sessionContext
import dev.basedpython.pycharm.debug.withFakeAdapter
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * `bpd/facts` across the platform's real DAP client, because what the request type declares is what
 * decides whether the answer survives the trip.
 *
 * Every other test of the data-flow chain hands [ByDataFlowFacts] an object it built in-process,
 * which is exactly the one thing that never happens in a session: there, the object is whatever the
 * platform's endpoint decoded from the reply body with the serializer the request type names. This
 * is the test that catches a declaration that cannot hold what bpd sends — the failure mode that
 * once made the feature draw nothing on every stop, with nothing logged.
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

    private fun askFacts(arguments: ByFactsArguments) = withFakeAdapter(
        respond = { Answer.Body(Json.parseToJsonElement(CAPTURED)) },
    ) { session, adapter ->
        val reply = coroutineScope {
            sessionContext(this, session.server, session.endpoint)
                .send(ByDapRequests.facts, arguments.toJson())
        }
        reply to adapter.received.single()
    }

    @Test
    fun `the answer arrives as something the fact reader can read`() {
        val (reply, _) = askFacts(
            ByFactsArguments(frameId = 1, names = listOf("a", "print"), limit = ByFactsLimit(depth = 3)),
        )

        // null here is indistinguishable from "this adapter is debugpy and has no facts" — which is
        // why a declaration the body does not survive draws nothing rather than reporting anything
        assertNotNull(reply, "the reply crossed the wire and arrived as nothing a reader can use")
        val observations = ByDataFlowFacts.observationsOf(reply)
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
        val (_, received) = askFacts(
            ByFactsArguments(frameId = 7, names = listOf("a"), limit = ByFactsLimit(depth = 3)),
        )

        assertEquals("bpd/facts", received.command)
        val arguments = received.arguments!!
        assertEquals(7, arguments.getValue("frameId").jsonPrimitive.int)
        assertEquals("a", arguments.getValue("names").jsonArray[0].jsonPrimitive.content)
        assertEquals(3, arguments.getValue("limit").jsonObject.getValue("depth").jsonPrimitive.int)
    }
}

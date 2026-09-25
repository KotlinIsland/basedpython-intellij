package dev.basedpython.pycharm.debug

import com.jetbrains.dap.protocol.Source
import com.jetbrains.dap.protocol.ThreadId
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Jump To Cursor's two requests across the platform's real DAP client, against a stand-in adapter
 * that answers the way bpd does — what goes out, which target is used, and what the user is told
 * when the frame does not move.
 */
class ByJumpRequestsTest {

    private val source = Source(name = "work.by", path = "/p/work.by")

    private fun targets(vararg ids: Int) = Answer.Body(
        Json.parseToJsonElement(
            """{"targets": [${ids.joinToString(",") { """{"id": $it, "label": "line 7", "line": 7}""" }}]}""",
        ),
    )

    /** The move, run to the end, and every sentence [ByJumpRequests] gave the user on the way. */
    private fun move(
        respond: (Received) -> Answer,
        afterGoto: suspend (adapter: FakeAdapter, said: List<String>) -> Unit = { _, _ -> },
    ): Pair<List<Received>, List<String>> {
        val requests = ByJumpRequests()
        val said = java.util.Collections.synchronizedList(mutableListOf<String>())
        val received = withFakeAdapter(
            respond = respond,
            handlers = { ByBpdEvents.register(this, bpdEvents(onMoved = requests::moved, onRecomposed = {})) },
        ) { session, adapter ->
            requests.move(session.server, ThreadId(1), source, 7) { said += it }
            afterGoto(adapter, said)
            adapter.received.toList()
        }
        return received to said
    }

    @Test
    fun `the line goes out as asked, and the target bpd mints is the one moved to`() {
        val (received, said) = move({ if (it.command == "gotoTargets") targets(41) else Answer.Body(null) })

        assertEquals(listOf("gotoTargets", "goto"), received.map { it.command })
        val asked = received[0].arguments!!
        assertEquals("/p/work.by", asked.getValue("source").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(7, asked.getValue("line").jsonPrimitive.int)
        val moved = received[1].arguments!!
        assertEquals(41, moved.getValue("targetId").jsonPrimitive.int)
        assertEquals(1, moved.getValue("threadId").jsonPrimitive.int)
        assertEquals(emptyList<String>(), said, "a move that was made says nothing")
    }

    /** bpd mints a target per held thread in the file and refuses one used on another thread. */
    @Test
    fun `a target minted for another thread is passed over for the next`() {
        val (received, said) = move({
            when {
                it.command == "gotoTargets" -> targets(41, 42)
                it.arguments!!.getValue("targetId").jsonPrimitive.int == 41 ->
                    Answer.Refuse("41 was minted for stop 2 — ask `gotoTargets` again for this thread")
                else -> Answer.Body(null)
            }
        })

        assertEquals(listOf(41, 42), received.drop(1).map { it.arguments!!.getValue("targetId").jsonPrimitive.int })
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `no target is said as a line the paused thread is not running`() {
        val (received, said) = move({ Answer.Body(Json.parseToJsonElement("""{"targets": []}""")) })

        assertEquals(listOf("gotoTargets"), received.map { it.command }, "nothing to move to, so no goto")
        assertTrue(said.single().contains("line 7 of work.by"), said.single())
    }

    @Test
    fun `an adapter's refusal is said in its own words`() {
        val (_, said) = move({ Answer.Refuse("stop 3 is not held any more") })

        assertEquals(listOf("stop 3 is not held any more"), said)
    }

    /**
     * cpython's refusal is not an error response: bpd answers `success` and says it on `bpd/moved`,
     * so the only way the user hears it at the caret is through that event.
     */
    @Test
    fun `cpython's refusal arrives on bpd's event and is said with its reason`() {
        val (_, said) = move(
            respond = { if (it.command == "gotoTargets") targets(41) else Answer.Body(null) },
            afterGoto = { adapter, said ->
                adapter.event(
                    ByMoved.EVENT,
                    Json.parseToJsonElement(
                        """{"stop": 2, "threadId": 1, "jumped": {
                             "at": {"file": "/p/work.by", "function": "main", "line": 7},
                             "outcome": {"error": {"kind": "ValueError",
                                                   "message": "can't jump into the body of a for loop",
                                                   "traceback": []},
                                         "jumped": "refused", "wanted": 9}}}""",
                    ),
                )
                // the event is dispatched on the connection's reader
                withTimeoutOrNull(5_000) { while (said.isEmpty()) delay(10) }
            },
        )

        assertEquals(listOf("The frame did not move to line 9: ValueError: can't jump into the body of a for loop"), said)
    }

    @Test
    fun `a move that was made is not reported as a refusal`() {
        val (_, said) = move(
            respond = { if (it.command == "gotoTargets") targets(41) else Answer.Body(null) },
            afterGoto = { adapter, _ ->
                adapter.event(
                    ByMoved.EVENT,
                    Json.parseToJsonElement(
                        """{"stop": 2, "threadId": 1, "jumped": {
                             "at": {"file": "/p/work.by", "function": "main", "line": 6},
                             "outcome": {"jumped": "moved", "from": 7, "bound_to_none": [], "unannounced": []}}}""",
                    ),
                )
                delay(300)
            },
        )

        assertEquals(emptyList<String>(), said)
    }

    /** Measured, not assumed: see [ByDebugBackend.setsNextStatement]. */
    @Test
    fun `only bpd is offered Jump To Cursor`() {
        assertTrue(ByDebugBackend.BPD.setsNextStatement)
        assertFalse(ByDebugBackend.DEBUGPY.setsNextStatement)
    }
}

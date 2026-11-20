package dev.basedpython.pycharm.debug

import com.jetbrains.dap.protocol.FrameId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading bpd's `bpd/restarting` event, saying only what a person could not see, and sending
 * `restartFrame` so that bpd's refusal reaches the user rendered.
 *
 * Every body here was captured off the wire from bpd 0.0.1-a5 with the plugin's `understands`,
 * against small programs: a reset inside a `with`, a reset that rewinds the caller, and one that
 * forces out the frame above first. The notes bpd sends beside the fields are dropped: they are the
 * prose this replaces.
 */
class ByRestartingTest {

    private fun parse(json: String) = ByRestarting.parse(Json.parseToJsonElement(json).jsonObject)

    private val resetInsideAWith = """
        {"stop": 2, "notes": ["the frame is back at its first line and the thread is still held in it"],
         "restarting": {"emptied": ["a"], "frame": {"file": "/t/prog.py", "function": "f", "line": 2},
                        "inside_a_block": true, "kept": ["x"], "restarted": "reset"}}
    """

    private val plainReset = """
        {"stop": 2, "notes": [],
         "restarting": {"emptied": ["a"], "frame": {"file": "/t/main.py", "function": "f", "line": 1},
                        "inside_a_block": false, "kept": [], "restarted": "reset"}}
    """

    private val arranged = """
        {"stop": 2, "notes": [],
         "restarting": {"bound_to_none": [], "caller": {"file": "/t/arranged.py", "function": "main", "line": 7},
                        "disturbed": ["got"], "exit_line": 4, "finalising": [],
                        "frame": {"file": "/t/arranged.py", "function": "g", "line": 3},
                        "inside_a_block": false, "mode": {"mode": "non_stop"}, "restarted": "arranged",
                        "unannounced": []}}
    """

    private val unwinding = """
        {"stop": 2, "notes": [],
         "restarting": {"above": [{"at": {"file": "/t/unwind.py", "function": "inner", "line": 4},
                                   "inside_a_block": true}],
                        "frame": {"file": "/t/unwind.py", "function": "outer", "line": 7},
                        "restarted": "unwinding"}}
    """

    /** bpd's `Restarted::Refused`, which it answers with `success`. */
    private val refused = """
        {"stop": 2, "notes": [],
         "restarting": {"restarted": "refused", "tried": [4],
                        "error": {"kind": "ValueError", "message": "can't jump into the body of a for loop",
                                  "traceback": []}}}
    """

    /** The case the user hit: five sentences on the console for a reset that surprised nobody. */
    @Test
    fun `a plain reset says nothing`() {
        assertNull(parse(plainReset)!!.report())
    }

    @Test
    fun `a reset inside a with says the cleanup was skipped`() {
        val restarting = parse(resetInsideAWith)
        assertEquals(ByRestarting.Reset("f", insideABlock = true), restarting)
        assertEquals(
            "reset f: the with or try it was inside did not clean up and will be entered again",
            restarting!!.report(),
        )
    }

    @Test
    fun `a rewind says which of the caller's names hold a placeholder`() {
        val restarting = parse(arranged) as ByRestarting.Arranged
        assertEquals("main", restarting.caller)
        assertEquals(listOf("got"), restarting.disturbed)
        assertEquals("reset g: got in main hold a placeholder until g returns again", restarting.report())
    }

    @Test
    fun `an unwinding names the frames that left a block without cleaning up`() {
        assertEquals(
            "reset outer: inner left a with or try without cleaning up",
            parse(unwinding)!!.report(),
        )
    }

    /** Answered with `success`, so without this line the platform shows a reset that never happened as done. */
    @Test
    fun `a refusal bpd answered with success is said`() {
        assertEquals(
            "the frame was not reset: ValueError: can't jump into the body of a for loop. it is still where it was",
            parse(refused)!!.report(),
        )
    }

    @Test
    fun `nothing the console shows is in backticks`() {
        listOf(resetInsideAWith, arranged, unwinding, refused).forEach { body ->
            val said = parse(body)!!.report()!!
            assertTrue('`' !in said, said)
        }
    }

    @Test
    fun `a body that is not one is nothing rather than an exception`() {
        assertNull(parse("""{"stop": 2}"""))
        assertNull(parse("""{"stop": 2, "restarting": {"restarted": "from a newer bpd"}}"""))
        assertNull(parse("""{"stop": 2, "restarting": []}"""))
    }

    /** The event bpd is told this plugin reads is the one that reaches the reader, over the platform's dispatch. */
    @Test
    fun `bpd is told this reads the event, and the event reaches the reader`() {
        assertTrue(ByRestarting.EVENT in ByBpdEvents.UNDERSTOOD, "bpd would narrate every restart on the console")
        val arrived = CompletableDeferred<ByRestarting>()
        val event = withFakeAdapter(
            respond = { Answer.Body(null) },
            handlers = {
                ByBpdEvents.register(this, bpdEvents(onMoved = {}, onRestarting = { arrived.complete(it) }, onRecomposed = {}))
            },
        ) { _, adapter ->
            adapter.event(ByRestarting.EVENT, Json.parseToJsonElement(resetInsideAWith))
            arrived.await()
        }
        assertEquals(ByRestarting.Reset("f", insideABlock = true), event)
    }

    /**
     * bpd's refusal of `restartFrame` reaches the caller as bpd's own sentence, and goes no further:
     * left to the platform it went into a notification raw, where `<module>` was read as a tag.
     */
    @Test
    fun `a refused restartFrame hands bpd's sentence over`() {
        val sentence = "frame 1 of stop 2 runs `<module>` and cannot be run again"
        val said = mutableListOf<String>()
        val received = withFakeAdapter(respond = { Answer.Refuse(sentence) }) { session, adapter ->
            ByRestartRequests().restart(session.server, FrameId(2)) { said += it }
            adapter.received.toList()
        }
        assertEquals(listOf("restartFrame"), received.map { it.command })
        assertEquals(2, received.single().arguments!!.getValue("frameId").jsonPrimitive.int)
        assertEquals(listOf(sentence), said)
    }

    @Test
    fun `a restartFrame that worked says nothing`() {
        val said = mutableListOf<String>()
        withFakeAdapter(respond = { Answer.Body(null) }) { session, _ ->
            ByRestartRequests().restart(session.server, FrameId(1)) { said += it }
        }
        assertEquals(emptyList<String>(), said)
    }
}

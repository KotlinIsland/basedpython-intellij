package dev.basedpython.pycharm.debug

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading bpd's `bpd/moved` event.
 *
 * Every payload here was captured off the wire from a real session rather than written to match the
 * parser, which is the only way this test says anything: the shape is bpd's `Jumped` serialised
 * whole, so a parser agreeing with itself would prove nothing.
 */
class ByMovedTest {

    private fun parse(json: String) = ByMoved.parse(JsonParser.parseString(json).asJsonObject)

    /** Captured from a restart of `work(n)` where `later` was not yet bound. */
    private val moved = """
        {"stop": 2, "threadId": 1, "jumped": {
          "at": {"file": "/tmp/p/bain.by", "function": "work", "line": 1},
          "mode": {"mode": "non_stop"},
          "outcome": {"bound_to_none": ["later"], "from": 3, "jumped": "moved", "unannounced": []}}}
    """

    /** The same event when nothing was disturbed. */
    private val quiet = """
        {"stop": 2, "threadId": 1, "jumped": {
          "at": {"file": "/tmp/p/bain.by", "function": "work", "line": 1},
          "mode": {"mode": "non_stop"},
          "outcome": {"bound_to_none": [], "from": 3, "jumped": "moved", "unannounced": []}}}
    """

    @Test
    fun `a move is read whole`() {
        val m = parse(moved)
        assertNotNull(m, "a captured event should read")
        m!!
        assertEquals(2L, m.stop)
        assertEquals("/tmp/p/bain.by", m.file)
        assertEquals(1, m.line)
        assertEquals("work", m.function)
        assertEquals(3, m.from)
        assertEquals(listOf("later"), m.boundToNone)
        assertFalse(m.refused)
    }

    /**
     * The whole point of the event. cpython binds every unbound local of a frame it moves, so a
     * variable the user is about to read holds `None` because of the debugger rather than because of
     * their program — and prose is not something a client can act on.
     */
    @Test
    fun `locals cpython bound to None are reported`() {
        assertTrue(parse(moved)!!.report()!!.contains("later"), parse(moved)!!.report()!!)
    }

    /**
     * A move that went where it was asked and disturbed nothing gets no line. The caret has already
     * moved there, and narrating ordinary success is how a console stops being read.
     */
    @Test
    fun `an uneventful move says nothing`() {
        assertNull(parse(quiet)!!.report())
    }

    /**
     * Captured by stopping before a `for` and asking cpython to jump into its body.
     *
     * `error` is a **`PythonError` object**, not a string — the mistake that made this test worth
     * rewriting. Read as a string it is null, which turned a refusal into a move that disturbed
     * nothing and reported it as nothing at all; and since the plugin now tells bpd to stop
     * narrating, that was the only channel a refusal had left. Note also that the request itself
     * answers `success: true` — the refusal is cpython's, not bpd's, so no error response carries it
     * and nothing but this event says it happened.
     */
    private val refusedPayload = """
        {"stop": 2, "threadId": 1, "jumped": {
          "at": {"file": "/tmp/p/bain.by", "function": "work", "line": 2},
          "mode": {"mode": "non_stop"},
          "outcome": {"error": {"kind": "ValueError",
                                "message": "can't jump into the body of a for loop",
                                "traceback": []},
                      "jumped": "refused", "wanted": 4}}}
    """

    /** A refusal always says something: it looks exactly like a button that did nothing. */
    @Test
    fun `a refusal is reported and marked`() {
        val refused = parse(refusedPayload)!!
        assertTrue(refused.refused, "the `jumped` tag says refused")
        assertEquals(4, refused.wanted)
        val report = refused.report()
        assertNotNull(report, "a refusal always says something")
        report!!
        assertTrue(report.contains("can't jump into the body of a for loop"), report)
        assertTrue(report.contains("ValueError"), report)
        assertTrue(report.contains("bain.by:2"), report)
    }

    /**
     * The tag decides, not the presence of a field.
     *
     * A refusal whose `error` this cannot read is still a refusal, and still has to be reported —
     * silently treating it as a successful move is the failure this whole test exists over.
     */
    @Test
    fun `a refusal with an unreadable error is still a refusal`() {
        val m = parse(
            """{"stop": 2, "jumped": {"at": {"file": "/tmp/p/a.by", "line": 2},
                 "outcome": {"jumped": "refused", "wanted": 4, "error": "a shape from a newer bpd"}}}""",
        )!!
        assertTrue(m.refused)
        assertNotNull(m.report(), "a refusal with no readable reason still says the frame did not move")
    }

    /** An error carrying only a kind reads as that kind, the way bpd's own `Display` writes it. */
    @Test
    fun `an error with no message is just its kind`() {
        val m = parse(
            """{"stop": 1, "jumped": {"outcome": {"jumped": "refused",
                 "error": {"kind": "ValueError", "message": "", "traceback": []}}}}""",
        )!!
        assertTrue(m.report()!!.contains("ValueError"), m.report()!!)
    }

    /**
     * A body this cannot read costs the report, never the session — an event from a newer bpd must
     * not take a debug session down, and lsp4j would log a handler failure per move.
     */
    @Test
    fun `an unreadable body is declined rather than thrown`() {
        assertNull(ByMoved.parse(null))
        assertNull(parse("""{"threadId": 1}"""))
        assertNull(parse("""{"stop": 2}"""), "no `jumped` is nothing to report")
        // Wrong *types*, not merely missing: Gson's `asLong` on a string throws, and a session must
        // not end because a newer bpd changed a shape.
        assertNull(parse("""{"stop": "two", "jumped": {}}"""))
        assertNull(parse("""{"stop": [2], "jumped": {}}"""))
        assertNull(parse("""{"stop": 2, "jumped": 5}"""))
    }

    /** Every field of the wrong type is absent rather than fatal. */
    @Test
    fun `wrongly typed fields read as absent`() {
        val m = parse(
            """{"stop": 2, "jumped": {"at": 5,
                 "outcome": {"from": "x", "bound_to_none": 3, "unannounced": [1, "two", 3]}}}""",
        )!!
        assertNull(m.file)
        assertNull(m.from)
        assertTrue(m.boundToNone.isEmpty())
        assertEquals(listOf(1, 3), m.unannounced, "a bad element is skipped, not fatal")
    }

    /** Fields a newer bpd has not sent are absent, not fatal. */
    @Test
    fun `a move with only what it must have still reads`() {
        val m = parse("""{"stop": 7, "jumped": {"outcome": {}}}""")
        assertNotNull(m)
        m!!
        assertEquals(7L, m.stop)
        assertNull(m.file)
        assertTrue(m.boundToNone.isEmpty())
        assertNull(m.report())
    }

    /** The name is the wire, and `bpd/understands` names it back to switch the prose off. */
    @Test
    fun `the event name is the one bpd sends`() {
        assertEquals("bpd/moved", ByMoved.EVENT)
    }
}

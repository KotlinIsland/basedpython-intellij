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

    /** A refusal always says something: it looks exactly like a button that did nothing. */
    @Test
    fun `a refusal is reported and marked`() {
        val refused = parse(
            """
            {"stop": 2, "threadId": 1, "jumped": {
              "at": {"file": "/tmp/p/bain.by", "function": "work", "line": 3},
              "mode": {"mode": "non_stop"},
              "outcome": {"jumped": "refused", "wanted": 1,
                          "error": "can't jump into the body of a for loop"}}}
            """,
        )!!
        assertTrue(refused.refused)
        val report = refused.report()
        assertNotNull(report, "a refusal always says something")
        report!!
        assertTrue(report.contains("can't jump into the body of a for loop"), report)
        assertTrue(report.contains("bain.by:3"), report)
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

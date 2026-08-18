package dev.basedpython.pycharm.debug.hotswap

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading what bpd answers `bpd/replaceCode` with.
 *
 * Every body here was captured off the wire from a real `bpd dap` session — a python 3.14 program
 * stopped at a breakpoint, its source edited underneath it, the request sent — rather than written
 * to match the parser. That is the only way this test says anything: the shape is bpd's `Replaced`
 * serialised whole, so a parser agreeing with itself would prove nothing.
 */
class ByReplacedTest {

    private fun parse(json: String) = ByReplaced.parse(JsonParser.parseString(json).asJsonObject)

    /**
     * The case the whole feature is for: the edited file is an **imported module**, so its own
     * module frame has already returned and every function of it is replaceable.
     *
     * That is the shape a `by run` session always has — the interpreter's script is `_by_runner.py`
     * and everything the user wrote is imported — and it is why `factory.<locals>.inner` is in here
     * with one object: the closure the factory handed out earlier is rebound too, which is the
     * difference between walking the heap and walking a namespace.
     */
    private val applied = """
        {"file": "/tmp/p/helper.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [
            {"function": "<module>", "now_at": 1, "objects": 0, "was_at": 1},
            {"function": "factory", "now_at": 1, "objects": 1, "was_at": 1},
            {"function": "factory.<locals>.inner", "now_at": 2, "objects": 1, "was_at": 2},
            {"function": "slow", "now_at": 10, "objects": 1, "was_at": 10}],
          "rebound": [], "replaced": "applied", "still_running": [], "unchanged": []}}
    """

    /** The same request a moment before the edit: the file on disk already was what is running. */
    private val nothingToDo = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [], "rebound": [], "replaced": "applied", "still_running": [],
          "unchanged": ["<module>", "slow", "other", "main"]}}
    """

    /** A file whose functions moved down, with a breakpoint that had to bind again. */
    private val rebound = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [
            {"function": "other", "now_at": 9, "objects": 0, "was_at": 8},
            {"function": "main", "now_at": 13, "objects": 0, "was_at": 12}],
          "rebound": [{"id": 1, "binding": {"binding": "bound", "evaluation": "always", "line": 16,
            "sites": [{"first_line": 13, "offset": 34, "qualname": "main"}]}}],
          "replaced": "applied", "still_running": [], "unchanged": []}}
    """

    /** Adding a function is a change to the module body, which applying would mean re-running. */
    private val topLevelChanged = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "because": [{"unreplaceable": "top_level_changed", "file": "/tmp/p/victim.py",
            "differences": [
              {"differs": "defines", "added": ["added"], "removed": []},
              {"differs": "names", "added": ["added"], "removed": []},
              {"differs": "instructions"}]}],
          "replaced": "refused"}}
    """

    /** Two frames of the file are in flight, and both are named. */
    private val running = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "because": [
            {"unreplaceable": "running", "function": "<module>",
             "frame": {"frame": "thread", "held": 2, "line": 12, "thread": 8336743808}},
            {"unreplaceable": "running", "function": "main",
             "frame": {"frame": "thread", "held": 2, "line": 8, "thread": 8336743808}}],
          "replaced": "refused"}}
    """

    @Test
    fun `an applied replacement is read whole`() {
        val r = parse(applied)
        assertNotNull(r, "a captured answer should read")
        r!!
        assertEquals("/tmp/p/helper.py", r.file)
        assertTrue(r.applied)
        assertEquals(
            listOf("<module>", "factory", "factory.<locals>.inner", "slow"),
            r.changed.map { it.function },
        )
        assertEquals(0, r.refusals)
    }

    /**
     * The count is the point of reporting it rather than assuming one. A closure a factory handed
     * out is a second function object running the same code, and it was rebound too.
     */
    @Test
    fun `how many function objects held the code is kept`() {
        val inner = parse(applied)!!.changed.single { it.function == "factory.<locals>.inner" }
        assertEquals(1, inner.objects)
    }

    /**
     * "Nothing needed replacing" and "nothing could be replaced" are different facts and bpd
     * distinguishes them, so this must not render the first as the second.
     */
    @Test
    fun `a replacement that found nothing to do says so`() {
        val r = parse(nothingToDo)!!
        assertTrue(r.applied)
        assertTrue(r.changed.isEmpty())
        assertEquals(4, r.unchanged.size)
        assertTrue(r.report()!!.contains("nothing needed replacing"), r.report()!!)
    }

    /**
     * A breakpoint is a *line of a file*, so an edit above it means the same request now names a
     * different statement. Where it ended up is the second thing a replacement changes about the
     * process, and a user watching a line they can see is armed has to be told.
     */
    @Test
    fun `a rebound breakpoint reports the line it is on now`() {
        val r = parse(rebound)!!
        assertEquals(listOf(16), r.rebound)
        assertTrue(r.report()!!.contains("now at line 16"), r.report()!!)
    }

    /** The line a function's code begins on now, when the edit above it moved it down the file. */
    @Test
    fun `a function that moved says where from and where to`() {
        assertTrue(parse(rebound)!!.report()!!.contains("line 12 is now 13"), parse(rebound)!!.report()!!)
    }

    @Test
    fun `a refusal is read as one, and counted`() {
        assertFalse(parse(topLevelChanged)!!.applied)
        assertEquals(1, parse(topLevelChanged)!!.refusals)
        assertEquals(2, parse(running)!!.refusals)
    }

    /**
     * bpd writes every refusal to the `output` stream itself, as its own sentence, and this plugin
     * shows that stream prominently. A line from here would be the same fact twice.
     */
    @Test
    fun `nothing is printed for a refusal`() {
        assertNull(parse(topLevelChanged)!!.report())
        assertNull(parse(running)!!.report())
    }

    /**
     * An answer from a newer bpd should cost this feature and never the session, so every accessor
     * checks the kind of what it found rather than merely that something was there. Gson's `asInt`
     * on a string throws.
     */
    @Test
    fun `nothing throws on json of the wrong shape`() {
        assertNull(parse("""{}"""))
        assertNull(parse("""{"outcome": 3}"""))
        assertNull(ByReplaced.parse(null))
        val odd = parse(
            """{"file": 7, "outcome": {"replaced": "applied", "changed": "no", "rebound": {},
               "unchanged": [1, "slow"]}}""",
        )!!
        assertNull(odd.file)
        assertTrue(odd.applied)
        assertTrue(odd.changed.isEmpty())
        assertEquals(listOf("slow"), odd.unchanged)
    }

    /**
     * The tag, not the presence of some other key. Inferring the outcome from whether `changed`
     * parsed is how a shape change becomes a silent wrong answer instead of a missing one.
     */
    @Test
    fun `the outcome is read from the tag`() {
        assertFalse(parse("""{"outcome": {"changed": []}}""")!!.applied)
    }
}

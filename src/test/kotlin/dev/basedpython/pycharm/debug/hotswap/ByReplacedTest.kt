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
 * ## how much of this is captured
 *
 * The **per-file bodies** below were captured off the wire from a real `bpd dap` session — a python
 * 3.14 program stopped at a breakpoint, its source edited underneath it, the request sent — and are
 * unchanged from when they were. That is what makes this test say anything: the shape is bpd's own
 * type serialised whole, so a parser agreeing with itself would prove nothing.
 *
 * [remapped] is a whole answer captured the same way, off a real re-staged basedpython build: the
 * `.by` was named, bpd resolved it through the map, read `_by_sourcemap.py` again and translated the
 * breakpoint set through the new tables before assigning any `__code__`. It is the shape that proves
 * where `rebound` now sits — beside the files rather than inside one — which a hand-written envelope
 * could only have asserted.
 *
 * The **envelopes around the older bodies** are still written rather than captured. They are the
 * same `Replacements` shape and the parser cannot tell the difference, but only [remapped] has
 * actually come off a wire.
 */
class ByReplacedTest {

    private fun parse(json: String) = ByReplaced.parse(JsonParser.parseString(json).asJsonObject)

    /** One captured per-file body, in the envelope bpd now wraps them in. */
    private fun envelope(vararg files: String, rebound: String = "[]", remapped: String = "null") =
        """{"files": [${files.joinToString(", ")}], "rebound": $rebound, "remapped": $remapped}"""

    /**
     * The case the whole feature is for: the edited file is an **imported module**, so its own
     * module frame has already returned and every function of it is replaceable.
     *
     * That is the shape a `by run` session always has — the interpreter's script is `_by_runner.py`
     * and everything the user wrote is imported — and it is why `factory.<locals>.inner` is in here
     * with one object: the closure the factory handed out earlier is rebound too, which is the
     * difference between walking the heap and walking a namespace.
     */
    private val appliedFile = """
        {"file": "/tmp/p/helper.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [
            {"function": "<module>", "now_at": 1, "objects": 0, "was_at": 1},
            {"function": "factory", "now_at": 1, "objects": 1, "was_at": 1},
            {"function": "factory.<locals>.inner", "now_at": 2, "objects": 1, "was_at": 2},
            {"function": "slow", "now_at": 10, "objects": 1, "was_at": 10}],
          "replaced": "applied", "still_running": [], "unchanged": []}}
    """

    /** The same request a moment before the edit: the file on disk already was what is running. */
    private val nothingToDoFile = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [], "replaced": "applied", "still_running": [],
          "unchanged": ["<module>", "slow", "other", "main"]}}
    """

    /** A file whose functions moved down the file. */
    private val movedFile = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "changed": [
            {"function": "other", "now_at": 9, "objects": 0, "was_at": 8},
            {"function": "main", "now_at": 13, "objects": 0, "was_at": 12}],
          "replaced": "applied", "still_running": [], "unchanged": []}}
    """

    /** The breakpoint that had to bind again, as bpd resolves it — now beside the files, not in one. */
    private val reboundBreakpoint = """
        [{"id": 1, "binding": {"binding": "bound", "evaluation": "always", "line": 16,
          "sites": [{"first_line": 13, "offset": 34, "qualname": "main"}]}}]
    """

    /** Adding a function is a change to the module body, which applying would mean re-running. */
    private val topLevelChangedFile = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "because": [{"unreplaceable": "top_level_changed", "file": "/tmp/p/victim.py",
            "differences": [
              {"differs": "defines", "added": ["added"], "removed": []},
              {"differs": "names", "added": ["added"], "removed": []},
              {"differs": "instructions"}]}],
          "replaced": "refused"}}
    """

    /** Two frames of the file are in flight, and both are named. */
    private val runningFile = """
        {"file": "/tmp/p/victim.py", "mode": {"mode": "non_stop"}, "outcome": {
          "because": [
            {"unreplaceable": "running", "function": "<module>",
             "frame": {"frame": "thread", "held": 2, "line": 12, "thread": 8336743808}},
            {"unreplaceable": "running", "function": "main",
             "frame": {"frame": "thread", "held": 2, "line": 8, "thread": 8336743808}}],
          "replaced": "refused"}}
    """

    /**
     * A whole answer to a re-staged build, captured off a real session.
     *
     * `__annotate__` beside each function is the detail worth having a real capture for: python 3.14
     * gives every annotated function a second code object, and it is replaced alongside the function
     * it belongs to. Nothing written by hand would have thought to put it there.
     */
    private val remapped = """
        {
          "files": [
            {
              "file": "/tmp/build/demo.py",
              "outcome": {
                "replaced": "applied",
                "changed": [
                  {
                    "function": "<module>",
                    "was_at": 1,
                    "now_at": 1,
                    "objects": 0
                  },
                  {
                    "function": "__annotate__",
                    "was_at": 7,
                    "now_at": 8,
                    "objects": 1
                  },
                  {
                    "function": "add",
                    "was_at": 7,
                    "now_at": 8,
                    "objects": 1
                  },
                  {
                    "function": "__annotate__",
                    "was_at": 12,
                    "now_at": 13,
                    "objects": 1
                  },
                  {
                    "function": "main",
                    "was_at": 12,
                    "now_at": 13,
                    "objects": 1
                  }
                ],
                "unchanged": [],
                "still_running": []
              }
            }
          ],
          "rebound": [
            {
              "id": 2,
              "binding": {
                "binding": "bound_in_source",
                "line": 9,
                "generated": {
                  "file": "/tmp/build/demo.py",
                  "line": 15
                },
                "sites": [
                  {
                    "qualname": "main",
                    "first_line": 13,
                    "offset": 26
                  }
                ],
                "evaluation": "always"
              }
            }
          ],
          "remapped": {
            "directory": "/tmp/build",
            "files": 1,
            "breakpoints": 2
          },
          "mode": {
            "mode": "non_stop"
          }
        }
    """

    @Test
    fun `an applied replacement is read whole`() {
        val r = parse(envelope(appliedFile))
        assertNotNull(r, "a captured answer should read")
        r!!
        val one = r.files.single()
        assertEquals("/tmp/p/helper.py", one.file)
        assertTrue(r.applied)
        assertEquals(
            listOf("<module>", "factory", "factory.<locals>.inner", "slow"),
            one.changed.map { it.function },
        )
        assertEquals(0, one.refusals)
    }

    /**
     * The count is the point of reporting it rather than assuming one. A closure a factory handed
     * out is a second function object running the same code, and it was rebound too.
     */
    @Test
    fun `how many function objects held the code is kept`() {
        val inner = parse(envelope(appliedFile))!!.files.single()
            .changed.single { it.function == "factory.<locals>.inner" }
        assertEquals(1, inner.objects)
    }

    /**
     * "Nothing needed replacing" and "nothing could be replaced" are different facts and bpd
     * distinguishes them, so this must not render the first as the second.
     */
    @Test
    fun `a replacement that found nothing to do says so`() {
        val r = parse(envelope(nothingToDoFile))!!
        assertTrue(r.applied)
        assertTrue(r.files.single().changed.isEmpty())
        assertEquals(4, r.files.single().unchanged.size)
        assertTrue(r.report()!!.contains("nothing needed replacing"), r.report()!!)
    }

    /**
     * A breakpoint is a *line of a file*, so an edit above it means the same request now names a
     * different statement. Where it ended up is the second thing a replacement changes about the
     * process, and a user watching a line they can see is armed has to be told.
     */
    @Test
    fun `a rebound breakpoint reports the line it is on now`() {
        val r = parse(envelope(movedFile, rebound = reboundBreakpoint))!!
        assertEquals(listOf(16), r.rebound)
        assertTrue(r.report()!!.contains("now at line 16"), r.report()!!)
    }

    /** The line a function's code begins on now, when the edit above it moved it down the file. */
    @Test
    fun `a function that moved says where from and where to`() {
        val report = parse(envelope(movedFile))!!.report()!!
        assertTrue(report.contains("line 12 is now 13"), report)
    }

    /**
     * A replacement is applied across the whole set or not at all, so one file refusing means the
     * process was not moved — and reading this per file would claim a state the process was never
     * in.
     */
    @Test
    fun `one file refusing means nothing was applied`() {
        val r = parse(envelope(appliedFile, topLevelChangedFile))!!
        assertFalse(r.applied)
        assertNull(r.report())
    }

    @Test
    fun `a refusal is read as one, and counted`() {
        assertFalse(parse(envelope(topLevelChangedFile))!!.applied)
        assertEquals(1, parse(envelope(topLevelChangedFile))!!.files.single().refusals)
        assertEquals(2, parse(envelope(runningFile))!!.files.single().refusals)
    }

    /**
     * bpd writes every refusal to the `output` stream itself, as its own sentence, and this plugin
     * shows that stream prominently. A line from here would be the same fact twice.
     */
    @Test
    fun `nothing is printed for a refusal`() {
        assertNull(parse(envelope(topLevelChangedFile))!!.report())
        assertNull(parse(envelope(runningFile))!!.report())
    }

    /**
     * The map moving is the half of this a user cannot otherwise see: it happened before any
     * `__code__` was assigned, and every `.by` line reported afterwards comes out of the new table.
     */
    @Test
    fun `a remap is reported when one happened`() {
        val r = parse(
            envelope(
                appliedFile,
                remapped = """{"directory": "/tmp/build", "files": 3, "breakpoints": 2}""",
            ),
        )!!
        assertEquals(3, r.remapped?.files)
        assertEquals(2, r.remapped?.breakpoints)
        assertTrue(r.report()!!.contains("read the build's source map again"), r.report()!!)
    }

    /**
     * An answer from a newer bpd should cost this feature and never the session, so every accessor
     * checks the kind of what it found rather than merely that something was there. Gson's `asInt`
     * on a string throws.
     */
    @Test
    fun `nothing throws on json of the wrong shape`() {
        assertNull(parse("""{}"""))
        assertNull(parse("""{"files": 3}"""))
        assertNull(ByReplaced.parse(null))
        val odd = parse(
            """{"files": [{"file": 7, "outcome": {"replaced": "applied", "changed": "no",
               "unchanged": [1, "slow"]}}], "rebound": {}, "remapped": 4}""",
        )!!
        val one = odd.files.single()
        assertNull(one.file)
        assertTrue(odd.applied)
        assertTrue(one.changed.isEmpty())
        assertEquals(listOf("slow"), one.unchanged)
        assertNull(odd.remapped)
        assertTrue(odd.rebound.isEmpty())
    }

    /**
     * The tag, not the presence of some other key. Inferring the outcome from whether `changed`
     * parsed is how a shape change becomes a silent wrong answer instead of a missing one.
     */
    @Test
    fun `the outcome is read from the tag`() {
        assertFalse(parse(envelope("""{"outcome": {"changed": []}}"""))!!.applied)
    }

    /**
     * The captured re-stage, read whole: what moved, where the map went, and where the breakpoint
     * came back.
     */
    @Test
    fun `a captured re-stage reads as one applied file, a remap and a rebound breakpoint`() {
        val r = parse(remapped)
        assertNotNull(r, "a captured answer should read")
        r!!

        assertTrue(r.applied)
        val one = r.files.single()
        assertEquals("/tmp/build/demo.py", one.file)
        assertTrue(
            one.changed.any { it.function == "main" },
            "the function whose body moved is named: ${one.changed.map { it.function }}",
        )

        assertEquals(1, r.remapped?.files)
        assertEquals(2, r.remapped?.breakpoints)

        // the `.by` line, not the generated one: `binding.line` is what the user asked for and
        // `binding.generated.line` is where the interpreter really stops
        assertEquals(listOf(9), r.rebound)

        val report = r.report()!!
        assertTrue(report.contains("read the build's source map again"), report)
        assertTrue(report.contains("now at line 9"), report)
    }
}

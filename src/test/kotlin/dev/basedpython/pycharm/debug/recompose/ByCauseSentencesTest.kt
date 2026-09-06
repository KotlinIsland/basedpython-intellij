package dev.basedpython.pycharm.debug.recompose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The one vocabulary the tree rows and the margin labels share. */
class ByCauseSentencesTest {

    private val site = ByTraceLocation("/app/examples/counter.by", 14, ByGeneratedLocation("/tmp/b/counter.py", 47), null)

    private fun state(
        old: String? = "0",
        new: String? = "2",
        name: String? = "count",
        kind: String = "state",
        op: String = "set",
        at: ByTraceScalar? = null,
        posted: Boolean = false,
        thread: Long? = 8674,
        readers: Int = 1,
    ) = ByCause.State(
        cell = 4401, kind = kind, op = op, at = at, old = old, new = new,
        declared = ByTraceLocation("/app/examples/counter.by", 12, null, null), declaredName = name,
        written = site, thread = thread, posted = posted, readers = readers,
    )

    @Test
    fun `a state write is the cell, the values, the op and the site`() {
        assertEquals("count 0 → 2, set at counter.by:14", ByCauseSentences.sentence(state()))
    }

    @Test
    fun `a posted write names its thread, and a write nothing read says so`() {
        assertEquals(
            "count 0 → 2, set at counter.by:14, posted from thread 8674, nothing depends on it",
            ByCauseSentences.sentence(state(posted = true, readers = 0)),
        )
        assertEquals(
            "count 0 → 2, set at counter.by:14, posted from thread ?",
            ByCauseSentences.sentence(state(posted = true, thread = null)),
        )
    }

    @Test
    fun `an op on an index or key names the slot`() {
        assertEquals(
            "items[2] 'a' → 'b', put at counter.by:14",
            ByCauseSentences.sentence(state(name = "items", kind = "list", op = "put", at = ByTraceScalar.Number(2), old = "'a'", new = "'b'")),
        )
        assertEquals(
            "todos['k'] None → a Todo, put at counter.by:14",
            ByCauseSentences.sentence(state(name = "todos", kind = "dict", op = "put", at = ByTraceScalar.Text("k"), old = "None", new = "a Todo")),
        )
    }

    @Test
    fun `a cell created outside composition is named by its kind`() {
        assertEquals("a list cell 3 → 0, clear at counter.by:14", ByCauseSentences.sentence(state(name = null, kind = "list", op = "clear", old = "3", new = "0")))
    }

    @Test
    fun `an argument that differed, and one that was never compared`() {
        assertEquals("step 1 → 2", ByCauseSentences.sentence(ByCause.Args("step", "1", "2", compared = true)))
        assertEquals(
            "draft: a Draft is unstable, never compared",
            ByCauseSentences.sentence(ByCause.Args("draft", "a Draft", "a Draft", compared = false)),
        )
        assertEquals("step ? → 2", ByCauseSentences.sentence(ByCause.Args("step", null, "2", compared = true)))
    }

    @Test
    fun `a derived says what it became and why`() {
        val derived = ByCause.Derived(77, null, "total", "1", "2", changed = true, because = state())
        assertEquals("total 1 → 2 because count 0 → 2, set at counter.by:14", ByCauseSentences.sentence(derived))
        assertEquals(
            "a derived 2 → 2, compared equal because count 0 → 2, set at counter.by:14",
            ByCauseSentences.sentence(derived.copy(declaredName = null, old = "2", changed = false)),
        )
    }

    @Test
    fun `the structural causes are themselves`() {
        assertEquals("created", ByCauseSentences.sentence(ByCause.Created))
        assertEquals("invalidated by hand, with no cause", ByCauseSentences.sentence(ByCause.Invalidated))
        assertEquals("takes a content block, so it runs whenever its parent runs", ByCauseSentences.sentence(ByCause.Inline))
        assertEquals("not committed yet", ByCauseSentences.sentence(ByCause.Uncommitted))
        assertEquals("the previous run raised boom", ByCauseSentences.sentence(ByCause.Recovery("boom")))
    }

    @Test
    fun `a dirty scope lists what made it dirty`() {
        assertEquals(
            "already dirty: created; count 0 → 2, set at counter.by:14",
            ByCauseSentences.sentence(ByCause.Dirty(listOf(ByCause.Created, state()))),
        )
    }

    @Test
    fun `a site is the file name and the line`() {
        assertEquals("counter.by:14", ByCauseSentences.site(site))
        assertEquals("a.by:2", ByCauseSentences.site(ByTraceLocation("C:\\app\\a.by", 2, null, null)))
    }

    /**
     * A clause for a tooltip, not bpd's paragraph. Every tag has one, so a location that carries a
     * reason always says something beside itself.
     */
    @Test
    fun `every reason for an unmapped place has a clause`() {
        assertEquals(
            "the source map says nothing about this file",
            ByCauseSentences.unmapped(ByUnmapped.NotInTheMap("/a.py")),
        )
        assertEquals(
            "line 90 is past the 74 lines the map covers — the file that was generated is not the file being read",
            ByCauseSentences.unmapped(ByUnmapped.PastTheEnd("/a.py", 90, 74)),
        )
        assertEquals(
            "the transpiler emitted this line and no line of counter.by is behind it",
            ByCauseSentences.unmapped(ByUnmapped.NoSourceLine("/tmp/b/counter.py", 90, "/app/counter.by")),
        )
        assertEquals(
            "nothing was generated for line 40, or for any line after it, the last being line 31",
            ByCauseSentences.unmapped(ByUnmapped.NoGeneratedLine("/a.by", 40, 31)),
        )
        assertEquals(
            "nothing was generated for line 40, or for any line after it",
            ByCauseSentences.unmapped(ByUnmapped.NoGeneratedLine("/a.by", 40, null)),
        )
    }

    /** A tag from a newer bpd is read out as itself rather than dressed up as a reason this knows. */
    @Test
    fun `a reason this build does not know is spelled as its tag`() {
        assertEquals("a reason from 2027", ByCauseSentences.unmapped(ByUnmapped.Unknown("a_reason_from_2027")))
        assertEquals("unmapped, for a reason this build cannot read", ByCauseSentences.unmapped(ByUnmapped.Unknown("")))
    }

    @Test
    fun `durations read in the unit that fits`() {
        assertEquals("300 ns", ByDurations.render(300))
        assertEquals("12 µs", ByDurations.render(12_345))
        assertEquals("1.2 ms", ByDurations.render(1_234_567))
        assertEquals("2.5 s", ByDurations.render(2_500_000_000))
    }
}

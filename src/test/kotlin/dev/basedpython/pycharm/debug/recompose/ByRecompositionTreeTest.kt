package dev.basedpython.pycharm.debug.recompose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Records in, rows out — the shape of the tree, with no tree anywhere near it. */
class ByRecompositionTreeTest {

    private val counterDef = ByTraceLocation("/app/counter.by", 9, ByGeneratedLocation("/tmp/b/counter.py", 41), null)
    private val counterCall = ByTraceLocation("/app/app.by", 24, null, null)
    private val site = ByTraceLocation("/app/counter.by", 14, null, null)

    private val countWrite = ByCause.State(
        cell = 1, kind = "state", op = "set", at = null, old = "0", new = "2",
        declared = ByTraceLocation("/app/counter.by", 12, null, null), declaredName = "count",
        written = site, thread = 1, posted = false, readers = 1,
    )
    private val unreadWrite = countWrite.copy(cell = 2, declaredName = "unused", new = "5", readers = 0)

    private fun run(
        frame: Long,
        scope: Long,
        name: String = "Counter",
        causes: List<ByCause> = listOf(countWrite),
        runtime: Int = 0,
        defined: ByTraceLocation? = counterDef,
        origin: String = "self",
        skipped: List<Long> = emptyList(),
        disposed: List<ByDisposed> = emptyList(),
    ) = ByRecord.Run(
        runtime = runtime, frame = frame, scope = scope, parent = 0, name = name, defined = defined,
        called = counterCall, key = null, origin = origin, causes = causes, skipped = skipped,
        disposed = disposed, elapsedNs = 12_345,
    )

    @Test
    fun `frames come newest first, runs under them in the order they ran`() {
        val rows = ByRecompositionTree.rows(
            listOf(
                run(frame = 2, scope = 5),
                ByRecord.Frame(0, 2, runs = 1, skips = 0, composeNs = 15_000, commitNs = 300_000),
                run(frame = 3, scope = 5, causes = listOf(ByCause.Created)),
                run(frame = 3, scope = 6, name = "Total", causes = listOf(ByCause.Inline)),
            ),
        )
        assertEquals(listOf("frame 3", "frame 2"), rows.map { it.text })
        assertEquals(listOf("Counter", "Total"), rows[0].children.map { it.text })
        assertEquals("2 runs", rows[0].detail, "no frame record: the runs are counted")
        assertEquals("1 run · 0 skips · compose 15 µs · commit 300 µs", rows[1].detail)
        assertEquals(ByRowKind.FRAME, rows[0].kind)
    }

    @Test
    fun `a run carries its origin, cost and key, and its causes as sentences`() {
        val rows = ByRecompositionTree.rows(listOf(run(frame = 1, scope = 5).copy(key = ByTraceScalar.Number(2))))
        val counter = rows.single().children.single()
        assertEquals(ByRowKind.RUN, counter.kind)
        assertEquals("self · 12 µs · key 2", counter.detail)
        assertEquals(counterDef, counter.target, "Jump to Source opens the definition")
        assertEquals(counterCall, counter.callSite)
        val cause = counter.children.single()
        assertEquals(ByRowKind.CAUSE, cause.kind)
        assertEquals("count 0 → 2, set at counter.by:14", cause.text)
        assertEquals(site, cause.target, "Jump to Source opens the write site")
        assertEquals(countWrite, cause.cause)
    }

    @Test
    fun `skipped and disposed children follow the causes`() {
        val rows = ByRecompositionTree.rows(
            listOf(run(frame = 1, scope = 5, skipped = listOf(7, 8), disposed = listOf(ByDisposed(9, "Row", ByTraceScalar.Number(2))))),
        )
        val children = rows.single().children.single().children
        assertEquals(listOf(ByRowKind.CAUSE, ByRowKind.SKIPPED, ByRowKind.DISPOSED), children.map { it.kind })
        assertEquals("skipped 7, 8", children[1].text)
        assertEquals("disposed Row key 2", children[2].text)
        assertEquals("scope 9", children[2].detail)
    }

    /**
     * A write that made something run is that run's cause and is not listed again; the write that
     * made nothing run — `readers == 0` — has nowhere else to be seen.
     */
    @Test
    fun `a write no run explains is shown under its frame`() {
        val rows = ByRecompositionTree.rows(
            listOf(
                ByRecord.Write(0, 3, countWrite),
                ByRecord.Write(0, 3, unreadWrite),
                run(frame = 3, scope = 5, causes = listOf(countWrite)),
            ),
        )
        val children = rows.single().children
        assertEquals(listOf(ByRowKind.WRITE, ByRowKind.RUN), children.map { it.kind })
        assertEquals("unused 0 → 5, set at counter.by:14, nothing depends on it", children[0].text)
        assertEquals(site, children[0].target)
    }

    @Test
    fun `a write explained through a dirty or a derived cause is not listed twice`() {
        val derived = ByCause.Derived(7, null, "total", "1", "2", changed = true, because = countWrite)
        val rows = ByRecompositionTree.rows(
            listOf(
                ByRecord.Write(0, 3, countWrite),
                ByRecord.Write(0, 3, derived),
                run(frame = 3, scope = 5, causes = listOf(ByCause.Dirty(listOf(derived)))),
            ),
        )
        assertEquals(listOf(ByRowKind.RUN), rows.single().children.map { it.kind })
    }

    /**
     * The protocol promises a write and the run it caused share a frame, so a write is looked for
     * among its own frame's runs and nowhere else: a stop in the middle of a handler leaves a
     * write in the current frame with no run yet, and an equal write that explained a run three
     * frames ago must not hide it.
     */
    @Test
    fun `a write is explained only by a run in its own frame`() {
        val rows = ByRecompositionTree.rows(
            listOf(
                ByRecord.Write(0, 3, countWrite),
                run(frame = 3, scope = 5, causes = listOf(countWrite)),
                ByRecord.Write(0, 5, countWrite),
            ),
        )
        assertEquals(listOf("frame 5", "frame 3"), rows.map { it.text })
        assertEquals(listOf(ByRowKind.WRITE), rows[0].children.map { it.kind }, "frame 5's write has no run yet, and shows")
        assertEquals(listOf(ByRowKind.RUN), rows[1].children.map { it.kind }, "frame 3's write is its run's cause")
    }

    /** What the stream dropped is a note at the place it happened, and counts toward what is missing. */
    @Test
    fun `a gap the stream dropped is a note where it happened`() {
        val records = listOf(
            run(frame = 2, scope = 5),
            ByRecord.Gap(0, 3, dropped = 2),
            run(frame = 3, scope = 5),
            ByRecord.Gap(0, 3, dropped = 1),
            run(frame = 3, scope = 6, name = "Total"),
        )
        val frame3 = ByRecompositionTree.rows(records)[0]
        assertEquals(listOf(ByRowKind.NOTE, ByRowKind.RUN, ByRowKind.NOTE, ByRowKind.RUN), frame3.children.map { it.kind })
        assertEquals("2 records were dropped before this one because the program outran the debugger", frame3.children[0].text)
        assertEquals("1 record was dropped before this one because the program outran the debugger", frame3.children[2].text)
        assertEquals(3L, ByRecompositionTree.droppedInStream(records))
        assertTrue(ByRecompositionTree.labels(records).none { it.text.contains("dropped") }, "a gap labels nothing")
    }

    @Test
    fun `errors and refusals are rows under their frame`() {
        val rows = ByRecompositionTree.rows(
            listOf(
                run(frame = 3, scope = 5),
                ByRecord.Error(0, 3, scope = 6, name = "Flaky", error = "boom", keptPrevious = true),
                ByRecord.Refused(0, 3, scope = 7, name = "Bad", what = "state set"),
            ),
        )
        val children = rows.single().children
        assertEquals(listOf(ByRowKind.RUN, ByRowKind.ERROR, ByRowKind.REFUSED), children.map { it.kind })
        assertEquals("Flaky raised boom", children[1].text)
        assertEquals("the previous frame stayed on screen", children[1].detail)
        assertEquals("Bad: a state set during composition was refused", children[2].text)
    }

    @Test
    fun `a second runtime is named on its frames, and only then`() {
        assertEquals("frame 1", ByRecompositionTree.rows(listOf(run(frame = 1, scope = 5))).single().text)
        val rows = ByRecompositionTree.rows(listOf(run(frame = 1, scope = 5), run(frame = 1, scope = 5, runtime = 1)))
        assertEquals(listOf("runtime 0 · frame 1", "runtime 1 · frame 1"), rows.map { it.text })
    }

    @Test
    fun `row keys are stable across rebuilds and unique within one`() {
        val records = listOf(run(frame = 3, scope = 5), run(frame = 3, scope = 6, name = "Total"), ByRecord.Write(0, 3, unreadWrite))
        fun keys(rows: List<ByTreeRow>): List<String> = rows.flatMap { listOf(it.key) + keys(it.children) }
        val first = keys(ByRecompositionTree.rows(records))
        assertEquals(first, keys(ByRecompositionTree.rows(records)))
        assertEquals(first.size, first.toSet().size, "every row needs its own key: $first")
    }

    @Test
    fun `the tree of nothing is nothing`() {
        assertTrue(ByRecompositionTree.rows(emptyList()).isEmpty())
    }

    // ---- the margin labels ---------------------------------------------------

    @Test
    fun `labels count the latest frame's runs per definition and carry the first reason`() {
        val rowDef = ByTraceLocation("/app/row.by", 3, null, null)
        val labels = ByRecompositionTree.labels(
            listOf(
                run(frame = 2, scope = 5, causes = listOf(ByCause.Created)),
                run(frame = 3, scope = 5),
                run(frame = 3, scope = 8, name = "Row", defined = rowDef, causes = listOf(ByCause.Inline, ByCause.Created)),
                run(frame = 3, scope = 9, name = "Row", defined = rowDef, causes = listOf(ByCause.Created)),
                run(frame = 3, scope = 10, name = "Nowhere", defined = null),
            ),
        )
        assertEquals(
            listOf(
                ByMarginLabel("/app/counter.by", 9, "ran ×1 · count 0 → 2, set at counter.by:14"),
                ByMarginLabel("/app/row.by", 3, "ran ×2 · takes a content block, so it runs whenever its parent runs"),
            ),
            labels,
        )
    }

    @Test
    fun `each runtime's latest frame is labelled`() {
        val labels = ByRecompositionTree.labels(
            listOf(run(frame = 5, scope = 5, runtime = 0), run(frame = 2, scope = 5, runtime = 1, defined = ByTraceLocation("/app/other.by", 1, null, null))),
        )
        assertEquals(listOf("/app/counter.by", "/app/other.by"), labels.map { it.file })
    }

    @Test
    fun `a frame with no runs labels nothing`() {
        assertTrue(ByRecompositionTree.labels(listOf(ByRecord.Frame(0, 1, 0, 0, 1, 1))).isEmpty())
    }
}

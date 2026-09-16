package dev.basedpython.pycharm.debug.recompose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * bpd's actual `bpd/recompositions` bodies, captured from a real session over the real runtime
 * (basedpython-ui's `examples/counter.by`, driven by a script that clicked the counter twice
 * between two breakpoints): the first answer at the first stop, the second after two more frames.
 *
 * The wire document was written before bpd existed and the other tests are the document; these
 * two files are what bpd wrote, and they are the truth the document described. Records are oldest
 * first.
 */
class ByRecompositionsCaptureTest {

    private fun capture(name: String): ByRecompositions.Answer {
        val stream = checkNotNull(javaClass.getResourceAsStream("/debug/recompose/$name")) { "no test resource $name" }
        val body = stream.use { Json.parseToJsonElement(it.reader().readText()).jsonObject }
        val reply = ByRecompositions.parseAnswer(body)
        assertInstanceOf(ByRecompositions.Reply.Read::class.java, reply, "$name: $reply")
        return (reply as ByRecompositions.Reply.Read).answer
    }

    private fun kinds(records: List<ByRecord>): List<String> = records.map {
        when (it) {
            is ByRecord.Run -> "run"
            is ByRecord.Write -> "write"
            is ByRecord.Frame -> "frame"
            is ByRecord.Error -> "error"
            is ByRecord.Refused -> "refused"
            is ByRecord.Gap -> "gap"
        }
    }

    @Test
    fun `the first stop is the first frame, four runs and the frame record, oldest first`() {
        val answer = capture("recompositions-1.json")
        assertEquals(1, answer.runtimes)
        assertTrue(answer.tracing)
        assertEquals(0L, answer.dropped)
        assertEquals(0, answer.unreadable, "every record bpd wrote is one this reads")
        assertEquals(5, answer.kept.size)
        assertEquals(listOf("run", "run", "run", "run", "frame"), kinds(answer.kept))
        assertTrue(answer.kept.all { it.runtime == 0 && it.frame == 0L })

        val runs = answer.kept.filterIsInstance<ByRecord.Run>()
        assertEquals(listOf("Counter", "Counter", "App", "root"), runs.map { it.name }, "children finish before their parents")
        assertTrue(runs.all { it.origin == "first" && it.causes == listOf(ByCause.Created) })

        val counter = runs[0]
        assertEquals(3L, counter.scope)
        assertEquals(2L, counter.parent)
        assertTrue(counter.defined!!.file.endsWith("/examples/counter.by"), counter.defined.file)
        assertEquals(4, counter.defined.line)
        assertTrue(counter.defined.generated!!.file.endsWith("/out/examples/counter.py"))
        assertEquals(103, counter.defined.generated.line)
        assertEquals(22, counter.called?.line, "called from the App's line")

        val root = runs[3]
        assertEquals(0L, root.scope)
        assertNull(root.parent)
        assertNull(root.called, "`called` is null for the root")
        assertNull(root.defined?.generated, "the driver is a plain .py with no map: the generated place is the place itself")

        val frame = answer.kept.last() as ByRecord.Frame
        assertEquals(4, frame.runs)
        assertEquals(0, frame.skips)
        assertTrue(frame.composeNs > 0 && frame.commitNs > 0)
    }

    @Test
    fun `the second stop carries the click as a state write and the rerun it caused`() {
        val answer = capture("recompositions-2.json")
        assertEquals(11, answer.kept.size)
        assertEquals(0, answer.unreadable)
        assertEquals(
            listOf("run", "run", "run", "run", "frame", "write", "run", "frame", "write", "run", "frame"),
            kinds(answer.kept),
        )
        assertEquals(0L, answer.kept.first().frame, "oldest first")
        assertEquals(2L, answer.kept.last().frame)

        val write = answer.kept[5] as ByRecord.Write
        assertEquals(1L, write.frame)
        val cause = write.cause as ByCause.State
        assertEquals("count", cause.declaredName)
        assertEquals("0", cause.old)
        assertEquals("1", cause.new)
        assertEquals("state", cause.kind)
        assertEquals("set", cause.op)
        assertNull(cause.at)
        assertEquals(1, cause.readers)
        assertTrue(!cause.posted)
        assertNotNull(cause.thread)
        assertTrue(cause.written.file.endsWith("/examples/counter.by"), "written at a .by location: ${cause.written.file}")
        assertEquals(7, cause.written.line)
        assertEquals(113, cause.written.generated?.line)
        assertTrue(cause.declared!!.file.endsWith("/examples/counter.by"))
        assertEquals(6, cause.declared.line)

        val rerun = answer.kept[6] as ByRecord.Run
        assertEquals("Counter", rerun.name)
        assertEquals("self", rerun.origin, "popped from the dirty heap")
        assertEquals(3L, rerun.scope)
        assertEquals(listOf(cause), rerun.causes, "the run's cause is the write, read the same way")

        val second = answer.kept[8] as ByRecord.Write
        val secondCause = second.cause as ByCause.State
        assertEquals("1", secondCause.old)
        assertEquals("2", secondCause.new)
        assertEquals(2L, second.frame)
    }

    /** What the window makes of the real thing: three frames, the write shown as its run's cause and not again. */
    @Test
    fun `the tree and the labels read the capture as the docs say`() {
        val answer = capture("recompositions-2.json")
        val rows = ByRecompositionTree.rows(answer.kept)
        assertEquals(listOf("frame 2", "frame 1", "frame 0"), rows.map { it.text })
        assertEquals("1 run · 0 skips", rows[0].detail?.substringBefore(" · compose"))

        val frame1 = rows[1]
        assertEquals(listOf(ByRowKind.RUN), frame1.children.map { it.kind }, "the write is its run's cause, not a row of its own")
        val counter = frame1.children.single()
        assertEquals("Counter", counter.text)
        assertTrue(counter.detail!!.startsWith("self · "), counter.detail)
        assertEquals("count 0 → 1, set at counter.by:7", counter.children.single().text)
        assertEquals(7, counter.children.single().target?.line)

        val labels = ByRecompositionTree.labels(answer.kept)
        assertEquals(1, labels.size)
        assertEquals(4, labels.single().line)
        assertEquals("ran ×1 · count 1 → 2, set at counter.by:7", labels.single().text)
    }

    /** The first stop's answer held while the second is pulled: the second is the truth for every frame it carries. */
    @Test
    fun `a watch that saw the first stop, then the second pull, is the second pull`() {
        val first = capture("recompositions-1.json").kept
        val second = capture("recompositions-2.json").kept
        assertEquals(second, ByRecordMerge.merge(first, second))
        assertEquals(first, second.take(5), "the second answer begins with everything the first held")
    }
}

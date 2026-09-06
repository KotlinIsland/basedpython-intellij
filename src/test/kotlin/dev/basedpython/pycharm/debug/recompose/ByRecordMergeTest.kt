package dev.basedpython.pycharm.debug.recompose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A pull folded into a watch: the pull is the truth for every frame it carries, and what was held
 * survives only in frames older than the pull's oldest.
 */
class ByRecordMergeTest {

    private fun state(name: String) = ByCause.State(
        cell = name.hashCode().toLong(), kind = "list", op = "append", at = null, old = "None", new = "1",
        declared = null, declaredName = name, written = ByTraceLocation("/a.by", 3, null, null),
        thread = 1, posted = false, readers = 1,
    )

    private val flag = state("flag")
    private val items = state("items")

    private fun run(frame: Long, scope: Long, runtime: Int = 0) = ByRecord.Run(
        runtime, frame, scope, 0, "Counter", null, null, null, "self", listOf(ByCause.Created), emptyList(), emptyList(), 1,
    )

    private fun write(frame: Long, cause: ByCause, runtime: Int = 0) = ByRecord.Write(runtime, frame, cause)

    @Test
    fun `a pull after a watch does not double`() {
        val held = listOf(run(1, 5), write(1, items), run(2, 5))
        val pulled = listOf(run(1, 5), write(1, items), run(2, 5), run(3, 5))
        assertEquals(pulled, ByRecordMerge.merge(held, pulled))
    }

    @Test
    fun `frames older than the pull's oldest survive, ahead of the pull`() {
        val fallen = listOf(run(1, 5), ByRecord.Frame(0, 1, 1, 0, 1, 1))
        val held = fallen + listOf(run(2, 5))
        val pulled = listOf(run(2, 5), run(3, 5))
        assertEquals(fallen + pulled, ByRecordMerge.merge(held, pulled))
    }

    /**
     * The ring's halving and bpd's cap cut wherever they fall, so the pull's oldest frame is
     * usually half a frame. The pull is the truth for that frame too: the write that fell off is
     * gone, the two surviving writes are two, and nothing is tripled — which a positional identity
     * (the reviewer's probe) got wrong on both counts.
     */
    @Test
    fun `the pull's oldest frame is the pull's, even when the ring cut it in half`() {
        val held = listOf(run(0, 4), write(1, flag), write(1, items), write(1, items), run(1, 5))
        val pulled = listOf(write(1, items), write(1, items), run(1, 5))
        assertEquals(listOf(run(0, 4)) + pulled, ByRecordMerge.merge(held, pulled))
    }

    /** Two identical appends from one loop are two records, and an identity that collapsed them would lose one. */
    @Test
    fun `identical writes in one frame stay distinct`() {
        val twice = listOf(write(1, items), write(1, items))
        assertEquals(twice, ByRecordMerge.merge(twice, twice))
        assertEquals(twice, ByRecordMerge.merge(listOf(twice[0]), twice))
    }

    /** A watch that saw every frame, then a pull that carries every frame: the pull, once. */
    @Test
    fun `a watch that saw everything the pull carries is replaced by the pull`() {
        val everything = listOf(run(0, 4), ByRecord.Frame(0, 0, 1, 0, 1, 1), write(1, items), run(1, 5), ByRecord.Frame(0, 1, 1, 0, 1, 1))
        assertEquals(everything, ByRecordMerge.merge(everything, everything))
        val partial = everything.drop(2)
        assertEquals(everything.take(2) + partial, ByRecordMerge.merge(everything, partial))
    }

    @Test
    fun `each runtime has its own oldest frame`() {
        val held = listOf(run(1, 5), run(1, 5, runtime = 1), run(2, 5), run(2, 5, runtime = 1))
        val pulled = listOf(run(2, 5), run(1, 5, runtime = 1), run(2, 5, runtime = 1))
        assertEquals(listOf(run(1, 5)) + pulled, ByRecordMerge.merge(held, pulled))
    }

    @Test
    fun `a runtime the pull does not mention is kept`() {
        val gone = listOf(run(7, 5, runtime = 1))
        val pulled = listOf(run(2, 5))
        assertEquals(gone + pulled, ByRecordMerge.merge(gone, pulled))
    }

    /** A gap the stream dropped is filled by a pull that carries its frame, and stays when none does. */
    @Test
    fun `a gap in a frame the pull carries is filled by the pull`() {
        assertEquals(listOf(run(2, 5)), ByRecordMerge.merge(listOf(ByRecord.Gap(0, 2, 3), run(2, 5)), listOf(run(2, 5))))
        val older = listOf(ByRecord.Gap(0, 1, 3), run(1, 4))
        assertEquals(older + listOf(run(2, 5)), ByRecordMerge.merge(older + listOf(run(2, 5)), listOf(run(2, 5))))
    }

    @Test
    fun `nothing held is the pull, and nothing pulled is what was held`() {
        val held = listOf(run(1, 5))
        assertEquals(held, ByRecordMerge.merge(emptyList(), held))
        assertEquals(held, ByRecordMerge.merge(held, emptyList()))
    }
}

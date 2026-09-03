package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ByAlignment] as the arithmetic it is, and as the picture that arithmetic makes.
 *
 * The rendered cases are the point of the file. What the layout has to get right is not a list of
 * integers but where the `=` ends up, and an assertion written as the line the reader would see is
 * the only one that fails legibly when it is wrong.
 */
class ByAlignmentTest {

    /**
     * One line of a block, written the way the author wrote it plus what `by` says about it.
     *
     * [lead] is the code before the gap, [hint] the hint standing at the end of it — empty for the
     * lines `by` has nothing to say about — and [tail] the `=` and whatever follows.
     */
    private data class Line(val lead: String, val gap: Int, val hint: String, val tail: String)

    /**
     * The block as the editor would draw it, one line per row.
     *
     * The hint's glyphs are laid down at their own width and the rest of the line starts at the
     * width the inlay *reports*, which is exactly what the editor does — nothing clips a hint to its
     * reported width. So an overlap here is an overlap on screen, and a row that comes out shorter
     * than its hint is a hint drawing over blanks it gave back.
     */
    private fun draw(lines: List<Line>): String {
        val deltas = ByAlignment.layout(
            lines.map { ByAlignment.Member(it.lead.length, it.hint.length, it.gap) },
        )
        return lines.zip(deltas) { line, delta ->
            val reported = (line.hint.length + delta).coerceAtLeast(0)
            val row = StringBuilder(line.lead)
            // Where the document's own text resumes: after the room the inlay claims.
            repeat(reported + line.gap) { row.append(' ') }
            row.append(line.tail)
            // The glyphs, painted over whatever is under them.
            row.replace(line.lead.length, line.lead.length + line.hint.length, line.hint)
            row.toString()
        }.joinToString("\n")
    }

    private fun layout(vararg members: Triple<Int, Int, Int>): List<Int> =
        ByAlignment.layout(members.map { ByAlignment.Member(it.first, it.second, it.third) })

    // region: the block that started it

    @Test
    fun `a hint wider than the padding pulls the block straight rather than apart`() {
        // `a = [1, 2]` infers `list[int]`, so the hint is eleven columns against five of padding:
        // narrowing the hint alone can never reach the column, and `basdf` has to give way.
        val block = listOf(
            Line(lead = "a", gap = 5, hint = ": list[int]", tail = "= [1, 2]"),
            Line(lead = "basdf", gap = 1, hint = "", tail = "= 1"),
        )
        assertEquals(
            """
            a: list[int] = [1, 2]
            basdf        = 1
            """.trimIndent(),
            draw(block),
        )
    }

    @Test
    fun `a hint narrower than the padding is drawn into it and nothing moves`() {
        // The happy case, and the one absorption alone would have covered: the room is already there.
        val block = listOf(
            Line(lead = "a", gap = 9, hint = ": int", tail = "= f()"),
            Line(lead = "basdfghij", gap = 1, hint = "", tail = "= 1"),
        )
        assertEquals(
            """
            a: int    = f()
            basdfghij = 1
            """.trimIndent(),
            draw(block),
        )
    }

    @Test
    fun `two hints of different widths still land on one column`() {
        val block = listOf(
            Line(lead = "a", gap = 5, hint = ": int", tail = "= f()"),
            Line(lead = "basdf", gap = 1, hint = ": list[str]", tail = "= g()"),
        )
        assertEquals(
            """
            a: int           = f()
            basdf: list[str] = g()
            """.trimIndent(),
            draw(block),
        )
    }

    // endregion

    // region: the properties that make it safe to leave on

    @Test
    fun `with no hints drawn the block is exactly as it was written`() {
        // The property that matters most for push-to-hint: letting the key up has to put the source
        // back, not leave it padded for hints that are no longer there.
        val block = listOf(
            Line(lead = "a", gap = 5, hint = "", tail = "= [1, 2]"),
            Line(lead = "basdf", gap = 1, hint = "", tail = "= 1"),
        )
        assertEquals(
            """
            a     = [1, 2]
            basdf = 1
            """.trimIndent(),
            draw(block),
        )
    }

    @Test
    fun `no member is ever asked to give back more room than its hint is wide`() {
        // A line with no hint has nothing to narrow, so a delta below `-hintColumns` would be an
        // instruction it cannot carry out. Swept over a range wide enough to catch an off-by-one in
        // either the column or the separator.
        for (lead in 0..12) {
            for (hint in 0..14) {
                for (gap in 1..9) {
                    val others = listOf(
                        ByAlignment.Member(lead, hint, gap),
                        // A second member sharing the column, which is what a group guarantees.
                        ByAlignment.Member(lead + gap - 1, 0, 1),
                    )
                    val deltas = ByAlignment.layout(others)
                    assertTrue(
                        deltas[0] >= -hint,
                        "lead=$lead hint=$hint gap=$gap gave back ${-deltas[0]} of $hint",
                    )
                    assertTrue(deltas[1] >= 0, "a line with no hint was asked to narrow")
                }
            }
        }
    }

    @Test
    fun `a block is never squeezed below the column the author typed`() {
        // Every member reaches at least its own `lead + gap`, so a block with small hints keeps the
        // author's own spacing rather than being pulled in to the tightest one that would fit.
        val deltas = layout(Triple(1, 0, 9), Triple(9, 0, 1))
        assertEquals(listOf(0, 0), deltas)
    }

    // endregion

    // region: the arithmetic

    @Test
    fun `the widest hint sets the column and the rest are padded out to it`() {
        // lead 1 + hint 11 + one separator = 13; `basdf` sits at 5 and takes 7 more to reach it.
        assertEquals(listOf(-4, 7), layout(Triple(1, 11, 5), Triple(5, 0, 1)))
    }

    @Test
    fun `a lone separator column is kept between the widest hint and the code`() {
        val members = listOf(ByAlignment.Member(1, 11, 5), ByAlignment.Member(5, 0, 1))
        val deltas = ByAlignment.layout(members)
        val column = members.zip(deltas) { member, delta ->
            member.leadColumns + member.hintColumns + member.gapColumns + delta
        }
        assertEquals(listOf(13, 13), column)
        assertEquals(ByAlignment.SEPARATOR, column[0] - (members[0].leadColumns + members[0].hintColumns))
    }

    @Test
    fun `an empty block asks for nothing`() {
        assertEquals(emptyList<Int>(), ByAlignment.layout(emptyList()))
    }

    // endregion
}

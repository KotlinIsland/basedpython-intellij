package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * [ByAlignment] as the arithmetic it is, and as the picture that arithmetic makes.
 *
 * The rendered cases are the point of the file. What the layout has to get right is not a list of
 * numbers but where the `=` ends up, and an assertion written as the line the reader would see is
 * the only one that fails legibly when it is wrong.
 *
 * Measured in characters throughout, with the [separator] set to one of them. [ByAlignment] does not
 * know what it is counting — in the editor it counts pixels, because a column is a fiction for any
 * glyph the editor does not draw one advance wide ([ByAlignedColumnTest] is where that is settled) —
 * and a character grid is the unit this arithmetic can be *read* in.
 */
class ByAlignmentTest {

    /** One character of breathing room: what [ByAlignment] is given on a grid one glyph wide. */
    private val separator = 1f

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
     *
     * A member reports the column less its own code and padding, which is what [ByAlignedColumn]
     * asks of a seat — not a correction to the hint's natural width, for the reason given on
     * [ByAlignment.column].
     */
    private fun draw(lines: List<Line>): String {
        val members = lines.map {
            ByAlignment.Member(it.lead.length.toFloat(), it.hint.length.toFloat(), it.gap.toFloat())
        }
        val column = ByAlignment.column(members, separator)
        return lines.zip(members) { line, member ->
            val reported = (column - member.lead - member.gap).roundToInt().coerceAtLeast(0)
            val row = StringBuilder(line.lead)
            // Where the document's own text resumes: after the room the inlay claims.
            repeat(reported + line.gap) { row.append(' ') }
            row.append(line.tail)
            // The glyphs, painted over whatever is under them.
            row.replace(line.lead.length, line.lead.length + line.hint.length, line.hint)
            row.toString()
        }.joinToString("\n")
    }

    /** The column of a block written as `lead, hint, gap` per line. */
    private fun column(vararg members: Triple<Int, Int, Int>): Float =
        ByAlignment.column(
            members.map { ByAlignment.Member(it.first.toFloat(), it.second.toFloat(), it.third.toFloat()) },
            separator,
        )

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

    @Test
    fun `a column nobody padded is held just the same`() {
        // Single spaces throughout: the `=` share a column because the names are one character each,
        // not because anyone lined them up. Hints of unequal width break that column exactly as they
        // break a padded one, so there is nothing here for the layout to treat differently — the
        // room simply all has to be taken, none of it given back. `by` reports the block for the
        // same reason (`ty_ide/src/alignment.rs`).
        val block = listOf(
            Line(lead = "a", gap = 1, hint = ": 2", tail = "= 1 + 1"),
            Line(lead = "b", gap = 1, hint = ": True", tail = "= True or False"),
        )
        assertEquals(
            """
            a: 2    = 1 + 1
            b: True = True or False
            """.trimIndent(),
            draw(block),
        )
    }

    @Test
    fun `a line with no hint of its own is pushed out to hold the column`() {
        // The price of taking a column nobody padded: `y` has nothing to narrow, so the room it owes
        // comes out of a spacer. This is the shape that surprises people, so it is written down as
        // the picture rather than left to be inferred from the arithmetic.
        val block = listOf(
            Line(lead = "x", gap = 1, hint = ": dict[str, Any]", tail = "= json.loads(blob)"),
            Line(lead = "y", gap = 1, hint = "", tail = "= 1"),
        )
        assertEquals(
            """
            x: dict[str, Any] = json.loads(blob)
            y                 = 1
            """.trimIndent(),
            draw(block),
        )
    }

    @Test
    fun `a member with no gap at all is still a member`() {
        // `by` reports `ab=f()` with a gap of nought — no spaces is not no column. Dropped instead,
        // it would take the whole group with it and `a =1` would be left where it started.
        //
        // The blank before each `=` is the separator, which every block gets and this one has
        // nowhere to take from: a hint butted straight against an `=` reads as one token.
        val block = listOf(
            Line(lead = "ab", gap = 0, hint = ": int", tail = "=f()"),
            Line(lead = "a", gap = 1, hint = ": str", tail = "=1"),
        )
        assertEquals(
            """
            ab: int =f()
            a: str  =1
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
    fun `no member is ever asked for less room than it already had`() {
        // The room a member reports is the column less its own code and padding, so a column short of
        // any member's `lead + gap` would ask that line for a negative width — an instruction it
        // cannot carry out, least of all the line with no hint to narrow. Swept over a range wide
        // enough to catch an off-by-one in either the column or the separator.
        for (lead in 0..12) {
            for (hint in 0..14) {
                for (gap in 1..9) {
                    val members = listOf(
                        ByAlignment.Member(lead.toFloat(), hint.toFloat(), gap.toFloat()),
                        // A second member sharing the column, which is what a group guarantees.
                        ByAlignment.Member((lead + gap - 1).toFloat(), 0f, 1f),
                    )
                    val column = ByAlignment.column(members, separator)
                    for ((index, member) in members.withIndex()) {
                        assertTrue(
                            column - member.lead - member.gap >= 0f,
                            "lead=$lead hint=$hint gap=$gap left member $index asking for " +
                                "${column - member.lead - member.gap}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a block is never squeezed below the column the author typed`() {
        // Every member reaches at least its own `lead + gap`, so a block with small hints keeps the
        // author's own spacing rather than being pulled in to the tightest one that would fit.
        assertEquals(10f, column(Triple(1, 0, 9), Triple(9, 0, 1)))
    }

    // endregion

    // region: the arithmetic

    @Test
    fun `the widest hint sets the column and the rest are padded out to it`() {
        // lead 1 + hint 11 + one separator = 13, against the 6 the author's own text reaches. Both
        // members then report the same 7 for opposite reasons: the hinted line gives back 4 of its 5
        // spaces and draws 11 of glyphs over 7 of room, and `basdf` takes 6 more than the 1 it had.
        val members = listOf(ByAlignment.Member(1f, 11f, 5f), ByAlignment.Member(5f, 0f, 1f))
        val column = ByAlignment.column(members, separator)
        assertEquals(13f, column)
        assertEquals(listOf(7f, 7f), members.map { column - it.lead - it.gap })
    }

    @Test
    fun `a lone separator column is kept between the widest hint and the code`() {
        val members = listOf(ByAlignment.Member(1f, 11f, 5f), ByAlignment.Member(5f, 0f, 1f))
        val column = ByAlignment.column(members, separator)
        assertEquals(separator, column - (members[0].lead + members[0].hint))
    }

    // endregion
}

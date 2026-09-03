package dev.basedpython.pycharm.lsp.inlay

/**
 * What a column of lined-up assignments has to do to stay a column once hints are drawn in it.
 *
 * A hint costs exactly the room the same characters would cost as source (see
 * [ByInlayHintPresentation.width]), which is the right price for a hint that stands in for code —
 * and it is what takes a hand-aligned block apart:
 *
 * ```
 * a     = [1, 2]    ->  a: list[int]     = [1, 2]
 * basdf = 1         ->  basdf = 1
 * ```
 *
 * The obvious repair — let the hint spend the padding the author wrote instead of adding its own
 * room — does not work on its own, and the example is why: five spaces of padding against an
 * eleven-column hint leaves the `=` six columns out however the hint is narrowed. Restoring the
 * column means moving `basdf` too, and a line with no hint on it has nothing to narrow.
 *
 * So both directions are one calculation. Every member is brought to one column, whether that means
 * *giving back* room the author left or *taking* room the author did not:
 *
 * ```
 * a: list[int] = [1, 2]     hint gives back 4 of its 5 spaces
 * basdf        = 1          line takes 7 more
 * ```
 *
 * Which lines belong together is `by`'s answer, not a guess made here — see `by/alignmentGroups`
 * and [ByAlignedColumn]. What is decided here is only how wide each one ends up, because that
 * depends on which hints are on screen at this instant, and nothing outside the editor knows that.
 */
object ByAlignment {

    /** The blank columns left between the widest member's hint and the column, so it can breathe. */
    const val SEPARATOR: Int = 1

    /**
     * One line of a group, measured in columns.
     *
     * Columns rather than pixels: the editor font is a code font, so a column is a fixed width and
     * the whole calculation is integer arithmetic that can be read and tested. The one conversion to
     * pixels happens where the width is finally reported, against the same font the hint is drawn
     * in.
     */
    data class Member(
        /** Columns from the start of the line to the end of the target — the code before the gap. */
        val leadColumns: Int,
        /**
         * Columns of hint drawn at the end of that target **right now**.
         *
         * Right now, and not as collected: a kind can be set to draw only while a key is held, so
         * this is nought for the same hint a moment later. That is exactly why the sizing is done
         * here and not by the server.
         */
        val hintColumns: Int,
        /** The spaces the author left between the target and the `=`; never fewer than one. */
        val gapColumns: Int,
    )

    /**
     * How many columns each member has to gain, negative where it has room to give back.
     *
     * The column every member is brought to is the furthest right of
     *
     * - the column the author already typed, so a group is never *squeezed* below what it reads as
     *   with no hints at all, and
     * - what each member needs to fit its own hint with [SEPARATOR] to spare.
     *
     * Two properties fall out of that first term, and both are worth stating because they are what
     * make this safe to leave switched on:
     *
     * - **With no hints drawn, every delta is nought.** Each member's `lead + gap` *is* the shared
     *   column, so the maximum is that column and nothing moves. Releasing the push key puts the
     *   block back exactly as written rather than leaving it padded for hints that are not there.
     * - **No member ever gives back more than its hint is wide.** A negative delta is bounded below
     *   by `-hintColumns`, so this can never ask a line to swallow padding it has no hint to swallow
     *   it with — least of all a line that has no hint at all.
     */
    fun layout(members: List<Member>): List<Int> {
        if (members.isEmpty()) return emptyList()
        val column = column(members)
        return members.map { column - (it.leadColumns + it.hintColumns + it.gapColumns) }
    }

    /**
     * The one column every member is brought to, counted from the start of the line.
     *
     * **The absolute column is what the caller wants, not the per-member deltas**, and the reason is
     * a pixel. A column is a fraction of a pixel wide — the editor lays a line out by accumulating
     * fractional advances and flooring each position — while an inlay reports one integer. Composing
     * two separately rounded measurements does not land where rounding once does, and the gap between
     * them is a whole pixel: laid out that way on a real editor, the two `=` of a two-line block came
     * out at 109 and 108 (`ByAlignedColumnTest`). Measuring each line's inlay as *the column, less the
     * code and the padding around it* rounds once instead of twice, and every member lands together.
     */
    fun column(members: List<Member>): Int {
        val typed = members.maxOf { it.leadColumns + it.gapColumns }
        val needed = members.maxOf { it.leadColumns + it.hintColumns + SEPARATOR }
        return maxOf(typed, needed)
    }
}

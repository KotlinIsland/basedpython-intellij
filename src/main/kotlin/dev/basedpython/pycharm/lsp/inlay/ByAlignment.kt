package dev.basedpython.pycharm.lsp.inlay

/**
 * What a column of lined-up assignments has to do to stay a column once hints are drawn in it.
 *
 * A hint costs exactly the room the same characters would cost as source (see
 * [ByInlayHintPresentation.width]), which is the right price for a hint that stands in for code —
 * and it is what takes a block of lined-up `=` apart:
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

    /**
     * One line of a group, as three widths in one unit.
     *
     * **Which unit is the caller's.** [ByAlignedColumn] passes pixels of the editor's own font,
     * measured by [ByEditorTextWidth], because that is the only unit in which a line of source and a
     * hint drawn beside it can be compared: a column is a fiction for any character the editor does
     * not draw one advance wide. `ByAlignmentTest` passes characters, which is the same arithmetic
     * on a grid where every glyph is one wide, and is how the layout is read as a picture.
     *
     * Nothing here divides, rounds or compares against a constant of its own, so the arithmetic is
     * the same in either.
     */
    data class Member(
        /** From the start of the line to the end of the target — the code before the gap. */
        val lead: Float,
        /**
         * The hint drawn at the end of that target **right now**.
         *
         * Right now, and not as collected: a kind can be set to draw only while a key is held, so
         * this is nought for the same hint a moment later. That is exactly why the sizing is done
         * here and not by the server.
         */
        val hint: Float,
        /**
         * The padding the author left between the target and the `=`.
         *
         * Nought is an ordinary value: `a=1` has no spaces and still has its `=` in a column.
         */
        val gap: Float,
    )

    /**
     * The one column every member is brought to, measured from the start of the line.
     *
     * [separator] is the blank left between the widest member's hint and the column, so it can
     * breathe — one space of whatever the caller is measuring in.
     *
     * [members] is never empty: a column is asked for by a line of the block, and a line of the
     * block is a member. An empty list has no column and is refused rather than given a nought that
     * would quietly pull a block to the left margin.
     *
     * The column is the furthest right of
     *
     * - where the author's own text puts the `=`, so a group is never *squeezed* in below what it
     *   reads as with no hints at all, and
     * - what each member needs to fit its own hint with [separator] to spare.
     *
     * **The absolute column is what the caller wants, not a per-member delta**, and the reason is a
     * pixel. The editor lays a line out by accumulating fractional advances, while an inlay reports
     * one integer. Composing two separately rounded measurements does not land where rounding once
     * does, and the gap between them is a whole pixel: laid out that way on a real editor, the two
     * `=` of a two-line block came out at 109 and 108 (`ByAlignedColumnTest`). So a member's inlay is
     * sized as *this column, less the code and the padding around it* — one subtraction, one
     * rounding, and [ByAlignedColumn] is where that rounding is argued.
     *
     * Two properties fall out of the first term, and both are worth stating because they are what
     * make this safe to leave switched on:
     *
     * - **With no hints drawn, a block of text on the editor's advance grid does not move.** Each
     *   member's `lead + gap` *is* the shared column, so the maximum is that column and every member
     *   asks for exactly the padding it already has. Releasing the push key puts such a block back
     *   as written rather than leaving it padded for hints that are not there. A block whose lead
     *   holds a character the editor draws off the grid — a wide glyph from a fallback font — is the
     *   exception, and it is the one place this *does* move text nobody hinted: `by` reports those
     *   lines as one column because they are one column in the source, the editor never drew them in
     *   one, and bringing them to a shared x is the whole point of measuring rather than counting.
     * - **No member is ever asked for less room than it already had.** The column is at least every
     *   member's own `lead + gap`, so the room it reports back is never negative — this can never
     *   ask a line to swallow padding it has no hint to swallow it with, least of all a line that
     *   has no hint at all.
     */
    fun column(members: List<Member>, separator: Float): Float {
        val typed = members.maxOf { it.lead + it.gap }
        val needed = members.maxOf { it.lead + it.hint + separator }
        return maxOf(typed, needed)
    }
}

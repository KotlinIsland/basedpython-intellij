package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Rectangle
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * One block of assignments the author lined up, and the inlays that keep it lined up.
 *
 * `by` says which lines belong together (`by/alignmentGroups`); this holds them in one editor and
 * decides, from moment to moment, how wide each one's inlay has to be. The arithmetic is
 * [ByAlignment.column] and is pure; what lives here is the part that cannot be — which hints are on
 * screen this instant, the conversion of a column into pixels, and telling the editor when either
 * has moved.
 *
 * **Every line of a block carries exactly one inlay at its gap**, a hint where there is one and a
 * [ByAlignmentSpacer] where there is not, even on the lines that need no room at all today. That is
 * not an accident of the algorithm but the thing that keeps the block square when hints go away: the
 * editor refuses a zero-width inline element, so an inlay that is standing by still costs its line
 * one pixel ([ByInlayHintPresentation.HIDDEN_WIDTH]). One pixel on every line of a block is a
 * constant the block carries evenly and nobody can see; one pixel on all but one of them is a step.
 *
 * Held alive by its own seats: each presentation refers back to the seat it sits in, and the inlays
 * hold the presentations, so a column lives exactly as long as the inlays it is arranging. That
 * matters because [ByHintPush] keeps its watchers weakly.
 */
class ByAlignedColumn(override val editor: Editor) : ByHintPush.Watcher {

    private val seats = ArrayList<Seat>()

    /**
     * One line of the block: the room it has, and the one inlay whose width this column decides.
     *
     * [leadColumns] and [gapColumns] are read off the document as it was when `by` answered. An edit
     * makes them stale, and nothing is done about that on purpose — the daemon restarts the pass
     * against the new text, which is the same staleness the hint's own text already has.
     */
    inner class Seat internal constructor(
        private val leadColumns: Int,
        private val gapColumns: Int,
    ) {
        /** Hints drawn where the gap starts. Empty on a line `by` had nothing to say about. */
        internal val hints = ArrayList<ByInlayHintPresentation>()

        /** The empty inlay standing in for a line with no hint. Set once, before anything is drawn. */
        internal var spacer: ByAlignmentSpacer? = null

        /** What this line is asking for as a member of the block, measured *now*. */
        internal val member: ByAlignment.Member
            get() = ByAlignment.Member(
                leadColumns = leadColumns,
                hintColumns = hints.sumOf { it.shownColumns },
                gapColumns = gapColumns,
            )

        /**
         * Columns this seat's inlay adds, negative where its hint has room to give back.
         *
         * Read only to tell one layout from another — see [ByInlayHintPresentation.updateState]. The
         * width itself is *not* built from this: see [requiredWidth].
         */
        val deltaColumns: Int
            get() = ByAlignment.layout(members()).getOrElse(seats.indexOf(this)) { 0 }

        /**
         * The pixels this line's inlays occupy between the end of its code and its `=`.
         *
         * Measured from the start of the line — the whole column, less the code before the gap and
         * the spaces after it — rather than as a natural width plus a correction. Rounding once
         * instead of twice is the difference between a block that lines up and one that is a pixel
         * out; [ByAlignment.column] carries the arithmetic.
         */
        internal fun requiredWidth(): Int =
            columnWidth(editor, ByAlignment.column(members())) -
                columnWidth(editor, leadColumns) -
                columnWidth(editor, gapColumns)

        /**
         * What one of this seat's hints reports.
         *
         * The last of them carries the line's whole requirement and the rest cost their own text, so
         * that a seat holding more than one hint still adds up to the column. In practice a seat
         * holds one: `by` puts a variable's type hint at the end of the target, and nothing else
         * stands there.
         */
        internal fun widthFor(hint: ByInlayHintPresentation, natural: Int): Int {
            if (hint !== hints.lastOrNull()) return natural
            return requiredWidth() - hints.dropLast(1).sumOf { it.naturalWidth() }
        }

        /** Puts a hint in this seat: the block sizes it, and it reports the block's width. */
        internal fun take(hint: ByInlayHintPresentation) {
            hints += hint
            hint.seat = this
        }

        /** The empty inlay for a line the server had no hint for. */
        internal fun standIn(): ByAlignmentSpacer =
            ByAlignmentSpacer(editor, this).also { spacer = it }

        internal fun revalidate() {
            hints.forEach { it.revalidate() }
            spacer?.revalidate()
        }
    }

    /** Adds a line to the block, in the order the lines are written. */
    fun seat(leadColumns: Int, gapColumns: Int): Seat = Seat(leadColumns, gapColumns).also { seats += it }

    /**
     * What every line of the block is asking for at this moment.
     *
     * Recomputed on every ask rather than cached, because the answer changes with the push key and
     * there is no event that says "a sibling's hint just went away" other than the one that already
     * arrives here. Blocks are a handful of lines, so recomputing is cheaper than the bookkeeping
     * that would avoid it.
     */
    private fun members(): List<ByAlignment.Member> = seats.map { it.member }

    /**
     * The push key moved: re-measure the whole block, not just the hints that changed.
     *
     * Both halves matter and the order between them does. Every hint's visibility is settled first,
     * because a seat's width is a function of what the *other* seats are showing — measuring one
     * while another still reports its old visibility lays the block out against a state that never
     * existed. Then every inlay in the block is told to re-measure, hints and spacers alike, since a
     * single hint appearing moves every line.
     *
     * Called on the EDT by [ByHintPush], inside its `InlayModel.execute(batchMode = true)`, so the
     * editor lays the lot out once.
     */
    override fun pushStateChanged() {
        for (seat in seats) seat.hints.forEach { it.refreshShown() }
        for (seat in seats) seat.revalidate()
    }

    /** Whether anything here is drawn only while a key is held, and so whether to watch for it. */
    fun watchesPush(): Boolean = seats.any { seat -> seat.hints.any { it.watchesPush } }
}

/**
 * An inlay that draws nothing and is exactly as wide as its line's share of the column.
 *
 * The counterpart to a hint giving room back: on a line with no hint there is nothing to narrow, so
 * the room has to come from somewhere, and this is it. It has no text, no tint and no tooltip —
 * every pixel of it is padding, which is why it can be told apart from a hint that merely happens to
 * be blank.
 */
class ByAlignmentSpacer(
    private val editor: Editor,
    private val seat: ByAlignedColumn.Seat,
) : BasePresentation() {

    /**
     * Never below [ByInlayHintPresentation.HIDDEN_WIDTH], which the editor requires of any inline
     * element. A spacer asking for nothing is the ordinary case rather than a mistake — see the note
     * on [ByAlignedColumn] about why every line of a block carries one either way.
     */
    override val width: Int
        get() = seat.requiredWidth().coerceAtLeast(ByInlayHintPresentation.HIDDEN_WIDTH)

    /** A whole line box, so the spacer occupies its line the way a character does. */
    override val height: Int get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) = Unit

    /** Re-measure me. See [ByInlayHintPresentation.revalidate] for what the platform does with it. */
    internal fun revalidate() {
        val size = Dimension(width, height)
        fireSizeChanged(size, size)
    }

    /**
     * A spacer is nothing but its width, so that is the whole of its state.
     *
     * Reported as changed whenever the width differs, because a spacer that quietly kept the width
     * it was measured at is a line that stopped following its block.
     */
    override fun updateState(previousPresentation: InlayPresentation): Boolean {
        val previous = previousPresentation as? ByAlignmentSpacer ?: return true
        return previous.width != width
    }

    override fun toString(): String = "alignment spacer"
}

/**
 * [columns] of the editor's own font, in pixels, signed.
 *
 * Measured with `getStringBounds` and rounded to nearest, exactly as
 * [ByInlayHintPresentation.textWidth] measures the hint's own text — the two have to be measured the
 * same way or a column given back and a column of text would not cancel, and the block would drift
 * by a pixel per line.
 */
internal fun columnWidth(editor: Editor, columns: Int): Int {
    if (columns == 0) return 0
    val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
    val metrics = editor.contentComponent.getFontMetrics(font)
    val spaces = " ".repeat(columns.absoluteValue)
    val width = metrics.font.getStringBounds(spaces, metrics.fontRenderContext).width.roundToInt()
    return if (columns < 0) -width else width
}

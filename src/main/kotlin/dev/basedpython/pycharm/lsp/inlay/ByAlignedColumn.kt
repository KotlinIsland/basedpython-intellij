package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Dimension
import java.awt.Graphics2D
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One block of assignments sharing an `=` column, and the inlays that keep them sharing it.
 *
 * `by` says which lines belong together (`by/alignmentGroups`); this holds them in one editor and
 * decides, from moment to moment, how wide each one's inlay has to be. The arithmetic is
 * [ByAlignment.column] and is pure; what lives here is the part that cannot be — which hints are on
 * screen this instant, how wide the editor really draws each line's code, and telling the editor
 * when either has moved.
 *
 * **The column is a pixel, not a count.** `by` answers in positions and the display columns it
 * counted them at; a display column is a faithful description of the source and a poor description
 * of the screen, because the editor draws a wide glyph in whatever fallback font has it — 13 pixels
 * against the 7.8 of a column of JetBrains Mono. So the lead and the padding of every member are
 * measured here, out of the document's own characters, with the metrics the editor will draw them
 * with ([ByEditorTextWidth]), and the column is the furthest right of those measurements. Counting
 * instead left a block with a wide character in it six pixels out of true, hints or no hints.
 *
 * **Every line of a block carries exactly one inlay at its gap**, a hint where there is one and a
 * [ByAlignmentSpacer] where there is not, even on the lines that need no room at all today. That is
 * not an accident of the algorithm but the thing that keeps the block square when hints go away: an
 * inlay that is standing by still costs its line the pixel [column] leaves it, and the editor would
 * refuse it outright at any less ([ByInlayHintPresentation.HIDDEN_WIDTH]). A pixel on every line of
 * a block is a constant the block carries evenly and nobody can see; a pixel on all but one of them
 * is a step.
 *
 * Held alive by its own seats: each presentation refers back to the seat it sits in, and the inlays
 * hold the presentations, so a column lives exactly as long as the inlays it is arranging. That
 * matters because [ByHintPush] keeps its watchers weakly.
 *
 * **Threading.** A block is built on the daemon's background thread — [seat] and [Seat.take] — and
 * measured only on the EDT, and the two never overlap: the seats reach an editor through the inlay
 * sink, which is what publishes them. Nothing here is synchronised for that reason, and nothing here
 * may be read from anywhere but those two places.
 */
class ByAlignedColumn(override val editor: Editor) : ByHintPush.Watcher {

    private val seats = ArrayList<Seat>()

    /** The editor's own metrics, and the cache of them this block measures against. */
    private val metrics = ByEditorTextWidth(editor)

    /**
     * What [column] was last worked out for, or null if it has not been.
     *
     * The pair is everything the answer depends on: [ByEditorTextWidth.state] covers the font, its
     * rendering context and the tab size, and [generation] covers the block itself — which hints are
     * drawn and which seats there are.
     */
    private var columnFor: Pair<Any, Int>? = null

    private var column: Float = 0f

    /** Bumped whenever the block changes shape, which is what makes the cached [column] stale. */
    private var generation: Int = 0

    /**
     * One line of the block: the room it has, and the one inlay whose width this column decides.
     *
     * [lead] and [gap] are the line's own characters, taken from the document as it was when `by`
     * answered about it — not columns, and not offsets. An edit makes them stale, and nothing is
     * done about that on purpose: the daemon restarts the pass against the new text, which is the
     * same staleness the hint's own text already has. Taking a copy rather than the offsets is what
     * makes them merely stale instead of dangerous — there is no document to re-read at measuring
     * time and so nothing to read the wrong part of.
     */
    inner class Seat internal constructor(
        private val lead: CharSequence,
        private val gap: CharSequence,
    ) {
        /** Hints drawn where the gap starts. Empty on a line `by` had nothing to say about. */
        internal val hints = ArrayList<ByInlayHintPresentation>()

        /** The empty inlay standing in for a line with no hint. Set once, before anything is drawn. */
        internal var spacer: ByAlignmentSpacer? = null

        /** What [leadWidth] and [gapWidth] were measured against, or null if they have not been. */
        private var measuredFor: Any? = null

        private var leadWidth: Float = 0f

        private var gapWidth: Float = 0f

        /**
         * What this line is asking for as a member of the block, measured *now*.
         *
         * The code's two widths are re-measured only when the font moves under them; the hint's is
         * asked for every time, because it is nought while a push-to-hint hint is not drawn.
         */
        internal val member: ByAlignment.Member
            get() {
                measure()
                return ByAlignment.Member(
                    lead = leadWidth,
                    hint = hints.fold(0f) { total, hint -> total + hint.naturalWidth() },
                    gap = gapWidth,
                )
            }

        private fun measure() {
            val state = metrics.state()
            if (state == measuredFor) return
            leadWidth = metrics.widthOf(lead)
            gapWidth = metrics.widthOf(gap)
            measuredFor = state
        }

        /**
         * The pixels this line's inlays occupy between the end of its code and its `=`.
         *
         * Asked for whole — the column, less the code before the gap and the padding after it —
         * rather than as a natural width plus a correction, because two separately rounded
         * measurements do not add up to the one the editor makes.
         *
         * **Rounded up, to the column's own pixel.** The editor draws this line's `=` at
         * `lead + width + gap`, where the two ends are fractions it accumulated and this is the one
         * integer in the sum, and it then *truncates* that to a pixel. Rounding to nearest leaves the
         * result within half a pixel of the column, which is a whole pixel of spread across a block
         * once the truncation lands either side of a boundary — the two `=` of a two-line block came
         * out at 109 and 108 that way. Rounding up against a column that is [column] — an integer
         * strictly above every member's own — puts `lead + width + gap` in `[column, column + 1)` for
         * every member instead, so all of them truncate to the same pixel and the block is exact
         * rather than close.
         *
         * Read to lay the block out, and read again to tell one layout from another — see
         * [ByInlayHintPresentation.updateState]. Cheap enough to read twice per hint per pass because
         * everything behind it is cached until the font or the block moves; measuring afresh each
         * time made a block cost time in the square of its length, which is no longer only a handful
         * of lines: any run of same-length targets is a block now, and a wall of settings is a long
         * one.
         */
        internal fun requiredWidth(): Int {
            val column = column()
            measure()
            return ceil(column - (leadWidth + gapWidth)).toInt()
        }

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
            generation++
        }

        /** The empty inlay for a line the server had no hint for. */
        internal fun standIn(): ByAlignmentSpacer =
            ByAlignmentSpacer(editor, this).also { spacer = it }

        internal fun revalidate() {
            hints.forEach { it.revalidate() }
            spacer?.revalidate()
        }
    }

    /**
     * Adds a line to the block, in the order the lines are written.
     *
     * [lead] is the line from its first character up to the padding, and [gap] the padding itself —
     * the document's own text, so that measuring it asks the same question the editor answers when
     * it draws it.
     */
    fun seat(lead: CharSequence, gap: CharSequence): Seat =
        Seat(lead, gap).also {
            seats += it
            generation++
        }

    /**
     * The pixel every line of the block puts its `=` on at this moment.
     *
     * The *first pixel past* what [ByAlignment] asks for, rather than that measure itself, and the
     * whole exactness of the block rests on the difference. [ByAlignment] answers in the editor's own
     * fractions; an inlay may only be an integer wide. Moving the target up to the next whole pixel
     * leaves every member a strictly positive amount to cover, so rounding each one *up* lands them
     * all inside one pixel of each other and the editor truncates the lot to the same column — see
     * [Seat.requiredWidth]. It also leaves every inlay at least one pixel wide, which the editor
     * demands of an inline element anyway, so the standing-by pixel is no longer a separate rule
     * fighting the arithmetic.
     *
     * The price is that a block sits one pixel right of where its bare text would — a pixel the
     * widest line already paid for its standing-by inlay, and so not a pixel anyone can see move.
     *
     * Cached rather than recomputed per ask, and the cache key says exactly why it can be: the
     * answer moves only when the editor's metrics move ([ByEditorTextWidth.state] — zoom,
     * presentation mode, a scheme or tab-size change) or when the block does ([generation] — a seat
     * or hint added, a push key pressed). It is asked for once per inlay per layout and there is one
     * inlay per line, so a cache that holds across a block's own lines is the difference between a
     * pass that costs a block and one that costs its square.
     */
    private fun column(): Float {
        val key = metrics.state() to generation
        if (key != columnFor) {
            column = floor(ByAlignment.column(seats.map { it.member }, metrics.spaceWidth())) + 1f
            columnFor = key
        }
        return column
    }

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
        generation++
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

package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * A hint drawn in the editor's own font, at the editor's own size, shadowed rather than boxed.
 *
 * This is the whole point of rendering basedpython's hints instead of letting the platform do it.
 * The platform draws every LSP hint through `PresentationFactory.smallText`, which is a
 * *deliberately* different typeface — the UI label font at roughly ⅘ of the editor size, inside a
 * rounded grey pill — so that a hint cannot be mistaken for source. In a language whose hints are
 * almost all types (`: list[int]`, `-> None`) that reads as a foreign body wedged into the line:
 * the glyphs don't line up with the code around them, the pill breaks the column, and a type in the
 * hint looks nothing like the same type written out.
 *
 * VS Code makes the opposite choice, and it is the right one here: same family, same size, same
 * baseline as the code, dimmed and set on a faint tint of the editor background. A hint then reads
 * as the code you did not have to write, which is what it is.
 *
 * The tint is not decoration and the fade is not enough on its own: dimmed text in the editor font
 * is already how the IDE draws code that does not run, so a hint wearing nothing but a fade is
 * indistinguishable from an unused import. [ByInlayColors] carries that argument and both colours;
 * what is decided here is the shape — text box rather than line box, barely rounded, the glyphs
 * still on the code's own column.
 *
 * Both the family and the size are taken from the editor's *own* scheme rather than the global one,
 * so zoom (`Ctrl+Wheel`), presentation mode and distraction-free mode carry the hints with them.
 * The platform's small-text metrics cannot do that: which font they use is a single global
 * checkbox, `Settings | Editor | Inlay Hints | Use editor font`, off by default and applying to
 * every language at once.
 *
 * **Push-to-hint.** A hint in [ByHintMode.ON_PUSH] is built like any other and draws nothing until
 * its [pushKey] goes down — see [shown]. Keeping the inlay and changing what it draws is what makes
 * the peek instant: the alternative, collecting these hints only while the key is held, means a
 * daemon pass and a round trip to `by` on every press and every release. The platform's inlay pass
 * would not even run one, since it skips a file whose PSI has not changed.
 */
class ByInlayHintPresentation(
    override val editor: Editor,
    val text: String,
    private val padLeft: Boolean,
    private val padRight: Boolean,
    private val mode: ByHintMode = ByHintMode.ALWAYS,
    private val pushKey: ByPushKey = ByPushKey.CTRL_ALT,
) : BasePresentation(), ByHintPush.Watcher {

    /**
     * Whether the hint is drawn at all — always, for the modes that are not [ByHintMode.ON_PUSH],
     * and only while the key is held for the one that is.
     *
     * Read while painting on the EDT and written from [pushStateChanged] there too, but built on
     * the daemon's background thread, so it is volatile rather than plain.
     */
    @Volatile
    private var shown: Boolean = mode.isShown(ByHintPush.getInstance().isHeld(pushKey))

    init {
        // Only the push modes have anything to hear about, and watching is what installs the
        // IDE-wide modifier dispatcher, so the other two never bring it into being.
        if (mode == ByHintMode.ON_PUSH) ByHintPush.getInstance().watch(this)
    }

    /**
     * The seat this hint has in a block of lined-up assignments, or null — which is nearly always.
     *
     * Set once by the collector, after the hint is built and before its inlay exists, because which
     * block a hint belongs to is `by`'s answer and arrives alongside the hints rather than in them.
     *
     * Volatile for the same reason [shown] is: written on the daemon's background thread and read
     * while measuring on the EDT.
     */
    @Volatile
    internal var seat: ByAlignedColumn.Seat? = null

    /**
     * Exactly what the same characters would measure as source, and **not a pixel more** — except
     * where a hint is holding a column together, which is the one thing worth more than that rule.
     *
     * The rule first, because it is what the rendering rests on, and it is checkable: writing an
     * annotation out by hand and letting the hint stand for it have to leave the rest of the line in
     * the same place.
     *
     * ```
     * a = A(1)          ->  a: A[int] = A[int](t=1)
     * a: A[int] = A(1)  ->  a: A[int] = A[int](t=1)
     * ```
     *
     * Those two must land character for character, and they only do if a hint costs its text and
     * nothing else. Any padding of its own — even the two pixels that would keep the glyphs off the
     * tint's corners — is per hint, so a line with three of them drifts against a line with two and
     * nothing in the file lines up any more. So the tint takes its room from the text rather than
     * the other way round: it is drawn to this width, and gets its breathing space vertically, where
     * there is room going spare.
     *
     * **The exception.** In a block the author lined up by hand, that same rule is what pulls the
     * block apart, and for a reason the rule cannot see: writing the annotation out *would* have
     * broken the alignment too, so costing what the source costs is faithful to a line and unfaithful
     * to the paragraph it is in. A hint with a [seat] therefore reports what its *line* needs — often
     * less than its text, drawing its glyphs over padding the author already left, and sometimes more
     * on the line that has to give the rest of the block room. What it *draws* is unchanged either
     * way; nothing is clipped to this width (`EditorPainter` hands the renderer the shared
     * `Graphics2D` with a translate and no clip, and queues the rest of the line's text after it), so
     * the glyphs simply sit on blanks.
     *
     * A seat's width is asked for whole rather than as this width plus a correction, and that is not
     * tidiness: two separately rounded measurements do not add up to the one the editor makes — see
     * [ByAlignment.column].
     *
     * Never below [HIDDEN_WIDTH]: the editor refuses a zero-width inline element, and a hint that has
     * given back all of its own room is exactly the case that would ask for one.
     */
    override val width: Int
        get() {
            val natural = naturalWidth()
            return (seat?.widthFor(this, natural) ?: natural).coerceAtLeast(HIDDEN_WIDTH)
        }

    /** What this hint costs as source, which is what it reports when it is not holding a column. */
    internal fun naturalWidth(): Int {
        if (!shown) return 0
        val metrics = metrics(font())
        return leftPadding(metrics) + textWidth(metrics) + rightPadding(metrics)
    }

    /**
     * The room this hint takes on its line right now, in columns, as [ByAlignment] counts it.
     *
     * Nought while it is not drawn, which is what makes releasing the push key put a block back
     * exactly as the author wrote it rather than leaving it padded for hints that are not there.
     */
    internal val shownColumns: Int
        get() = if (!shown) 0 else text.length + (if (padLeft) 1 else 0) + (if (padRight) 1 else 0)

    /** Whether this hint's visibility can change under a keypress, and so needs watching for. */
    internal val watchesPush: Boolean get() = mode == ByHintMode.ON_PUSH

    /**
     * The text's true advance, rounded to the nearest pixel — **not** `stringWidth`, and not the
     * width the same characters happen to occupy somewhere in a line.
     *
     * There is no such thing as "the width of this text as source" to match. The editor lays a line
     * out by accumulating *fractional* advances and flooring each position, so on a font whose
     * advance is 7.8px the columns fall at 0, 7, 15, 23, 31, 39, 46, 54, 62 — and the same eight
     * characters span 62px starting on one column and 63px starting on the next. An inlay, meanwhile,
     * gets one integer for all positions.
     *
     * So the question is not which integer is *right* but which is least wrong, and that is the
     * nearest one. Rounding up (which reproduces a measurement taken at one particular column, and
     * so looks correct until it is checked anywhere else) pads every hint by up to a pixel in the
     * same direction, and those add up: three hints on a line against two on the next is how it
     * showed. Rounding to nearest keeps each hint within half a pixel and lets the errors cancel
     * rather than accumulate.
     *
     * `FontMetrics.stringWidth` is not an option either — it rounds each character before adding,
     * which loses the fraction this depends on.
     */
    private fun textWidth(metrics: FontMetrics): Int =
        metrics.font.getStringBounds(text, metrics.fontRenderContext).width.roundToInt()

    /**
     * A whole line box, so the hint occupies the line the way a character does.
     *
     * Not the font's height: with line spacing above 1.0 an inlay shorter than the line would be
     * drawn against a gap the editor has already painted, and the text would ride high in it.
     */
    override val height: Int
        get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        if (!shown) return
        val hint = ByInlayColors.attributes(editor.colorsScheme)
        val font = font(hint.fontType)
        val metrics = metrics(font)

        val savedFont = g.font
        val savedColor = g.color
        try {
            hint.backgroundColor?.let { paintTint(g, metrics, it) }
            EditorUIUtil.setupAntialiasing(g)
            g.font = font
            g.color = hint.foregroundColor
            g.drawString(text, leftPadding(metrics), baseline(metrics))
        } finally {
            g.font = savedFont
            g.color = savedColor
        }
    }

    /**
     * The tint behind the text — what keeps a hint from reading as dead code (see [ByInlayColors]).
     *
     * Exactly the text's own advance wide, because [width] is not the tint's to spend (see there),
     * and taller than the text by [VERTICAL_INSET] — vertical room is free, since the line box is
     * already taller than the glyphs. Not as tall as the *line*, though: a full-height tint is a
     * solid band, and with line spacing above 1.0 it would close the gap the editor leaves between
     * lines.
     *
     * Barely rounded. At this size the arc is really only taking the hard corners off; a radius
     * large enough to read as a shape is a capsule, and a capsule is what this rendering exists to
     * get away from. With no horizontal inset the corners now sit within the glyphs' own side
     * bearings, which is why the radius is smaller than it would otherwise want to be.
     */
    private fun paintTint(g: Graphics2D, metrics: FontMetrics, color: Color) {
        val baseline = baseline(metrics)
        val top = (baseline - metrics.ascent - VERTICAL_INSET).coerceAtLeast(0)
        val bottom = (baseline + metrics.descent + VERTICAL_INSET).coerceAtMost(height)
        val config = GraphicsUtil.setupAAPainting(g)
        try {
            g.color = color
            g.fillRoundRect(
                leftPadding(metrics),
                top,
                textWidth(metrics),
                bottom - top,
                ARC,
                ARC,
            )
        } finally {
            config.restore()
        }
    }

    /**
     * The same baseline the editor puts its own text on: the font box centred in the line box, text
     * sitting on its ascent. Since the font *is* the editor's, a hint and the code beside it land on
     * one line however the line is spaced.
     */
    private fun baseline(metrics: FontMetrics): Int =
        (editor.lineHeight - (metrics.ascent + metrics.descent)) / 2 + metrics.ascent

    /**
     * The gap the server asked for, one space of the editor font wide.
     *
     * `paddingLeft` / `paddingRight` are the LSP way of saying where a hint needs air. `by` does not
     * use them — it writes the space it wants into the label instead — so in practice this is zero
     * today and the honouring is for the spec's sake. A space of the very font the hint is drawn in
     * is what would make the gap match the code's own spacing.
     */
    private fun leftPadding(metrics: FontMetrics): Int = if (padLeft) metrics.charWidth(' ') else 0

    private fun rightPadding(metrics: FontMetrics): Int = if (padRight) metrics.charWidth(' ') else 0

    private fun font(fontType: Int = Font.PLAIN): Font {
        val plain = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return if (fontType == Font.PLAIN) plain else plain.deriveFont(fontType)
    }

    private fun metrics(font: Font): FontMetrics = editor.contentComponent.getFontMetrics(font)

    /**
     * The push key went down or came up: redraw, and resize, since a hidden hint takes no room.
     *
     * Called on the EDT by [ByHintPush]. The size event is what reaches the inlay — the platform
     * listens on every presentation it renders and turns one into `Inlay.update()`, which is the
     * only way a width that has already been measured gets measured again.
     */
    override fun pushStateChanged() {
        val before = Dimension(width, height)
        if (!refreshShown()) return
        val after = Dimension(width, height)
        fireSizeChanged(before, after)
        fireContentChanged(Rectangle(0, 0, after.width, after.height))
    }

    /**
     * Re-reads whether this hint is drawn, and says whether that changed.
     *
     * Split from [revalidate] because a hint in a [ByAlignedColumn] cannot be measured until every
     * hint in its block has been asked this — a seat's width depends on what its *siblings* are
     * showing, so settling one visibility at a time and measuring as you go lays the block out
     * against a state that never existed. The column does all the asking first, then all the
     * measuring. See [ByAlignedColumn.pushStateChanged].
     */
    internal fun refreshShown(): Boolean {
        val next = mode.isShown(ByHintPush.getInstance().isHeld(pushKey))
        if (next == shown) return false
        shown = next
        return true
    }

    /**
     * Tells the platform to measure this hint again.
     *
     * The dimensions are a signal rather than data: the listener the inlay pass installs
     * (`InlayHintsPass` → `InlayContentListener`) reads neither and calls `Inlay.update()`, which is
     * the only thing that makes a width that has already been measured be measured again. Passing
     * the current size twice is therefore honest about what this call means — *something moved* —
     * rather than pretending to know what it was before.
     */
    internal fun revalidate() {
        val size = Dimension(width, height)
        fireSizeChanged(size, size)
        fireContentChanged(Rectangle(0, 0, size.width, size.height))
    }

    /**
     * Lets the daemon reuse an existing inlay whose text has not changed, instead of dropping it and
     * adding a new one — which is what stops hints flickering on every keystroke.
     *
     * [mode] counts as part of that text: a hint that has just been moved between "always" and "on
     * push" is the same string drawn under a different rule, and reusing the old presentation would
     * keep the old rule until the next edit.
     *
     * So does the share of the column, and that one is not optional. `RecursivelyUpdatingRootPresentation`
     * swaps this presentation in for the old one whichever way this answers, but it only fires the
     * size change — and so only makes the platform ask for the width again — when the answer is
     * true. A hint whose own text is unchanged but whose block has just grown around it would
     * otherwise keep the width it was measured at, and stop following the block.
     */
    override fun updateState(previousPresentation: InlayPresentation): Boolean {
        val previous = previousPresentation as? ByInlayHintPresentation ?: return true
        return previous.text != text ||
            previous.padLeft != padLeft ||
            previous.padRight != padRight ||
            previous.mode != mode ||
            previous.pushKey != pushKey ||
            previous.seat?.deltaColumns != seat?.deltaColumns
    }

    override fun toString(): String = text

    internal companion object {
        /**
         * How far the tint reaches above and below the text box.
         *
         * Vertical only. There is no horizontal counterpart on purpose: horizontal room is the
         * code's column, and a hint may not spend any of it — see [width].
         */
        private val VERTICAL_INSET: Int = JBUIScale.scale(1)

        /** Just enough to take the corners off. Anything more reads as a capsule. */
        private val ARC: Int = JBUIScale.scale(2)

        /**
         * What a hidden hint measures.
         *
         * One pixel rather than none: the editor rejects a zero-width inline element outright
         * ("Positive width should be defined for an inline element", `InlineInlayImpl`), so this is
         * the narrowest an inlay that is standing by can be. It draws nothing, and a pixel per
         * hidden hint is a constant the line carries whether or not the key is down — it does not
         * shift while you type, and it is gone entirely for anyone who leaves their hints on
         * [ByHintMode.ALWAYS].
         */
        const val HIDDEN_WIDTH = 1
    }
}

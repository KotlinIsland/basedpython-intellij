package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.paint.LinePainter2D
import com.intellij.ui.scale.JBUIScale
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Draws a multiline string's trim margin: a vertical line down the column it is trimmed to.
 *
 * A [CustomHighlighterRenderer] because there is nothing to attribute. Text attributes colour
 * characters, and the margin is a rule between two of them — on lines that may have no character
 * at that column at all, which is precisely the case worth showing (a blank line inside the
 * literal, or closing quotes sitting further left than the text above them).
 *
 * One renderer per marked literal, carrying [indent]: how much `by` said is stripped from it, and
 * the only thing carried in from the pass that added the highlighter. **Where the rule goes is
 * measured here, at paint time, from the highlighter's own range.** Offsets computed by a daemon
 * pass are a snapshot, and the editor keeps painting between one pass and the next: every
 * keystroke would draw the rule where the text used to be, and it would jump back a few hundred
 * milliseconds later when the daemon caught up. The highlighter's range is moved by the document
 * itself as the edit happens, so measuring from it is measuring from what is on screen. An edit
 * that changes how much is stripped is picked up by the next pass, which replaces the renderer.
 *
 * The line runs from the first line of content to the last. Not across the opening line, and not
 * down beside the closing quotes, which sit on a line of their own below the content.
 *
 * Placed by asking the editor where [StringMargin.anchorOffset] is rather than by multiplying a
 * column by a character width. Only the editor knows what the columns before it are worth — tabs,
 * a proportional font, an inlay from `by` sitting in the line — and the anchor is chosen on a line
 * whose leading characters are exactly the whitespace being stripped.
 */
class ByStringMarginRenderer(val indent: Int) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val margin = StringMargins.marginOf(
            editor.document.immutableCharSequence,
            highlighter.startOffset,
            highlighter.endOffset,
            indent,
        ) ?: return

        // Folded away — by `by`'s folding ranges or by a collapsed region around the statement.
        // The offsets would all map to the placeholder's single line and the margin would be a
        // tick mark in the middle of unrelated text.
        val folding = editor.foldingModel
        if (folding.isOffsetCollapsed(margin.firstLineStart) ||
            folding.isOffsetCollapsed(margin.lastLineStart)
        ) {
            return
        }

        val x = editor.offsetToXY(margin.anchorOffset).x - GAP
        val top = editor.offsetToXY(margin.firstLineStart).y
        val bottom = editor.offsetToXY(margin.lastLineStart).y + editor.lineHeight
        if (bottom <= top) return

        val clip = g.clipBounds
        if (clip != null && (bottom < clip.y || top > clip.y + clip.height)) return

        val g2d = g as Graphics2D
        val saved = g2d.color
        try {
            g2d.color = ByStringMarginColors.color(editor.colorsScheme)
            // LinePainter2D rather than drawLine: on a HiDPI display a one-pixel rule drawn in
            // user space lands between device pixels and comes out as a two-pixel smear.
            LinePainter2D.paint(g2d, x.toDouble(), top.toDouble(), x.toDouble(), (bottom - 1).toDouble())
        } finally {
            g2d.color = saved
        }
    }

    /**
     * How far left of the first kept character the rule sits.
     *
     * The anchor is the offset of that character and the editor answers with the left edge of its
     * cell, which is where the glyph begins — a rule drawn there touches the text it measures.
     * Two points is not a taste: it is where the editor draws its **own** indent guide for the
     * same column, measured off a rendered editor, and landing anywhere else would put a second
     * vertical line a couple of pixels from the first. See [ByStringMarginColors], which settles
     * the other half of sharing that column.
     */
    private val GAP: Int = JBUIScale.scale(2)
}

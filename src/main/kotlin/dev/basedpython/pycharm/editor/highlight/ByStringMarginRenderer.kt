package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.paint.LinePainter2D
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Draws a multiline string's trim margin: a vertical line down the column it is trimmed to.
 *
 * A [CustomHighlighterRenderer] because there is nothing to attribute. Text attributes colour
 * characters, and the margin is a rule between two of them — on lines that may have no character
 * at that column at all, which is precisely the case worth showing (a blank line inside the
 * literal, or closing quotes sitting further right than the text above them).
 *
 * **The margin is measured here, at paint time, from the highlighter's own range** — not carried
 * in from the pass that added the highlighter. Offsets computed by a daemon pass are a snapshot,
 * and the editor keeps painting between one pass and the next: every keystroke would draw the
 * rule where the text used to be, and it would jump back a few hundred milliseconds later when
 * the daemon caught up. The highlighter's range, by contrast, is moved by the document itself as
 * the edit happens, so measuring from it is measuring from what is on screen. It also costs
 * nothing worth counting — one scan of one literal, only for the ones in view.
 *
 * The line runs from the first line of content to the last line of text. Not across the opening
 * line, whose text starts after the quotes with nothing taken off it; not down beside closing
 * quotes on a line of their own, which are the margin rather than something to mark against it.
 *
 * Placed by asking the editor where [StringMargin.anchorOffset] is rather than by multiplying a
 * column by a character width. Only the editor knows what the columns before it are worth — tabs,
 * a proportional font, an inlay from `by` sitting in the line — and the anchor is chosen on a line
 * whose leading characters are exactly the whitespace being stripped.
 */
object ByStringMarginRenderer : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val margin = StringMargins.marginOf(
            editor.document.immutableCharSequence,
            highlighter.startOffset,
            highlighter.endOffset,
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

        val x = editor.offsetToXY(margin.anchorOffset).x
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
}

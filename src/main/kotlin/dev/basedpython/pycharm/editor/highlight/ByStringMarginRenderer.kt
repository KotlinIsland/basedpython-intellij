package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.paint.LinePainter2D
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Draws one [StringMargin]: a vertical line down the column a multiline string is trimmed to.
 *
 * A [CustomHighlighterRenderer] because there is nothing to attribute. Text attributes colour
 * characters, and the margin is a rule between two of them — on lines that may have no character
 * at that column at all, which is precisely the case worth showing (a blank line inside the
 * literal, or the closing quotes sitting further right than the text above them).
 *
 * The line runs from the first line of content to the line carrying the closing quotes, and no
 * further: the opening line's text starts after the quotes and nothing is taken off it, so a
 * margin drawn across it would be claiming a trim that does not happen.
 *
 * Placed by asking the editor where [StringMargin.anchorOffset] is rather than by multiplying a
 * column by a character width. Only the editor knows what the columns before it are worth — tabs,
 * a proportional font, an inlay from `by` sitting in the line — and the anchor is chosen on a line
 * whose leading characters are exactly the whitespace being stripped.
 */
class ByStringMarginRenderer(val margin: StringMargin) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return

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

    /**
     * Equality by the margin drawn, so a daemon pass that recomputes the same margins can leave
     * the editor's highlighters alone instead of replacing them — see [ByStringMarginPassFactory].
     */
    override fun equals(other: Any?): Boolean =
        other is ByStringMarginRenderer && other.margin == margin

    override fun hashCode(): Int = margin.hashCode()
}

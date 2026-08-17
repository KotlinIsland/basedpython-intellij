package dev.basedpython.pycharm.debug.dfa

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.ColorUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Draws `= true` / `= false` in the margin past the line the condition is on.
 *
 * Painted rather than inlaid, and that is the point: an inlay *inserts* width, so every hint would
 * shove the rest of the line sideways. Somebody reading code while stopped in a debugger is
 * following its shape, and a feature that reflows the text it is annotating is working against
 * that. This is drawn in the margin the line already has.
 *
 * ## why the end of the line and not the end of the condition
 *
 * Drawing it beside the condition was the first thing tried, and in a live IDE it read
 * `if a == 2:= true` — a `:=` a Python reader has every reason to misread. An `if` header's
 * condition ends exactly one character before the colon, so a gap measured from the condition's end
 * is spent on the colon itself and none is left. Worse, a condition in the middle of a line — the
 * test of a ternary — would have had the label painted straight over the code after it.
 *
 * The end of the line is where an inline value belongs anyway; it is where IntelliJ's own debugger
 * puts one, which is the thing this was asked to look like.
 *
 * The colour is derived from the scheme rather than fixed, so it stays legible under a theme
 * nobody here has seen, and it is faded — the code is what the reader is reading, and this is a
 * note on it.
 *
 * @param gap how many characters of margin an earlier verdict on the same line has already taken,
 *   so two labels stack rather than land on top of each other
 */
internal class ByDataFlowVerdictRenderer(
    private val label: String,
    private val gap: Int = 0,
) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val document = editor.document
        val end = highlighter.endOffset
        if (end > document.textLength) return

        val point = editor.offsetToXY(document.getLineEndOffset(document.getLineNumber(end)))
        val scheme = editor.colorsScheme
        val foreground = scheme.defaultForeground
        val background = scheme.defaultBackground

        val graphics = g as Graphics2D
        val hints = graphics.renderingHints
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        graphics.font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        graphics.color = ColorUtil.mix(background, foreground, FADE)

        // Measured in the editor's own font, which is what makes [gap] a count of characters
        // rather than a guess at pixels
        val space = graphics.fontMetrics.charWidth(' ')
        graphics.drawString(label, point.x + (gap + 1) * space, point.y + editor.ascent)

        graphics.setRenderingHints(hints)
    }

    private companion object {
        /**
         * How far the verdict is faded from ordinary text.
         *
         * Further than an inlay hint is, because this one has no tint behind it to say it is not
         * source — the fade is doing that job alone.
         */
        private const val FADE = 0.55
    }
}

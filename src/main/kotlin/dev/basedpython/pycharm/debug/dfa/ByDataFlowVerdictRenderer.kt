package dev.basedpython.pycharm.debug.dfa

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.ui.ColorUtil
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

/**
 * Draws `= true` / `= false` just past the condition it is about.
 *
 * Painted rather than inlaid, and that is the point: an inlay *inserts* width, so every hint would
 * shove the rest of the line sideways. Somebody reading code while stopped in a debugger is
 * following its shape, and a feature that reflows the text it is annotating is working against
 * that. This is drawn in the margin the line already has.
 *
 * The colour is derived from the scheme rather than fixed, so it stays legible under a theme
 * nobody here has seen, and it is faded — the code is what the reader is reading, and this is a
 * note on it.
 */
internal class ByDataFlowVerdictRenderer(private val label: String) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
        if (!highlighter.isValid) return
        val document = editor.document
        val end = highlighter.endOffset
        if (end > document.textLength) return

        val point = editor.offsetToXY(end)
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

        // One space of the editor's own font, so the verdict never touches the code it is about
        val gap = graphics.fontMetrics.charWidth(' ')
        graphics.drawString(label, point.x + gap, point.y + editor.ascent)

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

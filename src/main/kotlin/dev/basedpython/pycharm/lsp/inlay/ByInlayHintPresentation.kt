package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D

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
 * baseline as the code, just dimmed. A hint then reads as the code you did not have to write, which
 * is what it is. The only two things that separate it from real source are its colour (see
 * [ByInlayColors]) and the space around it.
 *
 * Both the family and the size are taken from the editor's *own* scheme rather than the global one,
 * so zoom (`Ctrl+Wheel`), presentation mode and distraction-free mode carry the hints with them.
 * The platform's small-text metrics cannot do that: which font they use is a single global
 * checkbox, `Settings | Editor | Inlay Hints | Use editor font`, off by default and applying to
 * every language at once.
 */
class ByInlayHintPresentation(
    private val editor: Editor,
    val text: String,
    private val padLeft: Boolean,
    private val padRight: Boolean,
) : BasePresentation() {

    override val width: Int
        get() {
            val metrics = metrics(font())
            return metrics.stringWidth(text) + leftPadding(metrics) + rightPadding(metrics)
        }

    /**
     * A whole line box, so the hint occupies the line the way a character does.
     *
     * Not the font's height: with line spacing above 1.0 an inlay shorter than the line would be
     * drawn against a gap the editor has already painted, and the text would ride high in it.
     */
    override val height: Int
        get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val hint = ByInlayColors.attributes(editor.colorsScheme)
        val font = font(hint.fontType)
        val metrics = metrics(font)

        val savedFont = g.font
        val savedColor = g.color
        try {
            // A background only when a scheme asks for one — the default derives no background at
            // all, which is what leaves the hint sitting flat in the line instead of in a pill.
            hint.backgroundColor?.let {
                g.color = it
                g.fillRect(0, 0, width, height)
            }
            EditorUIUtil.setupAntialiasing(g)
            g.font = font
            g.color = hint.foregroundColor ?: ByInlayColors.derivedForeground(editor.colorsScheme)
            g.drawString(text, leftPadding(metrics), baseline(metrics))
        } finally {
            g.font = savedFont
            g.color = savedColor
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
     * Lets the daemon reuse an existing inlay whose text has not changed, instead of dropping it and
     * adding a new one — which is what stops hints flickering on every keystroke.
     */
    override fun updateState(previousPresentation: InlayPresentation): Boolean {
        val previous = previousPresentation as? ByInlayHintPresentation ?: return true
        return previous.text != text || previous.padLeft != padLeft || previous.padRight != padRight
    }

    override fun toString(): String = text
}

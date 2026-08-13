package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle

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

    override val width: Int
        get() {
            if (!shown) return HIDDEN_WIDTH
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
        if (!shown) return
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
     * The push key went down or came up: redraw, and resize, since a hidden hint takes no room.
     *
     * Called on the EDT by [ByHintPush]. The size event is what reaches the inlay — the platform
     * listens on every presentation it renders and turns one into `Inlay.update()`, which is the
     * only way a width that has already been measured gets measured again.
     */
    override fun pushStateChanged() {
        val next = mode.isShown(ByHintPush.getInstance().isHeld(pushKey))
        if (next == shown) return
        val before = Dimension(width, height)
        shown = next
        val after = Dimension(width, height)
        fireSizeChanged(before, after)
        fireContentChanged(Rectangle(0, 0, after.width, after.height))
    }

    /**
     * Lets the daemon reuse an existing inlay whose text has not changed, instead of dropping it and
     * adding a new one — which is what stops hints flickering on every keystroke.
     *
     * [mode] counts as part of that text: a hint that has just been moved between "always" and "on
     * push" is the same string drawn under a different rule, and reusing the old presentation would
     * keep the old rule until the next edit.
     */
    override fun updateState(previousPresentation: InlayPresentation): Boolean {
        val previous = previousPresentation as? ByInlayHintPresentation ?: return true
        return previous.text != text ||
            previous.padLeft != padLeft ||
            previous.padRight != padRight ||
            previous.mode != mode ||
            previous.pushKey != pushKey
    }

    override fun toString(): String = text

    private companion object {
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

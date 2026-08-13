package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * The colours of a basedpython inlay hint: faded text on a faint tint of the editor background.
 *
 * **Both halves matter, and the tint is not decoration.** Dimmed text in the editor font is already
 * spoken for: it is how the IDE draws code that does not run — an unused import, an unreachable
 * branch, a symbol nobody references. A hint drawn in nothing but a fade is indistinguishable from
 * dead code at a glance, which is worse than the platform's pill: the pill is ugly, but it never
 * lies about what it is. The tint is what says "inferred" instead of "dead", and it is the smallest
 * mark that does — no border, no capsule, no inset that would knock the glyphs off the code's
 * column. VS Code makes the same pairing (`editorInlayHint.foreground` over
 * `editorInlayHint.background`) for the same reason.
 *
 * Deliberately **not** given a fallback key. The platform's inlay keys
 * (`DefaultLanguageHighlighterColors.INLAY_DEFAULT` and friends) carry a background too, but theirs
 * is the opaque grey capsule this rendering exists to get rid of — inheriting from them would put it
 * straight back in every scheme that has not been taught otherwise.
 *
 * So the key stands alone, and a scheme that says nothing about it (which is every scheme but the
 * two bundled ones) has both colours derived from the scheme itself. Deriving rather than hard-coding
 * is what makes a hint read the same way under a theme nobody here has seen.
 */
object ByInlayColors {

    @JvmField
    val HINT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BASEDPYTHON_INLAY_HINT")

    /**
     * How far the derived text colour is faded from ordinary text towards the background.
     *
     * Short of the halfway point: with a tint behind it the text no longer has to carry "this is not
     * source" on its own, and a hint that is legible is worth more than one that is maximally quiet.
     */
    private const val FADE = 0.38

    /**
     * How much of the text colour is mixed into the background to make the tint.
     *
     * Tuned by looking at it in a running IDE, light and dark: enough that the hint is visibly *on*
     * something, little enough that a line of code with four hints in it does not turn into a row of
     * boxes. A blend of the foreground rather than a fixed grey, so it stays neutral against a
     * coloured editor background.
     */
    private const val TINT = 0.20

    /**
     * The attributes to draw a hint with under [scheme].
     *
     * Resolved per half rather than all-or-nothing: a scheme is entitled to restyle the text and say
     * nothing about the tint (which is exactly what a theme author will do first), and that should
     * still get a tint — derived from the colour they chose, not from the one they replaced.
     */
    fun attributes(scheme: EditorColorsScheme): TextAttributes {
        val defined = scheme.getAttributes(HINT)
        val foreground = defined?.foregroundColor
            ?: derivedForeground(scheme.defaultForeground, scheme.defaultBackground)
        val background = defined?.backgroundColor
            ?: derivedBackground(foreground, scheme.defaultBackground)
        return TextAttributes().apply {
            foregroundColor = foreground
            backgroundColor = background
            fontType = defined?.fontType ?: java.awt.Font.PLAIN
        }
    }

    /** Ordinary editor text, faded [FADE] of the way into the editor background. */
    fun derivedForeground(text: Color, background: Color): Color =
        ColorUtil.mix(text, background, FADE)

    /** The editor background with [TINT] of the hint's own text colour mixed into it. */
    fun derivedBackground(hintForeground: Color, background: Color): Color =
        ColorUtil.mix(background, hintForeground, TINT)
}

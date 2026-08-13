package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * The colour of a basedpython inlay hint.
 *
 * Deliberately **not** given a fallback key. The platform's inlay keys
 * (`DefaultLanguageHighlighterColors.INLAY_DEFAULT` and friends) carry a background as well as a
 * foreground, and that background is the grey pill this rendering exists to get rid of — inheriting
 * from them would put it straight back in every scheme that has not been taught otherwise.
 *
 * So the key stands alone, and when a scheme says nothing about it (which is every scheme but the
 * two bundled ones) the colour is derived from that scheme instead: ordinary text faded halfway
 * into the background. That is what "shadowed" means here, and deriving it per scheme is what makes
 * a hint read the same way under a theme nobody here has seen.
 */
object ByInlayColors {

    @JvmField
    val HINT: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BASEDPYTHON_INLAY_HINT")

    /** How far the derived colour is faded from the text colour towards the background. */
    private const val FADE = 0.45

    /**
     * The attributes to draw a hint with under [scheme].
     *
     * A scheme that defines [HINT] wins outright, foreground, background, font style and all. One
     * that does not gets the derived fade and no background.
     */
    fun attributes(scheme: EditorColorsScheme): TextAttributes {
        val defined = scheme.getAttributes(HINT)
        if (defined != null && defined.foregroundColor != null) return defined
        return TextAttributes().apply { foregroundColor = derivedForeground(scheme) }
    }

    /** Ordinary editor text, faded [FADE] of the way into the editor background. */
    fun derivedForeground(scheme: EditorColorsScheme): Color =
        ColorUtil.mix(scheme.defaultForeground, scheme.defaultBackground, FADE)
}

package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import dev.basedpython.pycharm.lang.BasedPythonColors
import java.awt.Color

/**
 * The colour of a multiline string's trim margin: the literal's own colour, faded into the page.
 *
 * A [ColorKey] rather than a [com.intellij.openapi.editor.colors.TextAttributesKey], because the
 * margin is a line drawn beside the text and not a way of drawing text — the same distinction the
 * platform makes for indent guides and the right margin.
 *
 * Undefined in a scheme, it is derived rather than fixed, for the reason
 * [dev.basedpython.pycharm.lsp.inlay.ByInlayColors] derives its two: a hard-coded grey is wrong in
 * every theme nobody here has looked at. The derivation is from the *string* colour, not from the
 * editor's foreground or from the indent-guide colour, because that is what the margin is about —
 * it belongs to the literal it cuts through, and reads as part of it under a theme that colours
 * strings green as under one that colours them orange.
 */
object ByStringMarginColors {

    @JvmField
    val MARGIN: ColorKey = ColorKey.createColorKey("BASEDPYTHON_STRING_MARGIN")

    /**
     * How much of the string colour is mixed into the editor background.
     *
     * Enough to follow across a literal, little enough that it never competes with the text it
     * runs beside: this is a boundary marker, and a reader should have to look at it to see it.
     */
    private const val WEIGHT = 0.35

    /** The colour to draw the margin in under [scheme]. */
    fun color(scheme: EditorColorsScheme): Color =
        scheme.getColor(MARGIN) ?: derived(scheme)

    /** The scheme's string colour, [WEIGHT] of the way out of its background. */
    fun derived(scheme: EditorColorsScheme): Color {
        val string = scheme.getAttributes(BasedPythonColors.STRING)?.foregroundColor
            ?: scheme.defaultForeground
        return ColorUtil.mix(scheme.defaultBackground, string, WEIGHT)
    }
}

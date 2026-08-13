package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * The colour of a multiline string's trim margin: the editor's own indent-guide colour.
 *
 * A [ColorKey] rather than a [com.intellij.openapi.editor.colors.TextAttributesKey], because the
 * margin is a line drawn beside the text and not a way of drawing text — the same distinction the
 * platform makes for indent guides and the right margin.
 *
 * **Why the guide's colour and not the string's.** The two lines are the same line. The editor
 * already draws an indent guide down a multiline string, at the indentation its lines share —
 * which is the trim column, by the same arithmetic — and rendering the two in different colours
 * does not produce two lines a reader can tell apart. It produces one line that changes colour
 * partway down, because the platform draws a guide only on a block's *interior* lines: the first
 * and last content lines of the literal are ours alone, the ones between are the guide painted
 * over ours. Measured in a rendered editor, the guide and the margin land on the same pixel
 * column, and the guide wins where it is drawn.
 *
 * So the margin takes the guide's colour and continues it across the lines the guide skips. What
 * the reader gets is one unbroken rule spanning the whole literal, which is what IDEA shows for a
 * Java text block. The margin still earns its keep: it reaches the first and last line, it is
 * measured by basedpython's trim rule rather than by block indentation, and it is drawn where a
 * blank line or an outdented closing quote would leave the guide with nothing to say.
 *
 * A scheme that wants the margin to stand apart can still say so through [MARGIN] — with the
 * caveat above, that the interior lines are the platform's to colour, not ours.
 */
object ByStringMarginColors {

    @JvmField
    val MARGIN: ColorKey = ColorKey.createColorKey("BASEDPYTHON_STRING_MARGIN")

    /**
     * How far the fallback is mixed out of the editor background, when a scheme defines neither
     * this key nor an indent-guide colour. Faint: it stands in for a guide, and a guide is
     * something you look at rather than something that announces itself.
     */
    private const val WEIGHT = 0.20

    /** The colour to draw the margin in under [scheme]. */
    fun color(scheme: EditorColorsScheme): Color =
        scheme.getColor(MARGIN) ?: derived(scheme)

    /** The scheme's indent-guide colour, or a faint mix of its own text and background. */
    fun derived(scheme: EditorColorsScheme): Color =
        scheme.getColor(EditorColors.INDENT_GUIDE_COLOR)
            ?: ColorUtil.mix(scheme.defaultBackground, scheme.defaultForeground, WEIGHT)
}

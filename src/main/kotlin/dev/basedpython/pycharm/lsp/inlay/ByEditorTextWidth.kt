package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.FontInfo
import java.awt.Font
import java.awt.font.FontRenderContext

/**
 * How wide a run of a line's own source is, measured the way the editor draws it.
 *
 * **Not a column count.** A column is a fiction the editor only keeps for characters that happen to
 * be one advance wide. `名前` is two characters and two columns by Unicode's reckoning, and the
 * editor draws it in whatever fallback font has the glyphs — 13 pixels a glyph against 7.8 for a
 * column of JetBrains Mono. Six pixels a line is the difference between a block of `=` that lines up
 * and one that does not, and no arithmetic over columns can close it, because the number it is
 * missing is a property of the font rather than of the text. So the text is measured instead.
 *
 * Measured the way `EditorView` measures it, which is what makes the answer the *editor's* rather
 * than merely plausible:
 *
 * - each character's advance comes from [EditorUtil.fontForChar], the same
 *   `ComplementaryFontsRegistry` lookup against the same scheme's font preferences that the editor's
 *   own line layout does, so a character the editor font cannot display is measured in the very
 *   fallback font the editor will draw it in;
 * - the advance is [FontInfo.charWidth2D], a float, and the advances are accumulated as floats —
 *   `SimpleTextFragment` builds its character positions by exactly this sum. Rounding per character
 *   (which is what `FontMetrics.stringWidth` and [EditorUtil.textWidth] do) costs up to half a pixel
 *   each and drifts a whole column over a long line;
 * - a tab reaches the next tab stop in *pixels* ([EditorUtil.nextTabStop]), from the start of the
 *   line, as `TabFragment` does — not the next multiple of the tab size in characters.
 *
 * What is deliberately not modelled is the font *style*: everything is measured `PLAIN`, while the
 * editor draws a line in whatever styles its highlighting asks for. That is sound for the editor
 * font and only for it — the editor lays every line out on one advance grid and so requires a
 * monospaced family, in which bold and italic carry the same advances as plain. Reading the
 * highlighter would cost a lexer pass per measurement and buy nothing.
 *
 * Folded text is likewise not modelled: this measures the characters of the document, and a
 * collapsed region before the `=` is drawn as its placeholder instead. `by` groups lines by the
 * source it parsed, which has the same blind spot, so a block whose lead is folded is left laid out
 * for the text rather than for the placeholder — no worse than it was, and visible only while a fold
 * sits inside an assignment's target.
 *
 * **Lifetime and cost.** One of these belongs to one [ByAlignedColumn], so its cache dies with the
 * block. Character advances are remembered until the font, its rendering context or the tab size
 * moves — [state] is what says they have, and is the key a caller hangs its own cached geometry on.
 * Everything here is touched only while measuring an inlay, which is on the EDT.
 */
internal class ByEditorTextWidth(private val editor: Editor) {

    /**
     * Everything an advance depends on: the scheme's plain font, the context it is rasterised in and
     * the tab size. Zoom, presentation mode and a scheme change all move the first, a monitor change
     * the second.
     */
    private data class State(val font: Font, val context: FontRenderContext, val tabSize: Int)

    private var state: State? = null

    private val advances = HashMap<Char, Float>()

    private var space: Float = 1f

    /**
     * What the measurements currently answer for — a value to compare, not to read.
     *
     * A caller that caches a width this class computed keys the cache on this: when it changes,
     * every advance has changed with it.
     */
    fun state(): Any = current()

    /** One space of the editor's plain font — a blank column, and what a block keeps as breathing room. */
    fun spaceWidth(): Float {
        current()
        return space
    }

    /**
     * The pixels [text] occupies, starting at the beginning of its line.
     *
     * From the beginning of the line and not from anywhere: a tab's width is its distance to the next
     * tab stop, so the same three characters measure differently at different places on a line, and
     * only a run that starts where the line starts can be measured on its own.
     */
    fun widthOf(text: CharSequence): Float {
        val tabSize = current().tabSize
        var x = 0f
        for (character in text) {
            x = if (character == '\t') EditorUtil.nextTabStop(x, space, tabSize) else x + advanceOf(character)
        }
        return x
    }

    /** The state everything is measured against now, re-reading the font and forgetting stale advances. */
    private fun current(): State {
        val next = State(
            editor.colorsScheme.getFont(EditorFontType.PLAIN),
            FontInfo.getFontRenderContext(editor.contentComponent),
            EditorUtil.getTabSize(editor).coerceAtLeast(1),
        )
        if (next != state) {
            state = next
            advances.clear()
            space = advanceOf(' ')
        }
        return next
    }

    private fun advanceOf(character: Char): Float =
        advances.getOrPut(character) {
            EditorUtil.fontForChar(character, Font.PLAIN, editor).charWidth2D(character.code)
        }
}

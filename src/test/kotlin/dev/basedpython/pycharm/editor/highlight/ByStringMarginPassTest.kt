package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the margins [StringMarginsTest] computes actually reach an editor.
 *
 * Everything between the two is registration and reconciliation — the `highlightingPassFactory`
 * entry in plugin.xml, the language check, and the diff [ByStringMarginPassFactory] does against
 * what is already drawn — and none of it is visible to a unit test of the scanner. A margin that
 * is computed perfectly and never added to the markup model looks exactly like a feature that was
 * never written.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByStringMarginPassTest {

    private val fixture by codeInsightFixture()

    private val q = "\"\"\""

    /**
     * The margins currently drawn in the fixture's editor, measured the way the renderer measures
     * them: from each marked literal's *live* range, not from anything the pass stored.
     */
    private fun drawn(): List<StringMargin> {
        val text = fixture.editor.document.immutableCharSequence
        return fixture.editor.markupModel.allHighlighters
            .filter { (it as? RangeHighlighterEx)?.customRenderer === ByStringMarginRenderer }
            .mapNotNull { StringMargins.marginOf(text, it.startOffset, it.endOffset) }
    }

    @Test
    fun `the pass draws a margin for each multiline literal`() {
        fixture.configureByText("a.by", "a = $q\n    one\n    $q\nb = $q\n      two\n  $q\n")
        fixture.doHighlighting()
        assertEquals(listOf(4, 2), drawn().map { it.indent })
    }

    @Test
    fun `no highlighter where nothing is trimmed`() {
        fixture.configureByText("b.by", "a = $q\none\n$q\nb = \"two\"\n")
        fixture.doHighlighting()
        assertEquals(emptyList<StringMargin>(), drawn())
    }

    /**
     * That the anchor really is a pixel column, through a real editor.
     *
     * [StringMargin.anchorOffset] is chosen so the editor can be *asked* where the trim cuts,
     * instead of the renderer multiplying a column by a character width. Asking is only right if
     * the offset is on the line that defines the margin — here the closing quotes, four in, with
     * the text above them indented eight.
     */
    @Test
    fun `the margin lands on the closing quotes, left of the text above them`() {
        fixture.configureByText("d.by", "a = $q\n        one\n    $q\n")
        fixture.doHighlighting()
        val editor = fixture.editor
        val text = editor.document.text

        val x = editor.offsetToXY(drawn().single().anchorOffset).x
        assertEquals(editor.offsetToXY(text.lastIndexOf(q)).x, x)
        assertTrue(x < editor.offsetToXY(text.indexOf("one")).x)
    }

    /**
     * The rule shares its column with the editor's own indent guide, and must share its colour.
     *
     * A multiline string's lines are an indented run like any other, so the platform draws a
     * guide down it at the indentation they share — the trim column, by the same arithmetic. It
     * draws that guide only on the run's *interior* lines, and over the top of ours. Two colours
     * at one column is therefore not two lines a reader can tell apart: it is one line that
     * changes colour partway down, which is what a string-coloured margin actually looked like.
     */
    @Test
    fun `the rule is drawn in the colour of the editor's own indent guide`() {
        fixture.configureByText("e.by", "a = $q\n    one\n    $q\n")
        val scheme = fixture.editor.colorsScheme
        assertEquals(
            scheme.getColor(EditorColors.INDENT_GUIDE_COLOR),
            ByStringMarginColors.color(scheme),
        )
    }

    /**
     * The pass runs on every keystroke, so a margin that has not moved must not be replaced —
     * a new highlighter repaints the literal, and there is one of these per string in the file.
     */
    @Test
    fun `a second pass over unchanged text reuses the highlighters`() {
        fixture.configureByText("c.by", "a = $q\n    one\n    $q\n")
        fixture.doHighlighting()
        val first = fixture.editor.markupModel.allHighlighters.toList()
        fixture.doHighlighting()
        assertEquals(first, fixture.editor.markupModel.allHighlighters.toList())
    }
}

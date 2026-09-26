package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That [ByEditorTextWidth] answers what the editor draws, asked of the editor itself.
 *
 * Every case is written as a comparison with `Editor.offsetToXY`, which is the editor's own answer
 * to "where is this character" and is arrived at by an entirely different route — a laid-out line of
 * fragments rather than a sum of advances. Asserting a number instead would only record whatever
 * this class happened to compute on the machine the number was written on.
 */
@TestFixtures
class ByEditorTextWidthTest {

    private val fixture by codeInsightFixture()

    /** The editor's own x for the character at [offset], which is what a measurement has to match. */
    private fun drawnAt(text: String, offset: Int): Int {
        fixture.configureByText("measured.txt", text)
        return fixture.editor.offsetToXY(offset).x
    }

    private fun measure(text: String, upTo: Int): Int {
        fixture.configureByText("measured.txt", text)
        return ByEditorTextWidth(fixture.editor).widthOf(text.substring(0, upTo)).toInt()
    }

    @Test
    fun `plain ascii measures where the editor draws it`() = onEdt {
        assertEquals(drawnAt("basdf = 1\n", 5), measure("basdf = 1\n", 5))
    }

    @Test
    fun `a wide character measures the glyph the editor falls back to, not two columns`() = onEdt {
        // The case the whole class exists for. Unicode calls `名` two columns wide and `by` counts it
        // as two; neither is what the editor draws, and the difference is what took a block apart.
        val text = "名前 = 1\n"
        assertEquals(drawnAt(text, 2), measure(text, 2))
        val columns = ByEditorTextWidth(fixture.editor).widthOf("    ")
        assertTrue(
            measure(text, 2).toFloat() != columns,
            "`名前` came out exactly four columns wide, so this editor is not the one the bug needs",
        )
    }

    @Test
    fun `a tab reaches the tab stop in pixels`() = onEdt {
        val text = "x:\tlist[int] = [1]\n"
        fixture.configureByText("tabbed.txt", text)
        assertEquals(4, EditorUtil.getTabSize(fixture.editor), "the tab here only reaches column four at a tab size of four")
        assertEquals(drawnAt(text, 12), measure(text, 12))
    }

    @Test
    fun `an empty run is no width at all`() = onEdt {
        fixture.configureByText("empty.txt", "a = 1\n")
        assertEquals(0f, ByEditorTextWidth(fixture.editor).widthOf(""))
    }

    @Test
    fun `a space is the width the editor lays its own tab stops out with`() = onEdt {
        // `EditorView` takes its plain space width the same way, and a tab stop is a multiple of it,
        // so a separator measured differently would drift against every tabbed line in the file.
        fixture.configureByText("space.txt", "a = 1\n")
        val metrics = ByEditorTextWidth(fixture.editor)
        assertEquals(metrics.widthOf(" "), metrics.spaceWidth())
    }
}

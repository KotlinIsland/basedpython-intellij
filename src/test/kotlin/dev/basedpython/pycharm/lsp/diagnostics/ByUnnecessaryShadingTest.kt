package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.view.IterationState
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * That a range drawn as unused in a `.by` editor is grey in every character, over the colours of
 * the semantic tokens inside it.
 *
 * The highlighters are laid down as the platform's LSP client lays them: the diagnostic, tagged
 * `Unnecessary`, as [CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES] on the weak-warning layer, and
 * each semantic token on that same layer with a colour of its own. What the editor paints is read
 * the way the editor reads it, through [IterationState]'s ordering of the highlighters over a
 * character.
 */
@TestFixtures
class ByUnnecessaryShadingTest {

    private val fixture by codeInsightFixture()

    private val tokenColour = Color(0x12, 0x34, 0x56)

    private val markup get() = DocumentMarkupModel.forDocument(fixture.editor.document, fixture.project, true) as MarkupModelEx

    private fun offsetOf(text: String, occurrence: Int = 0): Int {
        var at = -1
        repeat(occurrence + 1) { at = fixture.editor.document.text.indexOf(text, at + 1) }
        return at
    }

    /** A semantic token over [text], as the platform draws one: weak-warning layer, its own colour. */
    private fun token(text: String, occurrence: Int = 0): RangeHighlighter {
        val start = offsetOf(text, occurrence)
        return markup.addRangeHighlighter(
            start, start + text.length, HighlighterLayer.WEAK_WARNING,
            TextAttributes(tokenColour, null, null, null, 0), HighlighterTargetArea.EXACT_RANGE,
        )
    }

    /** An `Unnecessary` diagnostic over [text], as the platform draws a hint tagged so. */
    private fun unnecessary(text: String, occurrence: Int = 0): RangeHighlighter {
        val start = offsetOf(text, occurrence)
        return markup.addRangeHighlighter(
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES, start, start + text.length,
            HighlighterLayer.WEAK_WARNING, HighlighterTargetArea.EXACT_RANGE,
        )
    }

    /** The foreground the editor paints [offset] in: the first defined by the highlighters over it, in the editor's order. */
    private fun foregroundAt(offset: Int): Color? {
        val editor = fixture.editor as EditorEx
        val over = (markup.allHighlighters.toList() + editor.markupModel.allHighlighters)
            .filter { it.isValid && it.startOffset <= offset && offset < it.endOffset }
            .map { it as RangeHighlighterEx }
            .sortedWith(IterationState.createByLayerThenByAttributesComparator(editor.colorsScheme))
        return over.firstNotNullOfOrNull { it.getTextAttributes(editor.colorsScheme)?.foregroundColor }
    }

    private val grey: Color
        get() = fixture.editor.colorsScheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES)?.foregroundColor
            .also { assertNotNull(it, "the scheme's unused colour has a foreground") }!!

    private fun settle() = PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    @Test
    fun `every name inside an unreachable statement is grey`() = onEdt {
        fixture.configureByText("a.by", "def f():\n    return\n    print(1)\n")
        token("print")
        token("1")
        unnecessary("print(1)")
        settle()

        assertEquals(grey, foregroundAt(offsetOf("print")))
        assertEquals(grey, foregroundAt(offsetOf("1")))
        assertEquals(grey, foregroundAt(offsetOf("(1")))
    }

    @Test
    fun `two ranges on one line are each grey and nothing between them is`() = onEdt {
        fixture.configureByText("b.by", "x = (aa, bb, cc)\n")
        token("aa")
        token("bb")
        token("cc")
        unnecessary("aa")
        unnecessary("cc")
        settle()

        assertEquals(grey, foregroundAt(offsetOf("aa")))
        assertEquals(tokenColour, foregroundAt(offsetOf("bb")))
        assertEquals(grey, foregroundAt(offsetOf("cc")))
    }

    @Test
    fun `a range no longer drawn as unused takes its grey with it`() = onEdt {
        fixture.configureByText("c.by", "def f():\n    return\n    print(1)\n")
        token("print")
        val diagnostic = unnecessary("print(1)")
        settle()
        assertEquals(grey, foregroundAt(offsetOf("print")))

        markup.removeHighlighter(diagnostic)
        settle()
        assertEquals(tokenColour, foregroundAt(offsetOf("print")))
        assertEquals(emptyList<RangeHighlighter>(), ByUnnecessaryShading.getInstance(fixture.project).shadesIn(fixture.editor))
    }

    @Test
    fun `an unused range drawn before the editor opened is grey once it does`() = onEdt {
        fixture.configureByText("d.by", "def f():\n    return\n    print(1)\n")
        val shading = ByUnnecessaryShading.getInstance(fixture.project)
        shading.released(fixture.editor)
        token("print")
        unnecessary("print(1)")
        settle()
        assertEquals(tokenColour, foregroundAt(offsetOf("print")))

        shading.opened(fixture.editor)
        assertEquals(grey, foregroundAt(offsetOf("print")))
    }

    @Test
    fun `a file by does not serve is left as it is drawn`() = onEdt {
        fixture.configureByText("e.txt", "print(1)\n")
        token("print")
        unnecessary("print(1)")
        settle()

        assertNotEquals(grey, foregroundAt(offsetOf("print")))
    }
}

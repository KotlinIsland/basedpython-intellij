package dev.basedpython.pycharm.lsp.typeinfo

import com.intellij.codeInsight.hint.ShowExpressionTypeHandler
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Type Info is a platform action driven by whichever [com.intellij.lang.ExpressionTypeProvider] is
 * registered for the caret's language, so the two things worth asserting without a live server are
 * that the provider is *found* for `.by` (registration, not code, is what was missing before) and
 * that it offers only the tokens `by` can type.
 *
 * The hint itself needs a running `by` server and is not asserted here; the binary-free half of it —
 * turning a hover payload into hint HTML — is [ByHoverMarkupTest].
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTypeInfoProviderTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `the action finds a provider for by files`() {
        val handlers = ShowExpressionTypeHandler.getHandlers(fixture.project, BasedPythonLanguage)
        assertTrue(
            handlers.any { it is ByTypeInfoProvider },
            "Type Info (Ctrl+Shift+P) is dead in a .by file unless a codeInsight.typeInfo " +
                "extension is registered for the basedpython language; found: $handlers",
        )
    }

    @Test
    fun `offers the name under the caret`() {
        val file = fixture.configureByText("a.by", "value = 1\nprint(val<caret>ue)")
        val leaf = file.findElementAt(fixture.caretOffset)!!
        val expressions = ByTypeInfoProvider().getExpressionsAt(leaf)
        assertEquals(listOf(leaf), expressions)
        assertEquals("value", expressions.single().text)
    }

    /** `by` reports nothing for a literal, so a number would only ever produce an empty hint. */
    @Test
    fun `does not offer numbers, operators or whitespace`() {
        val file = fixture.configureByText("a.by", "x = 1 + 2  # note")
        val offered = { offset: Int ->
            ByTypeInfoProvider().getExpressionsAt(file.findElementAt(offset)!!).isNotEmpty()
        }
        assertFalse(offered(file.text.indexOf('1')), "number literal")
        assertFalse(offered(file.text.indexOf('+')), "operator")
        assertFalse(offered(file.text.indexOf(' ')), "whitespace")
        assertFalse(offered(file.text.indexOf('#')), "comment")
        assertTrue(offered(file.text.indexOf('x')), "identifier")
    }

    /** A `.py` file with no basedpython marker is Python's, and its own provider answers there. */
    @Test
    fun `ignores elements from other languages`() {
        val file = fixture.configureByText("a.txt", "value")
        assertTrue(ByTypeInfoProvider().getExpressionsAt(file.findElementAt(0)!!).isEmpty())
    }

    @Test
    fun `says why there is no type when no server is running`() {
        val file = fixture.configureByText("a.by", "val<caret>ue = 1")
        val leaf = file.findElementAt(fixture.caretOffset)!!
        assertEquals(
            "No type: the by language server is not running",
            ByTypeInfoProvider().getInformationHint(leaf),
        )
    }
}

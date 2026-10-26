package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByDocstringStub
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `"""` and Enter at the top of a function: which line Enter has just left is read from the text;
 * the stub, and whether the literal is a function's docstring at all, is `by`'s answer.
 */
@TestFixtures
class ByDocstringStubsTest {

    private val fixture by codeInsightFixture()

    /** Configures [text] and says what [ByDocstringStubs.openedAt] finds at its caret. */
    private fun opened(text: String): ByDocstringStubs.Opened? {
        fixture.configureByText("a.by", text)
        val editor = fixture.editor
        return ByDocstringStubs.openedAt(editor.document.immutableCharSequence, editor.caretModel.offset) {
            editor.highlighter.createIterator(it).start
        }
    }

    @Test
    fun `an empty docstring the quote handler closed`() = onEdt {
        val text = "def f(a):\n    \"\"\"\n    <caret>\"\"\"\n    return a\n"
        assertEquals(ByDocstringStubs.Opened("    ", "\"\"\"", text.replace("<caret>", "").indexOf("\"\"\"\n    return")), opened(text))
    }

    @Test
    fun `an opening that does not close is closed by the handler`() = onEdt {
        assertEquals(ByDocstringStubs.Opened("    ", "'''", null), opened("def f(a):\n    r'''\n    <caret>\n    return a\n"))
    }

    @Test
    fun `the closing quotes of a docstring are not an opening`() = onEdt {
        assertNull(opened("def f(a):\n    \"\"\"\n    Summary.\n    \"\"\"\n    <caret>\n    return a\n"))
    }

    @Test
    fun `nor is a string with text, an assigned string, or an f-string`() = onEdt {
        assertNull(opened("def f(a):\n    \"\"\"Summary\n    <caret>\"\"\"\n"))
        assertNull(opened("def f(a):\n    x = \"\"\"\n    <caret>\n"))
        assertNull(opened("def f(a):\n    f\"\"\"\n    <caret>\"\"\"\n"))
    }

    @Test
    fun `by's stub replaces the literal and the caret goes where by says`() = onEdt {
        fixture.configureByText("a.by", "def f(item):\n    \"\"\"\n    <caret>\"\"\"\n")
        val stub = ByDocstringStub(
            range = Range(Position(1, 4), Position(2, 7)),
            newText = "\"\"\"\n    \n\n    :param item:\n    \"\"\"",
            caret = Position(2, 4),
        )
        val job = fixture.project.service<ByDocstringStubs>().fill(fixture.editor) { _, _ -> stub }
        job.join()
        assertEquals("def f(item):\n    \"\"\"\n    \n\n    :param item:\n    \"\"\"\n", fixture.editor.document.text)
        assertEquals(fixture.editor.document.getLineStartOffset(2) + 4, fixture.editor.caretModel.offset)
    }
}

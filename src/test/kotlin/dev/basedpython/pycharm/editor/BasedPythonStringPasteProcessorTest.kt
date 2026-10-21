package dev.basedpython.pycharm.editor

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.StringSelection

/** Pasting into a `.by` string literal, through the real Copy and Paste actions. */
@TestFixtures
class BasedPythonStringPasteProcessorTest {

    private val fixture by codeInsightFixture()

    private fun paste(clipboard: String, before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)? = null) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        CopyPasteManager.getInstance().setContents(StringSelection(clipboard))
        fixture.performEditorAction(IdeActions.ACTION_PASTE)
        fixture.checkResult(after)
    }

    @Test
    fun `quotes and backslashes pasted into a literal are escaped`() = paste(
        "say \"hi\" \\o/",
        "x = \"<caret>\"\n",
        "x = \"say \\\"hi\\\" \\\\o/<caret>\"\n",
    )

    @Test
    fun `a line break pasted into a one-line literal becomes an escape`() = paste(
        "a\nb",
        "x = 'one <caret>'\n",
        "x = 'one a\\nb<caret>'\n",
    )

    @Test
    fun `the other kind of quote is left alone`() = paste(
        "it's",
        "x = \"<caret>\"\n",
        "x = \"it's<caret>\"\n",
    )

    @Test
    fun `a raw literal is pasted into as it is`() = paste(
        "\\d+",
        "x = r\"<caret>\"\n",
        "x = r\"\\d+<caret>\"\n",
    )

    @Test
    fun `outside a literal nothing is escaped`() = paste(
        "\"a\"",
        "x = <caret>\n",
        "x = \"a\"<caret>\n",
    )

    @Test
    fun `braces pasted into an f-string's text are doubled`() = paste(
        "{x}",
        "y = f\"<caret>{a}\"\n",
        "y = f\"{{x}}<caret>{a}\"\n",
    ) {
        simple("y = f\"{a}\"")
        string("f\"{a}\"", interpolations = listOf("{a}"))
    }

    @Test
    fun `inside an f-string's interpolation the paste is code`() = paste(
        "b[\"k\"]",
        "y = f\"{<caret>}\"\n",
        "y = f\"{b[\"k\"]<caret>}\"\n",
    ) {
        simple("y = f\"{}\"")
        string("f\"{}\"", interpolations = listOf("{}"))
    }

    /** Without `by`'s outline there is no telling text from interpolation, so nothing is touched. */
    @Test
    fun `without an outline an f-string is pasted into as it is`() = paste(
        "{x}",
        "y = f\"<caret>\"\n",
        "y = f\"{x}<caret>\"\n",
    )

    @Test
    fun `text copied from inside one literal goes into another as it is written`() = onEdt {
        fixture.configureByText("a.by", "a = \"<selection>say \\\"hi\\\"</selection>\"\nb = \"<caret>\"\n")
        fixture.performEditorAction(IdeActions.ACTION_COPY)
        val document = fixture.editor.document
        fixture.editor.selectionModel.removeSelection()
        fixture.editor.caretModel.moveToOffset(document.text.lastIndexOf('"'))
        fixture.performEditorAction(IdeActions.ACTION_PASTE)
        fixture.checkResult("a = \"say \\\"hi\\\"\"\nb = \"say \\\"hi\\\"<caret>\"\n")
    }

    @Test
    fun `text copied from a literal is still escaped where it would not fit`() = onEdt {
        // a `'` is ordinary in a "-quoted literal, and would end a '-quoted one
        fixture.configureByText("a.by", "a = \"<selection>it's</selection>\"\nb = '<caret>'\n")
        fixture.performEditorAction(IdeActions.ACTION_COPY)
        val document = fixture.editor.document
        fixture.editor.selectionModel.removeSelection()
        fixture.editor.caretModel.moveToOffset(document.text.lastIndexOf('\''))
        fixture.performEditorAction(IdeActions.ACTION_PASTE)
        fixture.checkResult("a = \"it's\"\nb = 'it\\'s<caret>'\n")
    }

    // --- the escaping itself ---

    private fun context(quote: Char = '"', triple: Boolean = false, f: Boolean = false, before: Int = 0, after: Int = 0) =
        StringPasteEscaping.Context(quote, triple, f, before, after)

    @Test
    fun `in a triple-quoted literal only a third quote in a row is escaped`() {
        assertEquals("a \"b\" \"\"\\\"c\n", StringPasteEscaping.escape("a \"b\" \"\"\"c\n", context(triple = true)))
    }

    @Test
    fun `quotes running into the closing ones are escaped`() {
        assertEquals("a\\\"", StringPasteEscaping.escape("a\"", context(triple = true, after = 3)))
        assertEquals("a\"", StringPasteEscaping.escape("a\"", context(triple = true, after = 0)))
    }

    @Test
    fun `quotes counted before the paste make a third`() {
        assertEquals("\\\"", StringPasteEscaping.escape("\"", context(triple = true, before = 2)))
    }

    @Test
    fun `what fits a literal as it is`() {
        assertTrue(StringPasteEscaping.fits("a\\\"b", context()))
        assertFalse(StringPasteEscaping.fits("a\"b", context()))
        assertFalse(StringPasteEscaping.fits("a\nb", context()))
        assertTrue(StringPasteEscaping.fits("a\nb", context(triple = true)))
        assertFalse(StringPasteEscaping.fits("a\\", context()))
        assertFalse(StringPasteEscaping.fits("\"\"\"", context(triple = true)))
    }
}

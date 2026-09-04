package dev.basedpython.pycharm.lang

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The string literal as a PSI element: that a `.by` tree now has one at all, and that it behaves as
 * the injection host the platform expects.
 *
 * The tree being flat is the thing most likely to be broken by accident here — every lexer-driven
 * feature in the plugin reads the `BY_STRING` leaf, and wrapping it must not move it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonStringLiteralTest {

    private val fixture by codeInsightFixture()

    private fun literalsIn(source: String): List<BasedPythonStringLiteral> {
        val file = fixture.configureByText("a.by", source)
        return PsiTreeUtil.findChildrenOfType(file, BasedPythonStringLiteral::class.java).toList()
    }

    private fun literal(source: String): BasedPythonStringLiteral = literalsIn(source).first()

    // region: the tree

    @Test
    fun `every string in a file is one element`() {
        val literals = literalsIn("a = \"one\"\nb = 'two'\nc = \"\"\"three\"\"\"\n")
        assertEquals(listOf("\"one\"", "'two'", "\"\"\"three\"\"\""), literals.map { it.text })
    }

    @Test
    fun `the token underneath is still the token everything else reads`() {
        val literal = literal("a = \"hi\"\n")
        val leaf = literal.node.firstChildNode
        assertEquals(BasedPythonTokenTypes.STRING, leaf.elementType)
        assertEquals(literal.textRange, leaf.textRange)
        assertEquals(1, literal.node.getChildren(null).size)
    }

    @Test
    fun `nothing but strings gains a node`() {
        val file = fixture.configureByText("b.by", "a = 1  # note\n")
        assertEquals(0, PsiTreeUtil.findChildrenOfType(file, BasedPythonStringLiteral::class.java).size)
    }

    // endregion

    // region: what can be injected into

    @Test
    fun `an ordinary literal is a host`() {
        assertTrue(literal("a = \"hi\"\n").isValidHost)
        assertTrue(literal("a = r\"hi\"\n").isValidHost)
        assertTrue(literal("a = \"\"\"hi\"\"\"\n").isValidHost)
    }

    @Test
    fun `an f-string is not, because its braces are code`() {
        assertFalse(literal("a = f\"{x}\"\n").isValidHost)
    }

    @Test
    fun `bytes are not, because they are not text`() {
        assertFalse(literal("a = b\"hi\"\n").isValidHost)
    }

    @Test
    fun `a literal still being typed is not`() {
        assertFalse(literal("a = \"hi\n").isValidHost)
    }

    @Test
    fun `the content range stops inside the quotes`() {
        assertEquals(TextRange(1, 3), literal("a = \"hi\"\n").contentRange)
        assertEquals(TextRange(2, 4), literal("a = r\"hi\"\n").contentRange)
        assertEquals(TextRange(3, 5), literal("a = \"\"\"hi\"\"\"\n").contentRange)
    }

    // endregion

    // region: reading and writing the content

    @Test
    fun `the escaper decodes the content the platform will inject`() {
        val literal = literal("a = \"<a href=\\\"/\\\">\"\n")
        val escaper = literal.createLiteralTextEscaper()
        val decoded = StringBuilder()
        assertTrue(escaper.decode(literal.contentRange, decoded))
        assertEquals("<a href=\"/\">", decoded.toString())
    }

    @Test
    fun `an offset in the decoded text maps back into the literal`() {
        val literal = literal("a = \"<a href=\\\"/\\\">\"\n")
        val escaper = literal.createLiteralTextEscaper()
        val decoded = StringBuilder()
        escaper.decode(literal.contentRange, decoded)

        // The `"` at index 8 of `<a href="/">` is the `\"` that starts at offset 8 of the content,
        // which is offset 9 of the literal — one past the opening quote.
        assertEquals(8, decoded.indexOf("\""))
        assertEquals(9, escaper.getOffsetInHost(8, literal.contentRange))
    }

    @Test
    fun `only a triple-quoted literal can hold more than one line`() {
        assertTrue(literal("a = \"hi\"\n").createLiteralTextEscaper().isOneLine)
        assertFalse(literal("a = \"\"\"hi\"\"\"\n").createLiteralTextEscaper().isOneLine)
    }

    @Test
    fun `changing the content writes it back as source`() {
        val literal = literal("a = \"hi\"\n")
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            ElementManipulators.handleContentChange(literal, "say \"hi\"")
        }
        assertEquals("a = \"say \\\"hi\\\"\"\n", fixture.file!!.text)
    }

    @Test
    fun `a raw literal given content no raw literal can spell loses its prefix`() {
        val literal = literal("a = r\"\\d\"\n")
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            ElementManipulators.handleContentChange(literal, "say \"hi\"")
        }
        assertEquals("a = \"say \\\"hi\\\"\"\n", fixture.file!!.text)
    }

    @Test
    fun `a raw literal that can still spell its content keeps its prefix`() {
        val literal = literal("a = r\"\\d\"\n")
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            ElementManipulators.handleContentChange(literal, "\\w+")
        }
        assertEquals("a = r\"\\w+\"\n", fixture.file!!.text)
    }

    @Test
    fun `a triple-quoted literal keeps its line breaks and all but the quotes that would close it`() {
        val literal = literal("a = \"\"\"hi\"\"\"\n")
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            ElementManipulators.handleContentChange(literal, "<p class=\"x\">\n</p>")
        }
        assertEquals("a = \"\"\"<p class=\"x\">\n</p>\"\"\"\n", fixture.file!!.text)
        assertNotNull(StringLiteralShape.of(literal.text))
    }

    // endregion
}

package dev.basedpython.pycharm.editor.templates

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Expansion mechanics for postfix templates.
 *
 * The document state these tests describe is the one `expand` actually sees: `PostfixLiveTemplate`
 * has already deleted the `.print` key, so the caret sits at the end of the expression and the dot
 * is gone. The previous implementation subtracted the key length a second time and replaced from the
 * start of the line, which sliced into the expression and ate anything in front of it.
 */
class PostfixExpansionTest {

    /** Applies [body] at the end of [text] and returns the resulting document. */
    private fun apply(text: String, body: (String) -> String): String? {
        val e = postfixExpansion(text, text.length, body) ?: return null
        return text.substring(0, e.startOffset) + e.text + text.substring(e.endOffset)
    }

    @Test
    fun `print wraps the whole expression`() {
        assertEquals("print(value)", apply("value") { "print($it)" })
    }

    @Test
    fun `text before the expression survives`() {
        // The old expansion replaced from the line start and lost the `x = `.
        assertEquals("x = len(value)", apply("x = value") { "len($it)" })
    }

    @Test
    fun `a call with spaces in its arguments is taken whole`() {
        assertEquals("print(foo(a, b))", apply("foo(a, b)") { "print($it)" })
    }

    @Test
    fun `nested brackets are balanced`() {
        assertEquals("len(foo(bar(a, b), c))", apply("foo(bar(a, b), c)") { "len($it)" })
    }

    @Test
    fun `subscripts are part of the expression`() {
        assertEquals("print(items[a + 1])", apply("items[a + 1]") { "print($it)" })
    }

    @Test
    fun `a string literal with spaces is taken whole`() {
        assertEquals("""len("a b c")""", apply(""""a b c"""") { "len($it)" })
    }

    @Test
    fun `a bracket inside a string does not unbalance the scan`() {
        assertEquals("""print(f("a)b"))""", apply("""f("a)b")""") { "print($it)" })
    }

    @Test
    fun `attribute chains are part of the expression`() {
        assertEquals("print(self.a.b)", apply("self.a.b") { "print($it)" })
    }

    @Test
    fun `indentation is applied to continuation lines`() {
        val out = apply("    value") { "if $it:\n    pass" }
        assertEquals("    if value:\n        pass", out)
    }

    @Test
    fun `the caret marker is stripped and reported`() {
        val e = postfixExpansion("value", 5) { "${CARET_MARKER}name = $it" }!!
        assertEquals("name = value", e.text)
        assertEquals(0, e.caretOffset)
    }

    @Test
    fun `the caret defaults to the end of the replacement`() {
        val e = postfixExpansion("value", 5) { "print($it)" }!!
        assertEquals("print(value)".length, e.caretOffset)
    }

    @Test
    fun `the caret marker is placed after indentation is applied`() {
        val e = postfixExpansion("    value", 9) { "if $it:\n    $CARET_MARKER" }!!
        assertEquals("if value:\n        ", e.text)
        assertEquals(4 + e.text.length, e.caretOffset)
    }

    @Test
    fun `no expression before the caret yields no expansion`() {
        assertNull(postfixExpansion("    ", 4) { "print($it)" })
        assertNull(postfixExpansion("", 0) { "print($it)" })
    }

    @Test
    fun `a dot before the caret yields no expansion`() {
        // `bool()...` — the platform deletes the third dot before asking, so what it asks about is
        // `bool()..`. The user is typing an ellipsis, not an attribute, and the templates that used
        // to be offered here expanded `bool()..` into `print(bool()..)`.
        assertNull(postfixExpansion("if bool()..", 11) { "print($it)" })
        assertNull(postfixExpansion("value.", 6) { "print($it)" })
    }

    @Test
    fun `an ellipsis on its own yields no expansion`() {
        assertNull(postfixExpansion("..", 2) { "print($it)" })
    }

    @Test
    fun `an unbalanced closing bracket yields no expansion`() {
        assertNull(postfixExpansion("a, b)", 5) { "print($it)" })
    }

    @Test
    fun `the expression stops at an operator`() {
        assertEquals("a + print(b)", apply("a + b") { "print($it)" })
    }
}

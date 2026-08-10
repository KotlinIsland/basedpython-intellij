package dev.basedpython.pycharm.editor.templates

import junit.framework.TestCase

/**
 * Expansion mechanics for postfix templates.
 *
 * The document state these tests describe is the one `expand` actually sees: `PostfixLiveTemplate`
 * has already deleted the `.print` key, so the caret sits at the end of the expression and the dot
 * is gone. The previous implementation subtracted the key length a second time and replaced from the
 * start of the line, which sliced into the expression and ate anything in front of it.
 */
class PostfixExpansionTest : TestCase() {

    /** Applies [body] at the end of [text] and returns the resulting document. */
    private fun apply(text: String, body: (String) -> String): String? {
        val e = postfixExpansion(text, text.length, body) ?: return null
        return text.substring(0, e.startOffset) + e.text + text.substring(e.endOffset)
    }

    fun `test print wraps the whole expression`() {
        assertEquals("print(value)", apply("value") { "print($it)" })
    }

    fun `test text before the expression survives`() {
        // The old expansion replaced from the line start and lost the `x = `.
        assertEquals("x = len(value)", apply("x = value") { "len($it)" })
    }

    fun `test a call with spaces in its arguments is taken whole`() {
        assertEquals("print(foo(a, b))", apply("foo(a, b)") { "print($it)" })
    }

    fun `test nested brackets are balanced`() {
        assertEquals("len(foo(bar(a, b), c))", apply("foo(bar(a, b), c)") { "len($it)" })
    }

    fun `test subscripts are part of the expression`() {
        assertEquals("print(items[a + 1])", apply("items[a + 1]") { "print($it)" })
    }

    fun `test a string literal with spaces is taken whole`() {
        assertEquals("""len("a b c")""", apply(""""a b c"""") { "len($it)" })
    }

    fun `test a bracket inside a string does not unbalance the scan`() {
        assertEquals("""print(f("a)b"))""", apply("""f("a)b")""") { "print($it)" })
    }

    fun `test attribute chains are part of the expression`() {
        assertEquals("print(self.a.b)", apply("self.a.b") { "print($it)" })
    }

    fun `test indentation is applied to continuation lines`() {
        val out = apply("    value") { "if $it:\n    pass" }
        assertEquals("    if value:\n        pass", out)
    }

    fun `test the caret marker is stripped and reported`() {
        val e = postfixExpansion("value", 5) { "${CARET_MARKER}name = $it" }!!
        assertEquals("name = value", e.text)
        assertEquals(0, e.caretOffset)
    }

    fun `test the caret defaults to the end of the replacement`() {
        val e = postfixExpansion("value", 5) { "print($it)" }!!
        assertEquals("print(value)".length, e.caretOffset)
    }

    fun `test the caret marker is placed after indentation is applied`() {
        val e = postfixExpansion("    value", 9) { "if $it:\n    $CARET_MARKER" }!!
        assertEquals("if value:\n        ", e.text)
        assertEquals(4 + e.text.length, e.caretOffset)
    }

    fun `test no expression before the caret yields no expansion`() {
        assertNull(postfixExpansion("    ", 4) { "print($it)" })
        assertNull(postfixExpansion("", 0) { "print($it)" })
    }

    fun `test an unbalanced closing bracket yields no expansion`() {
        assertNull(postfixExpansion("a, b)", 5) { "print($it)" })
    }

    fun `test the expression stops at an operator`() {
        assertEquals("a + print(b)", apply("a + b") { "print($it)" })
    }
}

package dev.basedpython.pycharm.refactoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for [InlineLogic]. No IDE fixture required.
 *
 * A small helper [applyPlan] is used by several tests to assert the *result* of applying a plan to
 * source text, exactly as [InlineVariableAction] does (descending edits).
 */
class InlineLogicTest {

    /** Applies the plan produced for [name] to [src], returning the rewritten text (or null). */
    private fun applyByName(src: String, name: String): String? {
        val plan = InlineLogic.planInlineFor(src, name) ?: return null
        val sb = StringBuilder(src)
        for (edit in plan.toEdits()) {
            sb.replace(edit.start, edit.end, edit.replacement)
        }
        return sb.toString()
    }

    /** Applies the plan produced for the caret marker `|` in [withCaret]. */
    private fun applyAtCaret(withCaret: String): String? {
        val caret = withCaret.indexOf('|')
        val src = withCaret.replace("|", "")
        val plan = InlineLogic.planInline(src, caret) ?: return null
        val sb = StringBuilder(src)
        for (edit in plan.toEdits()) {
            sb.replace(edit.start, edit.end, edit.replacement)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // identifierAt
    // ------------------------------------------------------------------

    @Test
    fun `identifierAt inside word`() {
        assertEquals("foo", InlineLogic.identifierAt("foo = bar", 1))
    }

    @Test
    fun `identifierAt at leading edge`() {
        assertEquals("foo", InlineLogic.identifierAt("foo = bar", 0))
    }

    @Test
    fun `identifierAt at trailing edge`() {
        // caret right after the last char of foo
        assertEquals("foo", InlineLogic.identifierAt("foo = bar", 3))
    }

    @Test
    fun `identifierAt on whitespace is null`() {
        assertNull(InlineLogic.identifierAt("foo = bar", 4))
    }

    @Test
    fun `identifierAt on empty text is null`() {
        assertNull(InlineLogic.identifierAt("", 0))
    }

    @Test
    fun `identifierAt does not start with digit`() {
        // caret on the digit run "123" -> not a valid identifier
        assertNull(InlineLogic.identifierAt("x = 123", 5))
    }

    // ------------------------------------------------------------------
    // wordOccurrences (word-boundary correctness)
    // ------------------------------------------------------------------

    @Test
    fun `wordOccurrences finds standalone words only`() {
        val t = "name = 1\nx = name + names + myname + name"
        // occurrences of exactly "name": the LHS, and the two standalone uses on line 2
        assertEquals(listOf(0, 13, 37), InlineLogic.wordOccurrences(t, "name"))
    }

    @Test
    fun `wordOccurrences ignores substring inside longer identifier`() {
        assertEquals(emptyList<Int>(), InlineLogic.wordOccurrences("classname = 1", "name"))
    }

    @Test
    fun `wordOccurrences empty name returns empty`() {
        assertEquals(emptyList<Int>(), InlineLogic.wordOccurrences("abc", ""))
    }

    @Test
    fun `wordOccurrences multiple on one line`() {
        // "a == a == a": 'a' chars at offsets 0, 5, 10
        assertEquals(listOf(0, 5, 10), InlineLogic.wordOccurrences("a == a == a", "a"))
    }

    // ------------------------------------------------------------------
    // parenthesize
    // ------------------------------------------------------------------

    @Test
    fun `parenthesize wraps compound expression`() {
        assertEquals("(a + b)", InlineLogic.parenthesize("a + b"))
    }

    @Test
    fun `parenthesize leaves bare identifier atomic`() {
        assertEquals("foo", InlineLogic.parenthesize("foo"))
    }

    @Test
    fun `parenthesize leaves dotted primary atomic`() {
        assertEquals("a.b.c", InlineLogic.parenthesize("a.b.c"))
    }

    @Test
    fun `parenthesize leaves number atomic`() {
        assertEquals("42", InlineLogic.parenthesize("42"))
        assertEquals("3.14", InlineLogic.parenthesize("3.14"))
    }

    @Test
    fun `parenthesize leaves already wrapped paren expr atomic`() {
        assertEquals("(a + b)", InlineLogic.parenthesize("(a + b)"))
    }

    @Test
    fun `parenthesize wraps call expression`() {
        assertEquals("(foo(x))", InlineLogic.parenthesize("foo(x)"))
    }

    @Test
    fun `parenthesize leaves list literal atomic`() {
        assertEquals("[1, 2, 3]", InlineLogic.parenthesize("[1, 2, 3]"))
    }

    @Test
    fun `parenthesize leaves string literal atomic`() {
        assertEquals("\"hi\"", InlineLogic.parenthesize("\"hi\""))
    }

    @Test
    fun `parenthesize wraps two parenthesized groups`() {
        // (a) + (b) is not a single enclosing pair -> must wrap
        assertEquals("((a) + (b))", InlineLogic.parenthesize("(a) + (b)"))
    }

    @Test
    fun `parenthesize trims whitespace`() {
        assertEquals("(a + b)", InlineLogic.parenthesize("  a + b  "))
    }

    // ------------------------------------------------------------------
    // isValidIdentifier
    // ------------------------------------------------------------------

    @Test
    fun `isValidIdentifier accepts normal names`() {
        assertTrue(InlineLogic.isValidIdentifier("foo_bar1"))
        assertTrue(InlineLogic.isValidIdentifier("_x"))
    }

    @Test
    fun `isValidIdentifier rejects bad names`() {
        assertFalse(InlineLogic.isValidIdentifier(""))
        assertFalse(InlineLogic.isValidIdentifier("1abc"))
        assertFalse(InlineLogic.isValidIdentifier("a-b"))
    }

    // ------------------------------------------------------------------
    // planInline / planInlineFor — happy paths
    // ------------------------------------------------------------------

    @Test
    fun `inline single usage compound rhs is parenthesized`() {
        val out = applyByName("x = a + b\nresult = x * 2\n", "x")
        assertEquals("result = (a + b) * 2\n", out)
    }

    @Test
    fun `inline multiple usages`() {
        val out = applyByName("x = a + b\ny = x + x\n", "x")
        assertEquals("y = (a + b) + (a + b)\n", out)
    }

    @Test
    fun `inline atomic rhs not parenthesized`() {
        val out = applyByName("x = foo\ny = x\n", "x")
        assertEquals("y = foo\n", out)
    }

    @Test
    fun `inline preserves leading indentation of usage line`() {
        val src = "def f():\n    x = a + b\n    return x\n"
        val out = applyByName(src, "x")
        assertEquals("def f():\n    return (a + b)\n", out)
    }

    @Test
    fun `inline does not touch similar identifiers`() {
        val src = "x = 1\ny = x + xs + x_value + x\n"
        val out = applyByName(src, "x")
        assertEquals("y = 1 + xs + x_value + 1\n", out)
    }

    @Test
    fun `inline at caret resolves identifier`() {
        val out = applyAtCaret("co|unt = a + b\ntotal = count + 1\n")
        assertEquals("total = (a + b) + 1\n", out)
    }

    @Test
    fun `inline does not replace lhs occurrence`() {
        // the LHS "x" must be removed (with its line), only the usage replaced
        val out = applyByName("x = 5\nx_usage_line = x\n", "x")
        assertEquals("x_usage_line = 5\n", out)
    }

    @Test
    fun `inline keeps surrounding lines intact`() {
        val src = "a = 1\nx = a + b\nz = x\nq = 9\n"
        val out = applyByName(src, "x")
        assertEquals("a = 1\nz = (a + b)\nq = 9\n", out)
    }

    // ------------------------------------------------------------------
    // planInline — bail / no-op paths
    // ------------------------------------------------------------------

    @Test
    fun `bail on multiple assignments`() {
        assertNull(InlineLogic.planInlineFor("x = 1\nx = 2\ny = x\n", "x"))
    }

    @Test
    fun `bail on zero assignments`() {
        assertNull(InlineLogic.planInlineFor("y = x + 1\n", "x"))
    }

    @Test
    fun `bail on no usages besides assignment`() {
        assertNull(InlineLogic.planInlineFor("x = a + b\ny = 1\n", "x"))
    }

    @Test
    fun `bail on invalid identifier`() {
        assertNull(InlineLogic.planInlineFor("text", "1bad"))
    }

    @Test
    fun `bail on augmented assignment only`() {
        // x += 1 is not a simple assignment; there is no plain `x =` definition
        assertNull(InlineLogic.planInlineFor("x += 1\ny = x\n", "x"))
    }

    @Test
    fun `comparison line is not an assignment`() {
        // `x == 5` must not be mistaken for an assignment; with no real def -> bail
        assertNull(InlineLogic.planInlineFor("if x == 5:\n    y = x\n", "x"))
    }

    @Test
    fun `bail when caret not on identifier`() {
        // offset 2 sits on the '=' sign, not adjacent to any identifier char
        assertNull(InlineLogic.planInline("x = 1\ny = x\n", 2))
    }

    // ------------------------------------------------------------------
    // plan structure
    // ------------------------------------------------------------------

    @Test
    fun `plan reports delete range covering whole assignment line`() {
        val plan = InlineLogic.planInlineFor("x = a + b\ny = x\n", "x")
        assertNotNull(plan)
        plan!!
        assertEquals(0, plan.deleteStart)
        assertEquals(10, plan.deleteEnd) // "x = a + b\n" is 10 chars
        assertEquals("(a + b)", plan.exprText)
        assertEquals(1, plan.usageReplacements.size)
    }

    @Test
    fun `toEdits sorted descending by start`() {
        val plan = InlineLogic.planInlineFor("x = a + b\ny = x + x\n", "x")!!
        val edits = plan.toEdits()
        val starts = edits.map { it.start }
        assertEquals(starts.sortedDescending(), starts)
    }
}

package dev.basedpython.pycharm.refactoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic unit tests for [ExtractionLogic]. No IDE fixture required.
 */
class ExtractionLogicTest {

    // ------------------------------------------------------------------
    // lineStartOffset
    // ------------------------------------------------------------------

    @Test
    fun `lineStartOffset on empty text`() {
        assertEquals(0, ExtractionLogic.lineStartOffset("", 0))
    }

    @Test
    fun `lineStartOffset on first line`() {
        val t = "abc\ndef"
        assertEquals(0, ExtractionLogic.lineStartOffset(t, 2))
    }

    @Test
    fun `lineStartOffset on second line`() {
        val t = "abc\ndef"
        // 'e' is at index 5; line starts at 4
        assertEquals(4, ExtractionLogic.lineStartOffset(t, 5))
    }

    @Test
    fun `lineStartOffset at exact newline boundary`() {
        val t = "abc\ndef"
        // offset 4 is start of second line
        assertEquals(4, ExtractionLogic.lineStartOffset(t, 4))
    }

    // ------------------------------------------------------------------
    // lineIndentString / width
    // ------------------------------------------------------------------

    @Test
    fun `indent string for no indentation`() {
        assertEquals("", ExtractionLogic.lineIndentString("foo = 1\n", 3))
    }

    @Test
    fun `indent string for four spaces`() {
        val t = "    foo = 1\n"
        assertEquals("    ", ExtractionLogic.lineIndentString(t, 6))
        assertEquals(4, ExtractionLogic.lineIndentWidth(t, 6))
    }

    @Test
    fun `indent string preserves tabs verbatim`() {
        val t = "\t\tfoo = 1\n"
        assertEquals("\t\t", ExtractionLogic.lineIndentString(t, 4))
        assertEquals(8, ExtractionLogic.lineIndentWidth(t, 4))
    }

    @Test
    fun `indent string for nested line in multiline text`() {
        val t = "def f():\n    if x:\n        y = 1\n"
        val offset = t.indexOf("y = 1")
        assertEquals("        ", ExtractionLogic.lineIndentString(t, offset))
        assertEquals(8, ExtractionLogic.lineIndentWidth(t, offset))
    }

    @Test
    fun `indent width mixed tab and space`() {
        val t = "\t  x = 1\n"
        // tab(4) + 2 spaces = 6
        assertEquals(6, ExtractionLogic.lineIndentWidth(t, 4))
        assertEquals("\t  ", ExtractionLogic.lineIndentString(t, 4))
    }

    // ------------------------------------------------------------------
    // buildAssignmentLine
    // ------------------------------------------------------------------

    @Test
    fun `assignment line no indent`() {
        assertEquals("x = 1 + 2\n", ExtractionLogic.buildAssignmentLine("", "x", "1 + 2"))
    }

    @Test
    fun `assignment line trims expr`() {
        assertEquals("    x = a + b\n", ExtractionLogic.buildAssignmentLine("    ", "x", "  a + b  "))
    }

    // ------------------------------------------------------------------
    // constantInsertionOffset
    // ------------------------------------------------------------------

    @Test
    fun `constant offset empty file`() {
        assertEquals(0, ExtractionLogic.constantInsertionOffset(""))
    }

    @Test
    fun `constant offset file starting with code`() {
        val t = "x = compute()\nprint(x)\n"
        assertEquals(0, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset after single import`() {
        val t = "import os\nx = 1\n"
        assertEquals("import os\n".length, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset after multiple imports`() {
        val t = "import os\nimport sys\nfrom a import b\nx = 1\n"
        val expected = "import os\nimport sys\nfrom a import b\n".length
        assertEquals(expected, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset after leading comments`() {
        val t = "# header comment\n# coding: utf-8\nx = 1\n"
        val expected = "# header comment\n# coding: utf-8\n".length
        assertEquals(expected, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset after comments then imports`() {
        val t = "# top\nimport os\nx = 1\n"
        val expected = "# top\nimport os\n".length
        assertEquals(expected, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset whole file is header`() {
        val t = "import os\nimport sys\n"
        assertEquals(t.length, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset header with blank line before code`() {
        val t = "import os\n\nx = 1\n"
        // header boundary is committed right after the import line (blanks don't advance it)
        assertEquals("import os\n".length, ExtractionLogic.constantInsertionOffset(t))
    }

    @Test
    fun `constant offset blank lines then code at top`() {
        val t = "\n\nx = 1\n"
        // no committed header; falls back to the start of the first real statement line
        val expected = t.indexOf("x = 1")
        assertEquals(expected, ExtractionLogic.constantInsertionOffset(t))
    }

    // ------------------------------------------------------------------
    // planExtractVariable
    // ------------------------------------------------------------------

    @Test
    fun `plan extract variable top level`() {
        val t = "result = 1 + 2\n"
        val start = t.indexOf("1 + 2")
        val end = start + "1 + 2".length
        val plan = ExtractionLogic.planExtractVariable(t, start, end, "tmp")
        assertEquals(0, plan.insertOffset)
        assertEquals("tmp = 1 + 2\n", plan.insertText)
        assertEquals("tmp", plan.replaceWith)
        assertEquals(start, plan.replaceStart)
        assertEquals(end, plan.replaceEnd)
    }

    @Test
    fun `plan extract variable indented`() {
        val t = "def f():\n    return a + b\n"
        val start = t.indexOf("a + b")
        val end = start + "a + b".length
        val plan = ExtractionLogic.planExtractVariable(t, start, end, "s")
        // insert at start of the "    return..." line
        assertEquals(t.indexOf("    return"), plan.insertOffset)
        assertEquals("    s = a + b\n", plan.insertText)
    }

    // ------------------------------------------------------------------
    // planIntroduceConstant
    // ------------------------------------------------------------------

    @Test
    fun `plan introduce constant after imports`() {
        val t = "import os\nx = magic\n"
        val start = t.indexOf("magic")
        val end = start + "magic".length
        val plan = ExtractionLogic.planIntroduceConstant(t, start, end, "MAGIC")
        assertEquals("import os\n".length, plan.insertOffset)
        assertEquals("MAGIC = magic\n", plan.insertText)
        assertEquals("MAGIC", plan.replaceWith)
    }

    @Test
    fun `plan introduce constant at top adds blank separator`() {
        val t = "x = 42\n"
        val start = t.indexOf("42")
        val end = start + "42".length
        val plan = ExtractionLogic.planIntroduceConstant(t, start, end, "ANSWER")
        assertEquals(0, plan.insertOffset)
        assertEquals("ANSWER = 42\n\n", plan.insertText)
    }

    // ------------------------------------------------------------------
    // name helpers
    // ------------------------------------------------------------------

    @Test
    fun `default variable name`() {
        assertEquals("extracted", ExtractionLogic.defaultVariableName())
    }

    @Test
    fun `default constant name from identifier`() {
        assertEquals("FOO_BAR", ExtractionLogic.defaultConstantName("fooBar"))
    }

    @Test
    fun `default constant name from non identifier falls back`() {
        assertEquals("CONSTANT", ExtractionLogic.defaultConstantName("1 + 2"))
    }

    @Test
    fun `toConstantCase camel to snake`() {
        assertEquals("MAX_RETRY_COUNT", ExtractionLogic.toConstantCase("maxRetryCount"))
    }

    @Test
    fun `toConstantCase already snake`() {
        assertEquals("ALREADY_SNAKE", ExtractionLogic.toConstantCase("already_snake"))
    }

    @Test
    fun `isValidIdentifier accepts good names`() {
        assertTrue(ExtractionLogic.isValidIdentifier("foo"))
        assertTrue(ExtractionLogic.isValidIdentifier("_bar"))
        assertTrue(ExtractionLogic.isValidIdentifier("a1_b2"))
        assertTrue(ExtractionLogic.isValidIdentifier("MAX"))
    }

    @Test
    fun `isValidIdentifier rejects bad names`() {
        assertFalse(ExtractionLogic.isValidIdentifier(""))
        assertFalse(ExtractionLogic.isValidIdentifier("1foo"))
        assertFalse(ExtractionLogic.isValidIdentifier("a b"))
        assertFalse(ExtractionLogic.isValidIdentifier("a-b"))
    }
}

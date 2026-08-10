package dev.basedpython.pycharm.refactoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-logic unit tests for [ExtractMethodLogic]. No IDE fixture required.
 *
 * Many tests assert the exact generated function text, insert offset, and replacement call text by
 * computing a plan and then applying it to the source string by hand (insert + replace), which is
 * exactly what [AbstractExtractionAction] does at runtime.
 */
class ExtractMethodLogicTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Applies a plan to [text] deterministically and returns the resulting document text. */
    private fun apply(text: String, plan: ExtractMethodLogic.ExtractMethodPlan): String {
        assertTrue(plan.ok, "plan should be applicable")
        val sb = StringBuilder(text)
        // Apply higher offset first so the earlier offset stays valid.
        if (plan.insertOffset <= plan.replaceStart) {
            sb.replace(plan.replaceStart, plan.replaceEnd, plan.replacementText)
            sb.insert(plan.insertOffset, plan.insertText)
        } else {
            sb.insert(plan.insertOffset, plan.insertText)
            sb.replace(plan.replaceStart, plan.replaceEnd, plan.replacementText)
        }
        return sb.toString()
    }

    /** Selection offsets for the substring [needle] within [text]. */
    private fun sel(text: String, needle: String): Pair<Int, Int> {
        val s = text.indexOf(needle)
        require(s >= 0) { "needle not found: $needle" }
        return s to (s + needle.length)
    }

    // ------------------------------------------------------------------
    // expandToLines
    // ------------------------------------------------------------------

    @Test
    fun `expandToLines snaps partial selection to whole lines`() {
        val t = "alpha\nbeta\ngamma\n"
        // select "lph" inside "alpha"
        val (s, e) = sel(t, "lph")
        val (ls, le) = ExtractMethodLogic.expandToLines(t, s, e)
        assertEquals(0, ls)
        assertEquals(6, le) // through "alpha\n"
    }

    @Test
    fun `expandToLines zero-width selection covers caret line`() {
        val t = "alpha\nbeta\n"
        val (ls, le) = ExtractMethodLogic.expandToLines(t, 7, 7) // inside "beta"
        assertEquals(6, ls)
        assertEquals(11, le)
    }

    @Test
    fun `expandToLines does not swallow next line when end at line start`() {
        val t = "alpha\nbeta\ngamma\n"
        // select exactly "alpha\n" (end at start of "beta")
        val (ls, le) = ExtractMethodLogic.expandToLines(t, 0, 6)
        assertEquals(0, ls)
        assertEquals(6, le)
    }

    @Test
    fun `expandToLines covers multiple full lines`() {
        val t = "a\nb\nc\n"
        val (ls, le) = ExtractMethodLogic.expandToLines(t, 0, 4) // through "a\nb\n"
        assertEquals(0, ls)
        assertEquals(4, le)
    }

    @Test
    fun `expandToLines on last line without trailing newline`() {
        val t = "a\nb"
        val (ls, le) = ExtractMethodLogic.expandToLines(t, 2, 3)
        assertEquals(2, ls)
        assertEquals(3, le)
    }

    // ------------------------------------------------------------------
    // leadingIndent / indentWidth / commonIndent
    // ------------------------------------------------------------------

    @Test
    fun `leadingIndent for spaces`() {
        assertEquals("    ", ExtractMethodLogic.leadingIndent("    foo = 1"))
    }

    @Test
    fun `leadingIndent for none`() {
        assertEquals("", ExtractMethodLogic.leadingIndent("foo"))
    }

    @Test
    fun `indentWidth counts tabs as four`() {
        assertEquals(4, ExtractMethodLogic.indentWidth("\t"))
        assertEquals(6, ExtractMethodLogic.indentWidth("\t  "))
        assertEquals(8, ExtractMethodLogic.indentWidth("        "))
    }

    @Test
    fun `commonIndent of uniformly-indented lines`() {
        val lines = listOf("    a = 1", "    b = 2")
        assertEquals("    ", ExtractMethodLogic.commonIndent(lines))
    }

    @Test
    fun `commonIndent ignores blank lines`() {
        val lines = listOf("    a = 1", "", "    b = 2")
        assertEquals("    ", ExtractMethodLogic.commonIndent(lines))
    }

    @Test
    fun `commonIndent of nested block is the shallowest`() {
        val lines = listOf("    if x:", "        y = 1")
        assertEquals("    ", ExtractMethodLogic.commonIndent(lines))
    }

    @Test
    fun `commonIndent of all-blank is empty`() {
        assertEquals("", ExtractMethodLogic.commonIndent(listOf("", "   ")))
    }

    @Test
    fun `commonIndent at column zero`() {
        assertEquals("", ExtractMethodLogic.commonIndent(listOf("a = 1", "b = 2")))
    }

    // ------------------------------------------------------------------
    // enclosingDefStart
    // ------------------------------------------------------------------

    @Test
    fun `enclosingDefStart finds the wrapping def`() {
        val t = "def foo():\n    a = 1\n    b = 2\n"
        val (s, _) = sel(t, "    a = 1")
        val off = ExtractMethodLogic.enclosingDefStart(t, s, 4)
        assertEquals(0, off)
    }

    @Test
    fun `enclosingDefStart returns null at module level`() {
        val t = "a = 1\nb = 2\n"
        val (s, _) = sel(t, "a = 1")
        assertNull(ExtractMethodLogic.enclosingDefStart(t, s, 0))
    }

    @Test
    fun `enclosingDefStart picks nearest shallower def for nested methods`() {
        val t = "def outer():\n    def inner():\n        x = 1\n"
        val (s, _) = sel(t, "        x = 1")
        val off = ExtractMethodLogic.enclosingDefStart(t, s, 8)
        // inner def is at indent 4 < 8 → it is the enclosing def
        assertEquals(t.indexOf("    def inner"), off)
    }

    @Test
    fun `enclosingDefStart recognises async def`() {
        val t = "async def foo():\n    a = 1\n"
        val (s, _) = sel(t, "    a = 1")
        assertEquals(0, ExtractMethodLogic.enclosingDefStart(t, s, 4))
    }

    // ------------------------------------------------------------------
    // buildFunctionText
    // ------------------------------------------------------------------

    @Test
    fun `buildFunctionText single line de-indented and re-indented`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "extracted",
            bodyLines = listOf("    a = 1"),
            common = "    ",
            defIndent = "",
        )
        assertEquals("def extracted():\n    a = 1\n", text)
    }

    @Test
    fun `buildFunctionText preserves relative indentation`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "extracted",
            bodyLines = listOf("    if x:", "        y = 1"),
            common = "    ",
            defIndent = "",
        )
        assertEquals("def extracted():\n    if x:\n        y = 1\n", text)
    }

    @Test
    fun `buildFunctionText keeps blank lines as empty`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "f",
            bodyLines = listOf("    a = 1", "", "    b = 2"),
            common = "    ",
            defIndent = "",
        )
        assertEquals("def f():\n    a = 1\n\n    b = 2\n", text)
    }

    @Test
    fun `buildFunctionText nests under enclosing def indent`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "f",
            bodyLines = listOf("        a = 1"),
            common = "        ",
            defIndent = "    ",
        )
        assertEquals("    def f():\n        a = 1\n", text)
    }

    @Test
    fun `buildFunctionText emits pass for empty body`() {
        val text = ExtractMethodLogic.buildFunctionText("f", emptyList(), "", "")
        assertEquals("def f():\n    pass\n", text)
    }

    @Test
    fun `buildFunctionText appends trailing return`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "f",
            bodyLines = listOf("    a = 1"),
            common = "    ",
            defIndent = "",
            trailingReturn = "a",
        )
        assertEquals("def f():\n    a = 1\n    return a\n", text)
    }

    @Test
    fun `buildFunctionText at column zero body`() {
        val text = ExtractMethodLogic.buildFunctionText(
            name = "f",
            bodyLines = listOf("a = 1", "b = 2"),
            common = "",
            defIndent = "",
        )
        assertEquals("def f():\n    a = 1\n    b = 2\n", text)
    }

    // ------------------------------------------------------------------
    // trailingAssignmentTarget
    // ------------------------------------------------------------------

    @Test
    fun `trailingAssignmentTarget on simple assignment`() {
        assertEquals("result", ExtractMethodLogic.trailingAssignmentTarget(listOf("    result = a + b")))
    }

    @Test
    fun `trailingAssignmentTarget uses last non-blank line`() {
        val lines = listOf("    a = 1", "    total = a + 2", "")
        assertEquals("total", ExtractMethodLogic.trailingAssignmentTarget(lines))
    }

    @Test
    fun `trailingAssignmentTarget rejects augmented assignment`() {
        assertNull(ExtractMethodLogic.trailingAssignmentTarget(listOf("    a += 1")))
    }

    @Test
    fun `trailingAssignmentTarget rejects comparison`() {
        assertNull(ExtractMethodLogic.trailingAssignmentTarget(listOf("    a == 1")))
    }

    @Test
    fun `trailingAssignmentTarget rejects tuple target`() {
        assertNull(ExtractMethodLogic.trailingAssignmentTarget(listOf("    a, b = 1, 2")))
    }

    @Test
    fun `trailingAssignmentTarget rejects non-assignment last line`() {
        assertNull(ExtractMethodLogic.trailingAssignmentTarget(listOf("    return 1")))
    }

    @Test
    fun `trailingAssignmentTarget ignores subscript-only equals depth`() {
        // d[k] = v -> lhs "d[k]" is not a bare identifier, rejected
        assertNull(ExtractMethodLogic.trailingAssignmentTarget(listOf("    d[k] = v")))
    }

    // ------------------------------------------------------------------
    // splitKeepingStructure
    // ------------------------------------------------------------------

    @Test
    fun `splitKeepingStructure drops trailing empty from final newline`() {
        assertEquals(listOf("a", "b"), ExtractMethodLogic.splitKeepingStructure("a\nb\n"))
    }

    @Test
    fun `splitKeepingStructure keeps interior blank`() {
        assertEquals(listOf("a", "", "b"), ExtractMethodLogic.splitKeepingStructure("a\n\nb\n"))
    }

    @Test
    fun `splitKeepingStructure with no trailing newline`() {
        assertEquals(listOf("a", "b"), ExtractMethodLogic.splitKeepingStructure("a\nb"))
    }

    // ------------------------------------------------------------------
    // isValidIdentifier
    // ------------------------------------------------------------------

    @Test
    fun `isValidIdentifier accepts normal names`() {
        assertTrue(ExtractMethodLogic.isValidIdentifier("foo_bar2"))
        assertTrue(ExtractMethodLogic.isValidIdentifier("_x"))
    }

    @Test
    fun `isValidIdentifier rejects bad names`() {
        assertFalse(ExtractMethodLogic.isValidIdentifier(""))
        assertFalse(ExtractMethodLogic.isValidIdentifier("2foo"))
        assertFalse(ExtractMethodLogic.isValidIdentifier("a b"))
    }

    // ------------------------------------------------------------------
    // planExtractMethod — full end-to-end string assertions
    // ------------------------------------------------------------------

    @Test
    fun `plan single line inside a function`() {
        val t = "def foo():\n    a = 1\n    b = 2\n"
        val (s, e) = sel(t, "    a = 1\n")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "extracted")
        assertTrue(plan.ok)
        assertEquals(0, plan.insertOffset)
        assertEquals("def extracted():\n    a = 1\n\n", plan.insertText)
        assertEquals("    extracted()\n", plan.replacementText)
        assertEquals(
            "def extracted():\n    a = 1\n\ndef foo():\n    extracted()\n    b = 2\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan multi-line block inside a function`() {
        val t = "def foo():\n    a = 1\n    b = 2\n    return a + b\n"
        val s = t.indexOf("    a = 1")
        val e = t.indexOf("    return") // up to (not incl) the return line
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "compute")
        assertTrue(plan.ok)
        assertEquals(0, plan.insertOffset)
        assertEquals("def compute():\n    a = 1\n    b = 2\n\n", plan.insertText)
        assertEquals("    compute()\n", plan.replacementText)
        assertEquals(
            "def compute():\n    a = 1\n    b = 2\n\ndef foo():\n    compute()\n    return a + b\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan preserves nested indentation in body`() {
        val t = "def foo():\n    if x:\n        y = 1\n    z = 2\n"
        val s = t.indexOf("    if x:")
        val e = t.indexOf("    z = 2")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "branch")
        assertTrue(plan.ok)
        assertEquals("def branch():\n    if x:\n        y = 1\n\n", plan.insertText)
        assertEquals(
            "def branch():\n    if x:\n        y = 1\n\ndef foo():\n    branch()\n    z = 2\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan at module level inserts at top`() {
        val t = "a = 1\nb = 2\nc = 3\n"
        val (s, e) = sel(t, "a = 1\nb = 2\n")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "setup")
        assertTrue(plan.ok)
        assertEquals(0, plan.insertOffset)
        assertEquals("def setup():\n    a = 1\n    b = 2\n\n", plan.insertText)
        assertEquals("setup()\n", plan.replacementText)
        assertEquals(
            "def setup():\n    a = 1\n    b = 2\n\nsetup()\nc = 3\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan at module level inserts after import header`() {
        val t = "import os\n\nx = 1\ny = 2\n"
        val (s, e) = sel(t, "x = 1\ny = 2\n")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "work")
        assertTrue(plan.ok)
        // insertion offset is just past the import line (the import header)
        assertEquals(t.indexOf("import os\n") + "import os\n".length, plan.insertOffset)
        assertEquals("def work():\n    x = 1\n    y = 2\n\n", plan.insertText)
        assertEquals("work()\n", plan.replacementText)
        // Apply and check the import stays on top, def comes after the header.
        val result = apply(t, plan)
        assertTrue(result.startsWith("import os\n"))
        assertTrue(result.contains("def work():\n    x = 1\n    y = 2\n"))
        assertTrue(result.contains("\nwork()\n"))
    }

    @Test
    fun `plan with interior blank line in selection`() {
        val t = "def foo():\n    a = 1\n\n    b = 2\n    c = 3\n"
        val s = t.indexOf("    a = 1")
        val e = t.indexOf("    c = 3")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "pre")
        assertTrue(plan.ok)
        assertEquals("def pre():\n    a = 1\n\n    b = 2\n\n", plan.insertText)
        assertEquals(
            "def pre():\n    a = 1\n\n    b = 2\n\ndef foo():\n    pre()\n    c = 3\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan with selection at column zero inside def reindents body`() {
        // body that (unusually) sits at column 0 still gets re-indented under the new def
        val t = "x = 1\ny = 2\n"
        val (s, e) = sel(t, "x = 1\n")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "f")
        assertEquals("def f():\n    x = 1\n\n", plan.insertText)
        assertEquals("f()\n", plan.replacementText)
    }

    @Test
    fun `plan on empty document is a no-op`() {
        assertFalse(ExtractMethodLogic.planExtractMethod("", 0, 0, "f").ok)
    }

    @Test
    fun `plan blank-only selection is a no-op`() {
        val t = "def foo():\n    \n    \n    b = 2\n"
        val s = t.indexOf("    \n")
        val e = t.indexOf("    b = 2")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "f")
        assertFalse(plan.ok)
    }

    @Test
    fun `plan rejects invalid method name`() {
        val t = "def foo():\n    a = 1\n"
        val (s, e) = sel(t, "    a = 1\n")
        assertFalse(ExtractMethodLogic.planExtractMethod(t, s, e, "2bad").ok)
    }

    @Test
    fun `plan applies trailing-return heuristic when enabled`() {
        val t = "def foo():\n    a = 1\n    total = a + 2\n    print(total)\n"
        val s = t.indexOf("    a = 1")
        val e = t.indexOf("    print(total)")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "calc", addReturnHeuristic = true)
        assertTrue(plan.ok)
        assertEquals("def calc():\n    a = 1\n    total = a + 2\n    return total\n\n", plan.insertText)
        assertEquals("    total = calc()\n", plan.replacementText)
        assertEquals(
            "def calc():\n    a = 1\n    total = a + 2\n    return total\n\ndef foo():\n    total = calc()\n    print(total)\n",
            apply(t, plan),
        )
    }

    @Test
    fun `plan without heuristic does not add return`() {
        val t = "def foo():\n    total = a + 2\n    print(total)\n"
        val s = t.indexOf("    total = a + 2")
        val e = t.indexOf("    print(total)")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "calc", addReturnHeuristic = false)
        assertTrue(plan.ok)
        assertEquals("def calc():\n    total = a + 2\n\n", plan.insertText)
        assertEquals("    calc()\n", plan.replacementText)
    }

    @Test
    fun `plan heuristic no-ops when last line is not an assignment`() {
        val t = "def foo():\n    a = 1\n    print(a)\n    b = 2\n"
        val s = t.indexOf("    a = 1")
        val e = t.indexOf("    b = 2")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "f", addReturnHeuristic = true)
        assertTrue(plan.ok)
        // last selected line is print(a), no return appended
        assertEquals("def f():\n    a = 1\n    print(a)\n\n", plan.insertText)
        assertEquals("    f()\n", plan.replacementText)
    }

    @Test
    fun `plan partial selection expands to whole lines`() {
        val t = "def foo():\n    alpha = 1\n    beta = 2\n"
        // select just "lpha = 1" partial — should expand to the whole "    alpha = 1\n" line
        val s = t.indexOf("lpha = 1")
        val e = s + "lpha = 1".length
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "f")
        assertTrue(plan.ok)
        assertEquals("def f():\n    alpha = 1\n\n", plan.insertText)
        assertEquals("    f()\n", plan.replacementText)
    }

    @Test
    fun `plan nested method extracts above inner def`() {
        val t = "def outer():\n    def inner():\n        x = 1\n        y = 2\n"
        val s = t.indexOf("        x = 1")
        val e = t.indexOf("        y = 2")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "helper")
        assertTrue(plan.ok)
        // inserted at the inner def's line start, indented to match (4 spaces)
        assertEquals(t.indexOf("    def inner"), plan.insertOffset)
        assertEquals("    def helper():\n        x = 1\n\n", plan.insertText)
        assertEquals("        helper()\n", plan.replacementText)
        assertEquals(
            "def outer():\n    def helper():\n        x = 1\n\n    def inner():\n        helper()\n        y = 2\n",
            apply(t, plan),
        )
    }

    @Test
    fun `toExtractionPlan maps fields`() {
        val t = "def foo():\n    a = 1\n"
        val (s, e) = sel(t, "    a = 1\n")
        val plan = ExtractMethodLogic.planExtractMethod(t, s, e, "f")
        val ep = plan.toExtractionPlan()
        assertEquals(plan.insertOffset, ep.insertOffset)
        assertEquals(plan.insertText, ep.insertText)
        assertEquals(plan.replaceStart, ep.replaceStart)
        assertEquals(plan.replaceEnd, ep.replaceEnd)
        assertEquals(plan.replacementText, ep.replaceWith)
    }

    @Test
    fun `defaultMethodName is extracted`() {
        assertEquals("extracted", ExtractMethodLogic.defaultMethodName())
    }
}

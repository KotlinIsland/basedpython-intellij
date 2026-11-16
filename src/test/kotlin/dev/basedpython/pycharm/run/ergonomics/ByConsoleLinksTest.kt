package dev.basedpython.pycharm.run.ergonomics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding clickable file references in console output.
 *
 * Every sample here is a line a real `by` 0.0.1a9 run produced — the diagnostic, traceback and
 * pytest forms are three different programs' idea of how to report a location, and only the first
 * was recognised before.
 */
class ByConsoleLinksTest {

    private fun single(text: String): ByConsoleLink {
        val links = findConsoleLinks(text)
        assertEquals(1, links.size, "expected exactly one link in: $text (got $links)")
        return links.single()
    }

    /**
     * `by run pytest` now reports a failure at the `.by` file and line its test was transpiled
     * from, rather than at the generated `.py`: such a link is the source already, and is taken as
     * written — only a `.py` is looked up as the `.by` it came from.
     */
    @Test
    fun `a pytest failure at a by line is linked as it is written`() {
        val link = single("tests/test_shapes.by:11: AssertionError")
        assertEquals("tests/test_shapes.by", link.path)
        assertEquals(11, link.line)
        assertNull(byCounterpart(link.path))
        assertEquals("tests/test_shapes.by", byCounterpart("tests/test_shapes.py"))
    }

    @Test
    fun `a by diagnostic carries line and column`() {
        val link = single("  --> boom.by:11:11")
        assertEquals("boom.by", link.path)
        assertEquals(11, link.line)
        assertEquals(11, link.column)
    }

    /**
     * `by run`'s generated `_by_runner.py` rewrites tracebacks onto `.by` sources, so these are the
     * frames worth clicking. Matching only the colon form sent every one of them to line 1.
     */
    @Test
    fun `a traceback frame carries the line it names in prose`() {
        val link = single("""  File "/abs/path/demo.by", line 8, in total""")
        assertEquals("/abs/path/demo.by", link.path)
        assertEquals(8, link.line)
        assertNull(link.column)
    }

    /** The link should cover the path, not the surrounding `File "…", line N` prose. */
    @Test
    fun `a traceback link spans just the path`() {
        val text = """  File "/abs/demo.by", line 8, in total"""
        val link = single(text)
        assertEquals("/abs/demo.by", text.substring(link.start, link.end))
    }

    /** A quoted path must not also be reported as a bare one, or it would be linked twice. */
    @Test
    fun `a traceback path is not matched twice`() {
        assertEquals(1, findConsoleLinks("""  File "/abs/demo.by", line 8, in total""").size)
    }

    /**
     * `by` 0.0.1 writes a Windows path as `C:\...\pkg\calc.by`, with no `\\?\` in front: the drive is
     * part of the path, and the `:` after it is not the one before a line number. Written in that
     * form rather than captured, since no run here is on windows.
     */
    @Test
    fun `a windows path keeps its drive`() {
        val text = """ --> C:\Users\me\pkg\calc.by:3:5"""
        val link = single(text)
        assertEquals("""C:\Users\me\pkg\calc.by""", link.path)
        assertEquals("""C:\Users\me\pkg\calc.by:3:5""", text.substring(link.start, link.end))
        assertEquals(3, link.line)
        assertEquals(5, link.column)
    }

    /** A traceback quotes the whole path, drive and all, so it needs no help from the colon form. */
    @Test
    fun `a windows traceback frame is linked whole`() {
        val link = single("""  File "C:\Users\me\pkg\calc.by", line 8, in total""")
        assertEquals("""C:\Users\me\pkg\calc.by""", link.path)
        assertEquals(8, link.line)
    }

    /** A letter and a colon are a drive only before a separator: `x:a.by` is no path of `x:`. */
    @Test
    fun `a letter and colon before a name is not a drive`() {
        assertEquals("a.by", single("x:a.by:2").path)
    }

    @Test
    fun `a pytest failure line is found`() {
        val link = single("tests/test_math.py:8: AssertionError")
        assertEquals("tests/test_math.py", link.path)
        assertEquals(8, link.line)
    }

    @Test
    fun `a bare path with no position is still a link`() {
        val link = single("see /abs/path/foo.by for details")
        assertEquals("/abs/path/foo.by", link.path)
        assertNull(link.line)
    }

    @Test
    fun `several references on one line are all found, in order`() {
        val links = findConsoleLinks("a/one.by:3 and b/two.py:9:2")
        assertEquals(listOf("a/one.by", "b/two.py"), links.map { it.path })
        assertEquals(listOf(3, 9), links.map { it.line })
    }

    @Test
    fun `text with no file reference yields nothing`() {
        assertTrue(findConsoleLinks("Found 1 diagnostic").isEmpty())
        assertTrue(findConsoleLinks("").isEmpty())
    }

    /** pytest reports the transpiled tree; the source it came from differs only in extension. */
    @Test
    fun `a transpiled path maps to its by source`() {
        assertEquals("tests/test_math.by", byCounterpart("tests/test_math.py"))
        assertEquals("/abs/demo.by", byCounterpart("/abs/demo.py"))
    }

    /** A `.by` path is already the source — there is nothing to swap. */
    @Test
    fun `a by path has no counterpart`() {
        assertNull(byCounterpart("main.by"))
        assertNull(byCounterpart("Makefile"))
    }
}

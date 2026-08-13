package dev.basedpython.pycharm.inspections.logpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which `print` statements are offered as log points, what expression the log point gets, and which
 * line it lands on.
 *
 * The negative cases carry the weight. A log point is a breakpoint, so it can only be put on a line
 * that still runs after the fix, and it logs one expression rather than a formatted argument list —
 * offering one where neither holds produces either a breakpoint that never binds or output that
 * does not match what the `print` was writing.
 */
class PrintToLogpointTest {

    private fun only(source: String): PrintToLogpoint.Candidate? =
        PrintToLogpoint.candidates(source).singleOrNull()

    /** The source as the fix leaves it, with the log point's line marked by a leading `>`. */
    private fun applied(source: String): String {
        val candidate = checkNotNull(only(source)) { "no candidate in source" }
        val without = source.removeRange(candidate.lineStart, candidate.lineEndWithSeparator)
        return without.lines().mapIndexed { index, line ->
            if (index == candidate.logpointLine) ">$line" else " $line"
        }.joinToString("\n")
    }

    // ------------------------------------------------------------------ accepted

    @Test
    fun `print between two statements logs its argument on the next line`() {
        val source = """
            def f(x):
                print(x)
                return x * 2
        """.trimIndent()
        assertEquals("x", only(source)?.expression)
        assertEquals(" def f(x):\n>    return x * 2", applied(source))
    }

    @Test
    fun `blank lines and comments between do not move the log point off the next statement`() {
        val source = "def f(x):\n    print(x)\n\n    # why\n    return x"
        assertEquals("x", only(source)?.expression)
        assertEquals(" def f(x):\n \n     # why\n>    return x", applied(source))
    }

    @Test
    fun `an f-string argument survives whole`() {
        val source = "def f(x):\n    print(f\"x = {x} ({x!r})\")\n    return x\n"
        assertEquals("f\"x = {x} ({x!r})\"", only(source)?.expression)
    }

    @Test
    fun `several arguments are kept as written`() {
        val source = "def f(a, b):\n    print(a, b)\n    return a\n"
        assertEquals("a, b", only(source)?.expression)
    }

    @Test
    fun `a comparison inside the call is not a keyword argument`() {
        val source = "def f(a, b):\n    print(a == b)\n    return a\n"
        assertEquals("a == b", only(source)?.expression)
    }

    @Test
    fun `a keyword argument of a nested call is not the print's`() {
        val source = "def f(a):\n    print(fmt(a, width=3))\n    return a\n"
        assertEquals("fmt(a, width=3)", only(source)?.expression)
    }

    @Test
    fun `a trailing comment does not disqualify the statement`() {
        val source = "def f(a):\n    print(a)  # debug\n    return a\n"
        assertEquals("a", only(source)?.expression)
    }

    @Test
    fun `a bracket inside a string does not end the call early`() {
        val source = "def f(a):\n    print(\"a) b\" + a)\n    return a\n"
        assertEquals("\"a) b\" + a", only(source)?.expression)
    }

    @Test
    fun `the log point goes on the next statement at the same indent, not the nearest line`() {
        val source = "print(1)\n\n\nx = 2\n"
        assertEquals(3, only(source)?.followerLine)
        assertEquals(2, only(source)?.logpointLine)
    }

    // ------------------------------------------------------------------ declined

    @Test
    fun `the last statement of a block has nowhere to put the log point`() {
        // The next line runs at import time, not where the print did.
        assertNull(only("def f(x):\n    print(x)\n\nf(1)\n"))
    }

    @Test
    fun `a print at the end of the file has nowhere to put the log point`() {
        assertNull(only("x = 1\nprint(x)\n"))
    }

    @Test
    fun `print with no argument has nothing to log`() {
        assertNull(only("print()\nx = 1\n"))
    }

    @Test
    fun `keyword arguments change what print does, so they are left alone`() {
        assertNull(only("import sys\nprint(x, file=sys.stderr)\nx = 1\n"))
        assertNull(only("print(a, b, sep=\", \")\nx = 1\n"))
        assertNull(only("print(a, end=\"\")\nx = 1\n"))
    }

    @Test
    fun `a call spanning several lines is not offered`() {
        assertNull(only("print(\n    x,\n)\ny = 1\n"))
    }

    @Test
    fun `a print sharing its line with another statement is not offered`() {
        assertNull(only("print(x); y = 1\nz = 2\n"))
        assertNull(only("if x: print(x)\ny = 1\n"))
    }

    @Test
    fun `a name merely starting with print is not a print`() {
        assertNull(only("printer(x)\ny = 1\n"))
        assertNull(only("print_all(x)\ny = 1\n"))
    }

    @Test
    fun `a method named print is not the builtin`() {
        assertNull(only("logger.print(x)\ny = 1\n"))
    }

    // ------------------------------------------------------------------ lookup by offset

    @Test
    fun `at resolves the candidate the fix was offered for`() {
        val source = "def f(x):\n    print(x)\n    return x\n"
        val candidate = checkNotNull(only(source))
        assertEquals(candidate, PrintToLogpoint.at(source, candidate.callOffset))
    }

    @Test
    fun `at declines an offset that no longer names a print`() {
        val source = "def f(x):\n    return x\n"
        assertNull(PrintToLogpoint.at(source, source.indexOf("return")))
    }

    @Test
    fun `every candidate of a file is found`() {
        val source = "print(a)\nprint(b)\nx = 1\n"
        val found = PrintToLogpoint.candidates(source)
        assertEquals(listOf("a", "b"), found.map { it.expression })
        assertTrue(found.all { it.logpointLine >= 0 })
    }
}

package dev.basedpython.pycharm.inspections.logpoint

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which `print` statements are offered as log points, what expression the log point gets, and which
 * line it lands on — given `by`'s outline of the file, written out by hand.
 *
 * The negative cases carry the weight. A log point is a breakpoint, so it can only be put on a line
 * that still runs after the fix, and it logs one expression rather than a formatted argument list —
 * offering one where neither holds produces either a breakpoint that never binds or output that
 * does not match what the `print` was writing.
 */
@TestFixtures
class PrintToLogpointTest {

    private val fixture by codeInsightFixture()

    private fun candidates(source: String, outline: OutlineSpec.Suite.() -> Unit): List<PrintToLogpoint.Candidate> {
        val document = fixture.configureByText("a.by", source).viewProvider.document!!
        return PrintToLogpoint.candidates(OutlineSpec.outline(document, outline), document)
    }

    private fun only(source: String, outline: OutlineSpec.Suite.() -> Unit): PrintToLogpoint.Candidate? =
        candidates(source, outline).singleOrNull()

    /** The source as the fix leaves it, with the log point's line marked by a leading `>`. */
    private fun applied(source: String, outline: OutlineSpec.Suite.() -> Unit): String {
        val candidate = checkNotNull(only(source, outline)) { "no candidate in source" }
        val without = source.removeRange(candidate.lineStart, candidate.lineEndWithSeparator)
        return without.lines().mapIndexed { index, line ->
            if (index == candidate.logpointLine) ">$line" else " $line"
        }.joinToString("\n")
    }

    // ------------------------------------------------------------------ accepted

    @Test
    fun `print between two statements logs its argument on the next line`() = onEdt {
        val source = "def f(x):\n    print(x)\n    return x * 2"
        val outline: OutlineSpec.Suite.() -> Unit = {
            compound { clause("def f(x):") { call("print(x)"); simple("return x * 2") } }
        }
        assertEquals("x", only(source, outline)?.expression)
        assertEquals(" def f(x):\n>    return x * 2", applied(source, outline))
    }

    @Test
    fun `blank lines and comments between do not move the log point off the next statement`() = onEdt {
        val source = "def f(x):\n    print(x)\n\n    # why\n    return x"
        val outline: OutlineSpec.Suite.() -> Unit = {
            compound { clause("def f(x):") { call("print(x)"); simple("return x") } }
        }
        assertEquals(" def f(x):\n \n     # why\n>    return x", applied(source, outline))
    }

    @Test
    fun `the arguments are kept as written`() = onEdt {
        val source = "def f(a, b):\n    print(a, f\"{b!r}\")\n    return a\n"
        assertEquals(
            "a, f\"{b!r}\"",
            only(source) { compound { clause("def f(a, b):") { call("print(a, f\"{b!r}\")"); simple("return a") } } }
                ?.expression,
        )
    }

    @Test
    fun `a trailing comment does not disqualify the statement`() = onEdt {
        val source = "print(a)  # debug\nx = 1\n"
        assertEquals("a", only(source) { call("print(a)"); simple("x = 1") }?.expression)
    }

    @Test
    fun `the log point goes on the next statement, not the nearest line`() = onEdt {
        val candidate = only("print(1)\n\n\nx = 2\n") { call("print(1)"); simple("x = 2") }
        assertEquals(3, candidate?.followerLine)
        assertEquals(2, candidate?.logpointLine)
    }

    // ------------------------------------------------------------------ declined

    @Test
    fun `the last statement of a suite has nowhere to put the log point`() = onEdt {
        // The next line runs at import time, not where the print did.
        assertNull(
            only("def f(x):\n    print(x)\n\nf(1)\n") {
                compound { clause("def f(x):") { call("print(x)") } }
                call("f(1)")
            },
        )
    }

    @Test
    fun `a print at the end of the file has nowhere to put the log point`() = onEdt {
        assertNull(only("x = 1\nprint(x)\n") { simple("x = 1"); call("print(x)") })
    }

    @Test
    fun `print with no argument has nothing to log`() = onEdt {
        assertNull(only("print()\nx = 1\n") { call("print()"); simple("x = 1") })
    }

    @Test
    fun `keyword or unpacked arguments change what print does, so they are left alone`() = onEdt {
        assertNull(only("print(x, file=err)\nx = 1\n") { call("print(x, file=err)", positionalOnly = false); simple("x = 1") })
        assertNull(only("print(*xs)\nx = 1\n") { call("print(*xs)", positionalOnly = false); simple("x = 1") })
    }

    @Test
    fun `a call spanning several lines is not offered`() = onEdt {
        assertNull(only("print(\n    x,\n)\ny = 1\n") { call("print(\n    x,\n)"); simple("y = 1") })
    }

    @Test
    fun `a print sharing its line with another statement is not offered`() = onEdt {
        assertNull(only("print(x); y = 1\nz = 2\n") { call("print(x)"); simple("y = 1"); simple("z = 2") })
        assertNull(
            only("if x: print(x)\ny = 1\n") {
                compound { clause("if x:") { call("print(x)") } }
                simple("y = 1")
            },
        )
    }

    @Test
    fun `a call to anything but print is not a print`() = onEdt {
        assertNull(only("printer(x)\ny = 1\n") { call("printer(x)"); simple("y = 1") })
        assertNull(only("logger.print(x)\ny = 1\n") { call("logger.print(x)"); simple("y = 1") })
    }

    // ------------------------------------------------------------------ lookup by offset

    @Test
    fun `at resolves the candidate the fix was offered for, and nothing else`() = onEdt {
        val source = "def f(x):\n    print(x)\n    return x\n"
        val document = fixture.configureByText("a.by", source).viewProvider.document!!
        val outline = OutlineSpec.outline(document) {
            compound { clause("def f(x):") { call("print(x)"); simple("return x") } }
        }
        val candidate = PrintToLogpoint.candidates(outline, document).single()
        assertEquals(candidate, PrintToLogpoint.at(outline, document, candidate.callOffset))
        assertNull(PrintToLogpoint.at(outline, document, source.indexOf("return")))
    }

    @Test
    fun `every candidate of a file is found`() = onEdt {
        val found = candidates("print(a)\nprint(b)\nx = 1\n") { call("print(a)"); call("print(b)"); simple("x = 1") }
        assertEquals(listOf("a", "b"), found.map { it.expression })
    }
}

package dev.basedpython.pycharm.debug

import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerUtil
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which lines of a `.by` file take a line breakpoint.
 *
 * Every line used to, and a breakpoint on a blank line inside a function was moved by the debugger
 * to the module-level `def` below it, so it stopped at import time.
 */
@TestFixtures
class ByLineBreakpointTypeTest {

    private val fixture by codeInsightFixture()

    private fun breakableLines(text: String): List<Int> {
        fixture.configureByText("main.by", text)
        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!
        val file = fixture.file.virtualFile
        return (0 until fixture.editor.document.lineCount).filter { type.canPutAt(file, it, fixture.project) }
    }

    @Test
    fun `blank lines, comment lines and the inside of strings take no breakpoint`() = onEdt {
        val lines = breakableLines(
            """
            |import math
            |
            |# a comment
            |def area(r):
            |    ""${'"'}The area.
            |
            |    Of a circle.""${'"'}
            |    x = ""${'"'}
            |    text
            |    ""${'"'}
            |
            |    return math.pi * r ** 2  # squared
            |
            |
            |def total(shapes):
            |    return sum(
            |        area(s) for s in shapes
            |    )
            |""".trimMargin(),
        )
        assertEquals(listOf(0, 3, 7, 11, 14, 15, 16, 17), lines)
    }

    @Test
    fun `the rest of a backslash-continued statement takes no breakpoint`() = onEdt {
        assertEquals(listOf(0, 2), breakableLines("x = 1 + \\\n    2\ny = x\n"))
    }
}

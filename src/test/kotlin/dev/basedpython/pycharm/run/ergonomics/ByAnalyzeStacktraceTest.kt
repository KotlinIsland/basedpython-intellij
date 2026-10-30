package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.impl.ConsoleViewUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.unscramble.AnalyzeStacktraceUtil
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * *Code | Analyze Stack Trace* links a `.by` traceback through [ByConsoleFilter] with nothing
 * registered for it: its console is an ordinary one, and every console takes each
 * `consoleFilterProvider`'s filters. Measured in PyCharm 263.7649: a pasted traceback's frames,
 * `by`'s `path:line:col` and pytest's `tests/test_shapes.py:8` (opening the `.by`) were all linked.
 * An `analyzeStacktraceFilter` of the plugin's as well would put a second link on each of them.
 */
@TestFixtures
class ByAnalyzeStacktraceTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `every console, Analyze Stack Trace's included, takes the console filter, and no stack trace filter of ours is added`() = onEdt {
        val project = fixture.project
        val filters = ConsoleViewUtil.computeConsoleFilters(project, null, GlobalSearchScope.allScope(project))
        assertEquals(1, filters.count { it is ByConsoleFilter })
        assertEquals(0, AnalyzeStacktraceUtil.EP_NAME.getExtensions(project).count { it.javaClass.name.startsWith("dev.basedpython.") })
    }
}

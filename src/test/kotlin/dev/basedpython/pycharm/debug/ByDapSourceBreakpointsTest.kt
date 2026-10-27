package dev.basedpython.pycharm.debug

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Which `.by` breakpoints the adapter is given as DAP log points: only those that do nothing but log
 * one expression it can interpolate. Everything else stops, so the IDE can print what the breakpoint
 * asks for and decide whether to go on.
 */
@TestFixtures
class ByDapSourceBreakpointsTest {

    private val fixture by codeInsightFixture()

    /** The light project outlives the test; its breakpoints must not. */
    @AfterEach
    fun removeBreakpoints() = WriteAction.runAndWait<RuntimeException> {
        val manager = XDebuggerManager.getInstance(fixture.project).breakpointManager
        manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().forEach(manager::removeBreakpoint)
    }

    private fun logPoint(expression: String?, configure: XLineBreakpoint<ByBreakpointProperties>.() -> Unit = {}): XLineBreakpoint<ByBreakpointProperties> {
        val file = fixture.configureByText("main.by", "def main():\n    t = 1\n    print(t)\n").virtualFile
        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)
        return WriteAction.computeAndWait<XLineBreakpoint<ByBreakpointProperties>, RuntimeException> {
            XDebuggerManager.getInstance(fixture.project).breakpointManager
                .addLineBreakpoint(type, file.url, 2, ByBreakpointProperties())
                .apply {
                    suspendPolicy = SuspendPolicy.NONE
                    if (expression != null) logExpressionObject = XDebuggerUtil.getInstance().createExpression(expression, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)
                    configure()
                }
        }
    }

    @Test
    fun `a breakpoint that only logs an expression is the adapter's log point`() = onEdt {
        assertEquals("{t}", adapterLogMessage(logPoint("t")))
    }

    @Test
    fun `logging the hit or the stack as well stops for the IDE to log`() = onEdt {
        assertNull(adapterLogMessage(logPoint("t") { isLogMessage = true }))
        assertNull(adapterLogMessage(logPoint("t") { isLogStack = true }))
    }

    @Test
    fun `a breakpoint that suspends or goes once hit stops`() = onEdt {
        assertNull(adapterLogMessage(logPoint("t") { suspendPolicy = SuspendPolicy.THREAD }))
        assertNull(adapterLogMessage(logPoint("t") { isTemporary = true }))
    }

    @Test
    fun `an expression a log message cannot hold whole is logged by the IDE`() = onEdt {
        assertNull(adapterLogMessage(logPoint("{'t': t}")))
        assertNull(adapterLogMessage(logPoint("t +\n1")))
        assertNull(adapterLogMessage(logPoint(null)))
    }
}

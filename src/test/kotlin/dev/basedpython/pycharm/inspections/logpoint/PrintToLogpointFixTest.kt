package dev.basedpython.pycharm.inspections.logpoint

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the quick fix leaves behind, in a real editor: the call gone, and a breakpoint that logs and
 * does not suspend, placed *between* lines rather than on one.
 *
 * The placement is the part worth pinning down. `INTER_LINE` is what makes the result read as a swap
 * — the platform anchors such a breakpoint to the line below the gap and paints it in the gap, so it
 * lands exactly where the deleted call was. An `ON_LINE` breakpoint binds to the same line and logs
 * at the same moment, and looks like the log point wandered onto the next statement.
 *
 * The inspection is run directly rather than through `getAllQuickFixes()`, which would run the whole
 * daemon: line markers on a `.by` file ask `ByTestNodeService` what pytest collected, and the state
 * change that provokes restarts the daemon from under the highlighting pass the fixture asserts is
 * undisturbed. Nothing to do with this fix, and not worth a daemon in the loop to test it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class PrintToLogpointFixTest {

    private val fixture by codeInsightFixture()

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    /** Reports on [source], applies the single fix, and returns the breakpoint it created. */
    private fun applyFix(source: String): XLineBreakpoint<*> {
        fixture.configureByText("main.by", source)
        val descriptor = problems(source).single()
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            (descriptor.fixes!!.single() as LocalQuickFix).applyFix(fixture.project, descriptor)
        }
        return XDebuggerManager.getInstance(fixture.project).breakpointManager.getBreakpoints(type).single()
    }

    private fun problems(@Suppress("UNUSED_PARAMETER") source: String): Array<ProblemDescriptor> =
        PrintToLogpointInspection()
            .checkFile(fixture.file, InspectionManager.getInstance(fixture.project), false)

    /**
     * Waits out the platform's own reaction to the document this test edited.
     *
     * Every test here changes a document, and the platform hashes changed content on a shared
     * coroutine dispatcher — where the first hash of the JVM run also loads a native xxhash
     * library. The fixture's thread-leak check runs immediately after each test and fails on any
     * pool thread still RUNNABLE, so whichever test edits a document first can fail for the
     * platform's lazy initialisation rather than for anything it asserted. It is a race, and one
     * that unrelated work tips over: adding a second tool window to plugin.xml was enough to make
     * it land inside this test's window every time.
     *
     * Waiting for the coroutine's own name to leave the worker thread is what makes that
     * deterministic without touching the leak check itself.
     */
    @AfterEach
    fun letContentHashingFinish() {
        val deadline = System.currentTimeMillis() + HASH_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline && isHashing()) {
            Thread.sleep(POLL_MILLIS)
        }
    }

    /** True while a pool thread is running the platform's content-hashing coroutine. */
    private fun isHashing(): Boolean = Thread.getAllStackTraces().keys.any {
        it.isAlive && it.state == Thread.State.RUNNABLE && it.name.contains(HASHING_COROUTINE)
    }

    @Test
    fun `the call is gone and the log point sits between the lines it left`() {
        val breakpoint = applyFix("def f(x):\n    print(x)\n    return x * 2\n")

        assertEquals("def f(x):\n    return x * 2\n", fixture.editor.document.text)
        assertEquals(XLineBreakpointVerticalPlacement.INTER_LINE, breakpoint.placement)
        // Line 1 is `return x * 2`; the gap above it is where the print was.
        assertEquals(1, breakpoint.line)
    }

    @Test
    fun `the log point logs the argument and does not suspend`() {
        val breakpoint = applyFix("def f(x):\n    print(f\"x={x}\")\n    return x\n")

        assertEquals(SuspendPolicy.NONE, breakpoint.suspendPolicy)
        val logged = breakpoint.logExpressionObject
        assertNotNull(logged, "expected a log expression")
        assertEquals("f\"x={x}\"", logged!!.expression)
    }

    @Test
    fun `no fix is offered where the log point would have nowhere to bind`() {
        // The print is the last statement of the function; the next line runs at import time.
        fixture.configureByText("main.by", "def f(x):\n    print(x)\n\nf(1)\n")
        assertTrue(problems("").isEmpty(), "expected no report for a print at the end of its block")
    }

    private companion object {
        /** The coroutine whose name a worker thread carries while it hashes changed content. */
        const val HASHING_COROUTINE = "ProvenanceEvents"

        /** Long enough for a native library load on a cold, loaded machine; short of a hung build. */
        const val HASH_TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 20L
    }
}

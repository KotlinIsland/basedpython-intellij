package dev.basedpython.pycharm.inspections.logpoint

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.letContentHashingFinish
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

    /** See [letContentHashingFinish]: every test here edits a document, and the platform notices. */
    @AfterEach
    fun letTheEditSettle() = letContentHashingFinish()

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
    fun `undo takes the log point back along with the deleted line`() {
        applyFix("def f(x):\n    print(x)\n    return x * 2\n")
        assertTrue(
            XDebuggerManager.getInstance(fixture.project).breakpointManager.getBreakpoints(type).isNotEmpty(),
        )

        UndoManager.getInstance(fixture.project)
            .undo(FileEditorManager.getInstance(fixture.project).getSelectedEditor(fixture.file.virtualFile))

        assertEquals("def f(x):\n    print(x)\n    return x * 2\n", fixture.editor.document.text)
        assertTrue(
            XDebuggerManager.getInstance(fixture.project).breakpointManager.getBreakpoints(type).isEmpty(),
            "undo restoring the print while leaving the log point would log the value twice",
        )
    }

    @Test
    fun `no fix is offered where the log point would have nowhere to bind`() {
        // The print is the last statement of the function; the next line runs at import time.
        fixture.configureByText("main.by", "def f(x):\n    print(x)\n\nf(1)\n")
        assertTrue(problems("").isEmpty(), "expected no report for a print at the end of its block")
    }
}

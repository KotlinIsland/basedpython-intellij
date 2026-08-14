package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import com.intellij.xdebugger.evaluation.EvaluationMode
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The inline "Log:" field, driven the way the keyboard drives it.
 *
 * The gesture it completes is a click in the gutter gap, which creates a log point with nothing to
 * log; everything here is about what happens between that and the log point being worth keeping.
 * Escape on an untouched one takes the breakpoint away with it, because an empty log point does
 * nothing at all and the only sign of it is an icon.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByLogpointInlineEditorTest {

    private val fixture by codeInsightFixture()

    private val breakpoints get() = XDebuggerManager.getInstance(fixture.project).breakpointManager

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    /** A file with an editor, and an unfilled log point in the gap above [line] — what a gutter click leaves. */
    private fun logpointAt(line: Int, source: String = "def f(x):\n    return x\n"): XLineBreakpoint<*> {
        fixture.configureByText("main.by", source)
        val info = XLineBreakpointAdditionalInfo.Builder()
            .setVerticalPlacement(XLineBreakpointVerticalPlacement.INTER_LINE)
            .setSuspendPolicy(SuspendPolicy.NONE)
            .build()
        return breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, line, null, info)
    }

    private fun editor() = fixture.editor as EditorEx

    private fun type(prompt: ByLogpointInlineEditor, text: String) {
        prompt.expressionEditor.expression = XDebuggerUtil.getInstance()
            .createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)
    }

    @Test
    fun `the field opens in the gap above the log point`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)
        assertNotNull(prompt, "expected the inline field to open")

        val inlays = editor().inlayModel.getBlockElementsInRange(0, editor().document.textLength)
        assertTrue(inlays.isNotEmpty(), "expected a block inlay hosting the field")
        // Anchored to the start of the log point's own line; the platform draws it above.
        assertEquals(editor().document.getLineStartOffset(1), inlays.single().offset)

        Disposer.dispose(prompt!!)
    }

    @Test
    fun `committing writes the expression onto the log point and closes`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        type(prompt, "x * 2")
        prompt.commit()

        assertEquals("x * 2", logpoint.logExpressionObject?.expression)
        assertTrue(breakpoints.getBreakpoints(type).contains(logpoint), "the log point should survive")
        assertTrue(
            editor().inlayModel.getBlockElementsInRange(0, editor().document.textLength).isEmpty(),
            "the field should be gone once committed",
        )
    }

    @Test
    fun `focus churn on opening does not take the log point away`() {
        // The click that creates a log point is a gutter click, and the editor takes focus back as
        // it finishes — so the field is told it lost focus before it ever had it. Committing nothing
        // removes the log point, which is why it used to appear for one frame and vanish.
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        prompt.focusLost(movedWithinTheField = false)

        assertTrue(
            breakpoints.getBreakpoints(type).contains(logpoint),
            "a focus-lost before the field was ever focused is churn, not the user leaving",
        )
        assertTrue(
            editor().inlayModel.getBlockElementsInRange(0, editor().document.textLength).isNotEmpty(),
            "the field should still be open",
        )
    }

    @Test
    fun `leaving the field after using it commits, and an empty one still goes`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        prompt.focusGained()
        prompt.focusLost(movedWithinTheField = false)

        assertTrue(breakpoints.getBreakpoints(type).isEmpty(), "an abandoned empty log point goes")
    }

    @Test
    fun `leaving the field after typing keeps what was typed`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        prompt.focusGained()
        type(prompt, "x")
        prompt.focusLost(movedWithinTheField = false)

        assertEquals("x", logpoint.logExpressionObject?.expression)
        assertTrue(breakpoints.getBreakpoints(type).contains(logpoint))
    }

    @Test
    fun `escaping an untouched log point takes it away`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        prompt.cancel()

        assertTrue(breakpoints.getBreakpoints(type).isEmpty(), "an empty log point should not be left behind")
    }

    @Test
    fun `committing nothing is the same as escaping`() {
        val logpoint = logpointAt(1)
        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!

        type(prompt, "   ")
        prompt.commit()

        assertTrue(breakpoints.getBreakpoints(type).isEmpty(), "whitespace is not something to log")
    }

    @Test
    fun `an existing expression is offered for editing and kept when abandoned`() {
        val logpoint = logpointAt(1)
        logpoint.logExpressionObject = XDebuggerUtil.getInstance()
            .createExpression("first", BasedPythonLanguage, null, EvaluationMode.EXPRESSION)

        val prompt = ByLogpointInlineEditor.show(fixture.project, editor(), logpoint)!!
        assertEquals("first", prompt.expressionEditor.expression?.expression)

        prompt.cancel()

        assertEquals("first", logpoint.logExpressionObject?.expression)
        assertTrue(breakpoints.getBreakpoints(type).contains(logpoint), "an established log point survives Escape")
    }

    @Test
    fun `a line the document does not have gets no field`() {
        val logpoint = logpointAt(1)
        fixture.configureByText("other.by", "x = 1\n")
        assertNull(ByLogpointInlineEditor.show(fixture.project, editor(), logpointBeyond(logpoint)))
    }

    /** The same log point, moved past the end of the freshly configured document. */
    private fun logpointBeyond(logpoint: XLineBreakpoint<*>): XLineBreakpoint<*> {
        breakpoints.removeBreakpoint(logpoint)
        val info = XLineBreakpointAdditionalInfo.Builder()
            .setVerticalPlacement(XLineBreakpointVerticalPlacement.INTER_LINE)
            .setSuspendPolicy(SuspendPolicy.NONE)
            .build()
        return breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 99, null, info)
    }
}

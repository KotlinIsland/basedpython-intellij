package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.letContentHashingFinish
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import java.awt.event.FocusEvent
import javax.swing.JComponent

/**
 * The `Log:` box, which is how a log point looks rather than a prompt that opens once.
 *
 * The distinction is the whole of what "there is no log point UI" meant: a log point made by the
 * `print` quick fix never went through a prompt, so it was a yellow dot doing something unstated.
 * A box that is always there also means nothing here has to remove a breakpoint to tidy up after
 * itself, which is what every focus bug in this feature came from.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByLogpointFieldTest {

    private val fixture by codeInsightFixture()

    private val breakpoints get() = XDebuggerManager.getInstance(fixture.project).breakpointManager

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    private fun logpointAt(line: Int, expression: String? = null): XLineBreakpoint<*> {
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val info = PlatformLogpointInfo.of(
            SuspendPolicy.NONE,
            expression?.let { ByLogpoints.expressionOf(it) },
        )
        return breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, line, null, info)
    }

    /** See [letContentHashingFinish]: the re-indent test edits a document, and the platform notices. */
    @AfterEach
    fun letTheEditSettle() = letContentHashingFinish()

    private fun editor() = fixture.editor as EditorEx

    private fun inlays() = editor().inlayModel.getBlockElementsInRange(0, editor().document.textLength)

    private fun type(field: ByLogpointField, text: String) {
        field.expressionEditor.expression = XDebuggerUtil.getInstance()
            .createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)
    }

    @Test
    fun `a log point that already has an expression still gets a box`() {
        // The print quick fix supplies the expression itself, so this log point never sees a prompt.
        val logpoint = logpointAt(1, expression = "x * 2")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)

        assertNotNull(field, "every log point shows its expression, however it was made")
        assertEquals("x * 2", field!!.expressionEditor.expression?.expression)
        assertTrue(inlays().isNotEmpty())
    }

    @Test
    fun `committing writes the expression and leaves the box in place`() {
        val logpoint = logpointAt(1)
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!

        type(field, "x")
        field.commit()

        assertEquals("x", logpoint.logExpressionObject?.expression)
        assertTrue(inlays().isNotEmpty(), "the box is how a log point looks, not a prompt that closes")
    }

    @Test
    fun `nothing the box does removes the log point`() {
        val logpoint = logpointAt(1)
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!

        field.commit()
        field.revert()

        assertTrue(
            breakpoints.getBreakpoints(type).contains(logpoint),
            "an empty log point is visible now, so there is nothing to tidy away",
        )
    }

    @Test
    fun `reverting puts back what the log point says`() {
        val logpoint = logpointAt(1, expression = "kept")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!

        type(field, "abandoned")
        field.revert()

        assertEquals("kept", field.expressionEditor.expression?.expression)
        assertEquals("kept", logpoint.logExpressionObject?.expression)
    }

    @Test
    fun `a reopened editor gets its own box rather than the closed one`() {
        // A breakpoint outlives every editor showing it. Parking the box on the breakpoint meant a
        // reopened tab found a disposed one still attached and drew nothing but the gutter icon.
        val logpoint = logpointAt(1, expression = "x")
        val first = ByLogpointField.show(fixture.project, editor(), logpoint)!!
        first.close()

        val second = ByLogpointField.show(fixture.project, editor(), logpoint)

        assertNotNull(second, "the box has to come back with the editor")
        assertTrue(first !== second)
        assertTrue(inlays().isNotEmpty())
    }

    @Test
    fun `asking twice returns the same box rather than stacking another`() {
        val logpoint = logpointAt(1)
        val first = ByLogpointField.show(fixture.project, editor(), logpoint)
        val second = ByLogpointField.show(fixture.project, editor(), logpoint)

        assertTrue(first === second)
        assertEquals(1, inlays().size)
    }

    @Test
    fun `closing the box takes the inlay with it`() {
        val logpoint = logpointAt(1)
        ByLogpointField.show(fixture.project, editor(), logpoint)!!.close()

        assertTrue(inlays().isEmpty())
        assertNull(ByLogpointField.of(editor(), logpoint))
    }

    @Test
    fun `a line the document does not have gets no box`() {
        val logpoint = logpointAt(99)
        assertNull(ByLogpointField.show(fixture.project, editor(), logpoint))
    }

    @Test
    fun `the box starts where the code it logs starts`() {
        // A block inlay is drawn at the left edge of the text whatever offset it is anchored to, so
        // the box on an indented statement used to sit under the `def` of the line above it.
        val logpoint = logpointAt(1, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!

        layOut(field.indented)

        val code = editor().offsetToXY(editor().document.text.indexOf("return")).x
        assertTrue(code > 0, "this fixture's `return` is indented, so it cannot start at the gutter")
        assertEquals(code, field.component.x, "the box has to line up with the statement it logs")
    }

    @Test
    fun `a box on an unindented line starts at the gutter`() {
        val logpoint = logpointAt(0, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!

        layOut(field.indented)

        assertEquals(0, field.component.x, "there is nothing to indent past on the first line")
    }

    @Test
    fun `re-indenting the line takes the box with it`() {
        // The indentation is measured at layout, not remembered from when the box was made — which
        // is what the document listener that asks for that layout is for.
        val logpoint = logpointAt(1, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!
        layOut(field.indented)
        val before = field.component.x

        val document = editor().document
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            document.insertString(document.getLineStartOffset(1), "    ")
        }
        layOut(field.indented)

        assertTrue(
            field.component.x > before,
            "the box was left at column ${'$'}before while the statement moved to ${'$'}{field.component.x}",
        )
    }

    @Test
    fun `the box takes the file's caret while it has focus, and gives it back`() {
        // Two carets is what the file showed otherwise. EditorImpl.focusGained activates its caret
        // and focusLost only stops the blink — it never passivates — and this box is a component
        // inlay, so the editor does not even count itself unfocused while the box has the keyboard.
        val logpoint = logpointAt(1, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!
        editor().setCaretVisible(true)

        focus(field, gained = true)
        assertFalse(editor().setCaretVisible(false), "the file's caret has to go while the box has one")
        editor().setCaretVisible(false)

        focus(field, gained = false)
        assertTrue(editor().setCaretVisible(true), "the file gets its caret back when the box lets go")
    }

    @Test
    fun `a file with no caret showing does not gain one from being logged`() {
        val logpoint = logpointAt(1, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!
        editor().setCaretVisible(false)

        focus(field, gained = true)
        focus(field, gained = false)

        assertFalse(editor().setCaretVisible(false), "there was no caret to put back")
    }

    @Test
    fun `the caption is the log point's own colour while the box has focus`() {
        val logpoint = logpointAt(1, expression = "x")
        val field = ByLogpointField.show(fixture.project, editor(), logpoint)!!
        val caption = field.component.components.first { it is JBLabel } as JBLabel
        val resting = caption.foreground

        focus(field, gained = true)
        val active = caption.foreground
        focus(field, gained = false)

        assertNotEquals(resting.rgb, active.rgb, "a focused Log: caption is yellow, not grey")
        assertEquals(resting.rgb, caption.foreground.rgb, "and grey again once the box lets go")
    }

    /**
     * Runs the box's own focus handling, which is what a click into it reaches.
     *
     * Through `EditorTextField`'s own `focusGained`/`focusLost` rather than by dispatching a
     * `FocusEvent`: dispatching one goes through the keyboard focus manager, which in a test with no
     * window swallows it. These two are the same methods the field's inner editor calls when focus
     * really moves — the field keeps its listeners in a list of its own rather than AWT's, which is
     * why `getFocusListeners()` comes back empty here.
     */
    private fun focus(field: ByLogpointField, gained: Boolean) {
        val component = field.expressionEditor.editorComponent as EditorTextField
        val event = FocusEvent(component, if (gained) FocusEvent.FOCUS_GAINED else FocusEvent.FOCUS_LOST)
        if (gained) component.focusGained(event) else component.focusLost(event)
    }

    /** Lays out a holder that was never added to a window, which is what `validate` needs a peer for. */
    private fun layOut(component: JComponent) {
        val size = component.preferredSize
        component.setBounds(0, 0, size.width, size.height)
        component.doLayout()
    }
}

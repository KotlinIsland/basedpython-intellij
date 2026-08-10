package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end tests that drive the inline document mutation through a real editor + document,
 * mirroring exactly what [InlineVariableAction] does inside its write command.
 *
 * We replicate the (deterministic) apply step here using the pure [InlineLogic] plan; this is the
 * only document-mutating code path the action runs. Action enable/disable is exercised separately
 * via `update()` (we never call `actionPerformed`, which can pop a modal info dialog in headless).
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class InlineVariableEndToEndTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    /** Applies the inline plan for the identifier at the editor caret. */
    private fun applyInlineAtCaret() {
        val editor = fixture.editor
        val text = editor.document.charsSequence.toString()
        val plan = InlineLogic.planInline(text, editor.caretModel.offset) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            for (edit in plan.toEdits()) {
                editor.document.replaceString(edit.start, edit.end, edit.replacement)
            }
        }
    }

    private fun updatePresentation(): Presentation {
        val action = InlineVariableAction()
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.EDITOR, fixture.editor)
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, fixture.file!!.virtualFile)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation
    }

    // ------------------------------------------------------------------
    // Apply behaviour
    // ------------------------------------------------------------------

    @Test
    fun `inline single usage compound rhs`() {
        fixture.configureByText("a.by", "x = a + b\nresult = <caret>x * 2\n")
        // caret is on the usage of x; identifierAt resolves it
        applyInlineAtCaret()
        fixture.checkResult("result = (a + b) * 2\n")
    }

    @Test
    fun `inline from definition caret`() {
        fixture.configureByText("a.by", "<caret>x = a + b\ny = x\n")
        applyInlineAtCaret()
        fixture.checkResult("y = (a + b)\n")
    }

    @Test
    fun `inline multiple usages`() {
        fixture.configureByText("a.by", "x = a + b\ny = <caret>x + x\n")
        applyInlineAtCaret()
        fixture.checkResult("y = (a + b) + (a + b)\n")
    }

    @Test
    fun `inline atomic rhs not parenthesized`() {
        fixture.configureByText("a.by", "x = foo\ny = <caret>x\n")
        applyInlineAtCaret()
        fixture.checkResult("y = foo\n")
    }

    @Test
    fun `inline inside function preserves indentation`() {
        fixture.configureByText("a.by", "def f():\n    x = a + b\n    return <caret>x\n")
        applyInlineAtCaret()
        fixture.checkResult("def f():\n    return (a + b)\n")
    }

    @Test
    fun `inline tab indentation`() {
        fixture.configureByText("a.by", "def f():\n\tx = a + b\n\treturn <caret>x\n")
        applyInlineAtCaret()
        fixture.checkResult("def f():\n\treturn (a + b)\n")
    }

    @Test
    fun `inline does not touch similar names`() {
        fixture.configureByText("a.by", "x = 1\ny = <caret>x + xs + x_y + x\n")
        applyInlineAtCaret()
        fixture.checkResult("y = 1 + xs + x_y + 1\n")
    }

    @Test
    fun `inline no trailing newline`() {
        fixture.configureByText("a.by", "x = a + b\ny = <caret>x")
        applyInlineAtCaret()
        fixture.checkResult("y = (a + b)")
    }

    @Test
    fun `inline call expression parenthesized`() {
        fixture.configureByText("a.by", "x = foo(1)\ny = <caret>x + 2\n")
        applyInlineAtCaret()
        fixture.checkResult("y = (foo(1)) + 2\n")
    }

    @Test
    fun `multiple assignments leaves text unchanged`() {
        fixture.configureByText("a.by", "x = 1\nx = 2\ny = <caret>x\n")
        applyInlineAtCaret()
        fixture.checkResult("x = 1\nx = 2\ny = x\n")
    }

    @Test
    fun `no usages leaves text unchanged`() {
        fixture.configureByText("a.by", "<caret>x = a + b\ny = 1\n")
        applyInlineAtCaret()
        fixture.checkResult("x = a + b\ny = 1\n")
    }

    // ------------------------------------------------------------------
    // Action update() enable/disable
    // ------------------------------------------------------------------

    @Test
    fun `action enabled on identifier in by file`() {
        fixture.configureByText("a.by", "x = 1\ny = <caret>x\n")
        assertTrue(updatePresentation().isEnabled, "enabled on identifier")
    }

    @Test
    fun `action disabled on whitespace`() {
        fixture.configureByText("a.by", "x = 1\n<caret> y = x\n")
        assertFalse(updatePresentation().isEnabled, "disabled when caret not on identifier")
    }

    @Test
    fun `action disabled on non-by file`() {
        fixture.configureByText("a.txt", "x = 1\ny = <caret>x\n")
        assertFalse(updatePresentation().isEnabled, "disabled on non-.by file")
    }

    @Test
    fun `action visible on by file`() {
        fixture.configureByText("a.by", "x = 1\ny = <caret>x\n")
        assertTrue(updatePresentation().isVisible, "visible on .by file")
    }
}

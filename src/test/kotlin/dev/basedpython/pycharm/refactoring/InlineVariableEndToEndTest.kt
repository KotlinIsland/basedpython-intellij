package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * End-to-end tests that drive the inline document mutation through a real editor + document,
 * mirroring exactly what [InlineVariableAction] does inside its write command.
 *
 * We replicate the (deterministic) apply step here using the pure [InlineLogic] plan; this is the
 * only document-mutating code path the action runs. Action enable/disable is exercised separately
 * via `update()` (we never call `actionPerformed`, which can pop a modal info dialog in headless).
 */
class InlineVariableEndToEndTest : BasePlatformTestCase() {

    /** Applies the inline plan for the identifier at the editor caret. */
    private fun applyInlineAtCaret() {
        val editor = myFixture.editor
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
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file!!.virtualFile)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation
    }

    // ------------------------------------------------------------------
    // Apply behaviour
    // ------------------------------------------------------------------

    fun `test inline single usage compound rhs`() {
        myFixture.configureByText("a.by", "x = a + b\nresult = <caret>x * 2\n")
        // caret is on the usage of x; identifierAt resolves it
        applyInlineAtCaret()
        myFixture.checkResult("result = (a + b) * 2\n")
    }

    fun `test inline from definition caret`() {
        myFixture.configureByText("a.by", "<caret>x = a + b\ny = x\n")
        applyInlineAtCaret()
        myFixture.checkResult("y = (a + b)\n")
    }

    fun `test inline multiple usages`() {
        myFixture.configureByText("a.by", "x = a + b\ny = <caret>x + x\n")
        applyInlineAtCaret()
        myFixture.checkResult("y = (a + b) + (a + b)\n")
    }

    fun `test inline atomic rhs not parenthesized`() {
        myFixture.configureByText("a.by", "x = foo\ny = <caret>x\n")
        applyInlineAtCaret()
        myFixture.checkResult("y = foo\n")
    }

    fun `test inline inside function preserves indentation`() {
        myFixture.configureByText("a.by", "def f():\n    x = a + b\n    return <caret>x\n")
        applyInlineAtCaret()
        myFixture.checkResult("def f():\n    return (a + b)\n")
    }

    fun `test inline tab indentation`() {
        myFixture.configureByText("a.by", "def f():\n\tx = a + b\n\treturn <caret>x\n")
        applyInlineAtCaret()
        myFixture.checkResult("def f():\n\treturn (a + b)\n")
    }

    fun `test inline does not touch similar names`() {
        myFixture.configureByText("a.by", "x = 1\ny = <caret>x + xs + x_y + x\n")
        applyInlineAtCaret()
        myFixture.checkResult("y = 1 + xs + x_y + 1\n")
    }

    fun `test inline no trailing newline`() {
        myFixture.configureByText("a.by", "x = a + b\ny = <caret>x")
        applyInlineAtCaret()
        myFixture.checkResult("y = (a + b)")
    }

    fun `test inline call expression parenthesized`() {
        myFixture.configureByText("a.by", "x = foo(1)\ny = <caret>x + 2\n")
        applyInlineAtCaret()
        myFixture.checkResult("y = (foo(1)) + 2\n")
    }

    fun `test multiple assignments leaves text unchanged`() {
        myFixture.configureByText("a.by", "x = 1\nx = 2\ny = <caret>x\n")
        applyInlineAtCaret()
        myFixture.checkResult("x = 1\nx = 2\ny = x\n")
    }

    fun `test no usages leaves text unchanged`() {
        myFixture.configureByText("a.by", "<caret>x = a + b\ny = 1\n")
        applyInlineAtCaret()
        myFixture.checkResult("x = a + b\ny = 1\n")
    }

    // ------------------------------------------------------------------
    // Action update() enable/disable
    // ------------------------------------------------------------------

    fun `test action enabled on identifier in by file`() {
        myFixture.configureByText("a.by", "x = 1\ny = <caret>x\n")
        assertTrue("enabled on identifier", updatePresentation().isEnabled)
    }

    fun `test action disabled on whitespace`() {
        myFixture.configureByText("a.by", "x = 1\n<caret> y = x\n")
        assertFalse("disabled when caret not on identifier", updatePresentation().isEnabled)
    }

    fun `test action disabled on non-by file`() {
        myFixture.configureByText("a.txt", "x = 1\ny = <caret>x\n")
        assertFalse("disabled on non-.by file", updatePresentation().isEnabled)
    }

    fun `test action visible on by file`() {
        myFixture.configureByText("a.by", "x = 1\ny = <caret>x\n")
        assertTrue("visible on .by file", updatePresentation().isVisible)
    }
}

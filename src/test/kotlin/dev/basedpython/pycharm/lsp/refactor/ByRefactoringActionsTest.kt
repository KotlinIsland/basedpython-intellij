package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The platform's own refactoring shortcuts reach `by` in a `.by` file.
 *
 * They are found through the language's refactoring support and the inline extension point; with
 * neither, the shortcuts were disabled in a `.by` file and did nothing at all. No server runs here,
 * so each ends in the error every refactoring shows when it cannot be performed — which is also what
 * shows that the action reached `by`'s handler rather than stopping at the action.
 */
@TestFixtures
class ByRefactoringActionsTest {

    private val fixture by codeInsightFixture()

    @ParameterizedTest
    @ValueSource(strings = ["IntroduceVariable", "IntroduceConstant", "ExtractMethod", "Inline"])
    fun `the platform's refactoring action is enabled in a by file and asks by`(actionId: String) = onEdt {
        fixture.configureByText("main.by", "def f(a: int) -> int:\n    x = <selection>a + 1</selection>\n    return x * 2\n")

        val presentation = fixture.testAction(ActionManager.getInstance().getAction(actionId).let { NoPerform(it) })
        assertTrue(presentation.isEnabledAndVisible, "$actionId is not offered in a .by file")

        val error = assertThrows<RefactoringErrorHintException> { fixture.performEditorAction(actionId) }
        assertEquals("The by language server is not running", error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["InlineVariable", "ExtractVariable", "IntroduceConstant", "ExtractFunction"])
    fun `the plugin's own duplicate menu entries are gone`(name: String) = onEdt {
        assertNull(ActionManager.getInstance().getAction("basedpython.refactor.$name"))
    }

    /** Updates [action] as the platform does before showing it, without running it. */
    private class NoPerform(private val action: com.intellij.openapi.actionSystem.AnAction) :
        com.intellij.openapi.actionSystem.AnAction() {
        override fun getActionUpdateThread() = action.actionUpdateThread
        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) = action.update(e)
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = Unit
    }
}

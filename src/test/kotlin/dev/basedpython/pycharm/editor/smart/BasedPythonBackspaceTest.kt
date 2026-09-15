package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.letContentHashingFinish
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Backspace in a line's leading indentation, through the real action and the IDE's default
 * *Unindent* setting.
 *
 * The columns are the point. The handler this replaced measured the caret after the platform had
 * already deleted the character, so it stopped one stop short: five spaces went to none instead of
 * four, nine to four instead of eight, six to five instead of four — and a tab counted as one
 * column.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonBackspaceTest {

    private val fixture by codeInsightFixture()

    @AfterEach
    fun letTheEditSettle() = letContentHashingFinish()

    private fun backspace(before: String, after: String) {
        fixture.configureByText("a.by", before)
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        fixture.checkResult(after)
    }

    // The files carry an indented line so the detected indent is four spaces, as it is in practice.

    @Test
    fun `five columns go back to four`() =
        backspace("def f():\n    pass\n     <caret>x\n", "def f():\n    pass\n    <caret>x\n")

    @Test
    fun `nine columns go back to eight`() =
        backspace("def f():\n    pass\n         <caret>x\n", "def f():\n    pass\n        <caret>x\n")

    @Test
    fun `six columns go back to four`() =
        backspace("def f():\n    pass\n      <caret>x\n", "def f():\n    pass\n    <caret>x\n")

    @Test
    fun `eight columns go back a whole level`() =
        backspace("def f():\n    pass\n        <caret>x\n", "def f():\n    pass\n    <caret>x\n")

    @Test
    fun `a tab counts as the columns it spans`() =
        backspace("class C:\n\tdef f():\n\t\t<caret>pass\n", "class C:\n\tdef f():\n\t<caret>pass\n")

    @Test
    fun `past the indentation, Backspace deletes one character`() =
        backspace("def f():\n    pass\n    x <caret>= 1\n", "def f():\n    pass\n    x<caret>= 1\n")
}

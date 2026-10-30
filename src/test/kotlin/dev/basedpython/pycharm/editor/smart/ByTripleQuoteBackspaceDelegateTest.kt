package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/**
 * Backspace in an empty triple-quoted literal, through the real action, as PyCharm does it for `.py`
 * (measured in 263.7649: `x = """|"""` went back to `x = |` in `.py`, and to `x = ""|"""` in `.by`
 * before this).
 */
@TestFixtures
class ByTripleQuoteBackspaceDelegateTest {

    private val fixture by codeInsightFixture()

    private fun backspace(before: String, after: String) = onEdt {
        fixture.configureByText("a.by", before)
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        fixture.checkResult(after)
    }

    @Test
    fun `typing three quotes and Backspace leaves nothing`() = onEdt {
        fixture.configureByText("a.by", "x = <caret>\n")
        fixture.type("\"\"\"")
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        fixture.checkResult("x = <caret>\n")
    }

    @Test
    fun `an empty triple-quoted literal goes whole`() {
        backspace("x = \"\"\"<caret>\"\"\"\n", "x = <caret>\n")
        backspace("x = '''<caret>'''\n", "x = <caret>\n")
    }

    @Test
    fun `a prefix stays`() {
        backspace("x = rb\"\"\"<caret>\"\"\"\n", "x = rb<caret>\n")
        backspace("x = f\"\"\"<caret>\"\"\"\n", "x = f<caret>\n")
    }

    @Test
    fun `a literal with something in it loses one quote`() =
        backspace("x = \"\"\"<caret>abc\"\"\"\n", "x = \"\"<caret>abc\"\"\"\n")

    @Test
    fun `the closing quotes of a literal are deleted one by one`() =
        backspace("x = \"\"\"a\"\"\"<caret>\n", "x = \"\"\"a\"\"<caret>\n")

    @Test
    fun `an empty single-quoted pair still goes as a pair`() =
        backspace("x = \"<caret>\"\n", "x = <caret>\n")
}

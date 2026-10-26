package dev.basedpython.pycharm.lang

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/** *Comment with Line Comment* (Ctrl+/) in a `.by` file, which should read like Python's. */
@TestFixtures
class BasedPythonCommenterTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `a line comment goes at the indent, with a space`() = onEdt {
        fixture.configureByText("main.by", "def f():\n    total = 1<caret>\n    return total\n")
        fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        fixture.checkResult("def f():\n    # total = 1\n    return total\n")
    }

    @Test
    fun `lines of different depth are commented at the shallowest indent`() = onEdt {
        fixture.configureByText(
            "main.by",
            "<selection>def f():\n    return 1\n</selection>",
        )
        fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        fixture.checkResult("# def f():\n#     return 1\n")
    }

    @Test
    fun `uncommenting takes the comment off again, with or without the space`() = onEdt {
        fixture.configureByText("main.by", "def f():\n    # total = 1<caret>\n    #x = 2\n")
        fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        fixture.checkResult("def f():\n    total = 1\n    #x = 2\n")
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.text.indexOf("#x"))
        fixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        fixture.checkResult("def f():\n    total = 1\n    x = 2\n")
    }
}

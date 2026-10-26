package dev.basedpython.pycharm.editor.indent

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test
import java.awt.datatransfer.StringSelection

/**
 * End on a blank line, Auto-Indent Lines and paste in a real editor, with `by`'s outline of the
 * text put in by hand: the platform asks the formatting model for the first two, and the outline is
 * what answers.
 */
@TestFixtures
class ByIndentFormattingTest {

    private val fixture by codeInsightFixture()

    private fun act(action: String, before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.performEditorAction(action)
        fixture.checkResult(after)
    }

    private fun end(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) =
        act(IdeActions.ACTION_EDITOR_MOVE_LINE_END, before, after, outline)

    private fun autoIndent(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) =
        act("AutoIndentLines", before, after, outline)

    @Test
    fun `End on a blank line in a suite goes to the suite's level`() = end(
        "class C:\n    def f(self):\n        x = 1\n<caret>\n        y = 2\n",
        "class C:\n    def f(self):\n        x = 1\n        <caret>\n        y = 2\n",
    ) {
        compound { clause("class C:") { compound { clause("def f(self):") { simple("x = 1"); simple("y = 2") } } } }
    }

    @Test
    fun `End on a blank line after a return goes back to the header's level`() = end(
        "def f():\n    return 1\n<caret>\n",
        "def f():\n    return 1\n<caret>\n",
    ) {
        compound { clause("def f():") { simple("return 1") } }
    }

    @Test
    fun `End on a blank line after a header goes into its suite`() = end(
        "def f():\n<caret>\n",
        "def f():\n    <caret>\n",
    ) {
        compound { clause("def f():") }
    }

    @Test
    fun `End on a blank line inside brackets hangs a level deeper`() = end(
        "x = [\n<caret>\n]\n",
        "x = [\n    <caret>\n]\n",
    ) {
        simple("x = [\n\n]")
    }

    @Test
    fun `without an answer for this text, End goes where the line's whitespace ends`() = end(
        "def f():\n    x = 1\n<caret>\n    y = 2\n",
        "def f():\n    x = 1\n<caret>\n    y = 2\n",
        outline = null,
    )

    @Test
    fun `Auto-Indent Lines puts a statement in the suite the parser read it in`() = autoIndent(
        "def f(a):\n    x = 1\n            <caret>y = 2\n",
        "def f(a):\n    x = 1\n    y = 2\n<caret>",
    ) {
        compound { clause("def f(a):") { simple("x = 1"); simple("y = 2") } }
    }

    @Test
    fun `Auto-Indent Lines gives a header with an empty suite the statement after it`() = autoIndent(
        "def f(a):\n<caret>x = 1\n",
        "def f(a):\n    x = 1\n<caret>",
    ) {
        compound { clause("def f(a):") }
        simple("x = 1")
    }

    @Test
    fun `Auto-Indent Lines aligns a line inside brackets`() = autoIndent(
        "def f():\n    print(1,\n<caret>2)\n",
        "def f():\n    print(1,\n          2)\n<caret>",
    ) {
        compound { clause("def f():") { call("print(1,\n2)") } }
    }

    @Test
    fun `Auto-Indent Lines puts else at its if, and a case under its match`() {
        autoIndent(
            "def f(a):\n    if a:\n        pass\n<caret>else:\n        pass\n",
            "def f(a):\n    if a:\n        pass\n    else:\n<caret>        pass\n",
        ) {
            compound {
                clause("def f(a):") {
                    compound {
                        clause("if a:") { simple("pass") }
                        clause("else:") { simple("pass") }
                    }
                }
            }
        }
        autoIndent(
            "match x:\n<caret>case 1:\n        pass\n",
            "match x:\n    case 1:\n<caret>        pass\n",
        ) {
            compound {
                clause("match x:")
                clause("case 1:") { simple("pass") }
            }
        }
    }

    @Test
    fun `Auto-Indent Lines leaves a string's lines and a first-column comment alone`() {
        autoIndent("x = '''\n<caret>  a\n'''\n", "x = '''\n  a\n<caret>'''\n") {
            simple("x = '''\n  a\n'''")
            string("'''\n  a\n'''", text = listOf("\n  a\n"))
        }
        autoIndent("def f():\n<caret># note\n    pass\n", "def f():\n# note\n<caret>    pass\n") {
            compound { clause("def f():") { simple("pass") } }
        }
    }

    @Test
    fun `without an answer for this text, Auto-Indent Lines changes nothing`() = autoIndent(
        "def f(a):\n    x = 1\n            <caret>y = 2\n",
        "def f(a):\n    x = 1\n            y = 2\n<caret>",
        outline = null,
    )

    @Test
    fun `Reformat does not touch indentation through this model`() = onEdt {
        fixture.configureByText("a.by", "def f(a):\n    x = 1\n            y = 2\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound { clause("def f(a):") { simple("x = 1"); simple("y = 2") } }
        }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.project) {
            com.intellij.psi.codeStyle.CodeStyleManager.getInstance(fixture.project)
                .reformatText(fixture.file, 0, fixture.file.textLength)
        }
        fixture.checkResult("def f(a):\n    x = 1\n            y = 2\n")
    }

    private fun paste(before: String, text: String, after: String) = onEdt {
        val settings = CodeInsightSettings.getInstance()
        val was = settings.INDENT_TO_CARET_ON_PASTE
        settings.INDENT_TO_CARET_ON_PASTE = true
        try {
            fixture.configureByText("a.by", before)
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            fixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
            fixture.checkResult(after)
        } finally {
            settings.INDENT_TO_CARET_ON_PASTE = was
        }
    }

    @Test
    fun `lines pasted on an indented blank line move as a block`() = paste(
        "def f(a):\n    x = 1\n    <caret>\n",
        "if a:\n    return 1\nreturn 2",
        "def f(a):\n    x = 1\n    if a:\n        return 1\n    return 2<caret>\n",
    )

    @Test
    fun `lines copied with their indentation lose it for the caret's`() = paste(
        "def f(a):\n    x = 1\n    <caret>\n    y = 2\n",
        "        if a:\n            return 1\n        return 2\n",
        "def f(a):\n    x = 1\n    if a:\n        return 1\n    return 2\n<caret>\n    y = 2\n",
    )

    @Test
    fun `lines pasted before a line's code go where the code is`() = paste(
        "def f(a):\n    x = 1\n<caret>    y = 2\n",
        "z = 3\n",
        "def f(a):\n    x = 1\n    z = 3\n<caret>    y = 2\n",
    )

    @Test
    fun `text pasted into the middle of a line is left as it is`() = paste(
        "x = 1 + <caret>\n",
        "foo(\n    1)",
        "x = 1 + foo(\n    1)<caret>\n",
    )
}

package dev.basedpython.pycharm.editor.mover

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Test

/**
 * Move Statement Up/Down through the real actions, with `by`'s outline of the file put in by hand.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonStatementMoverTest {

    private val fixture by codeInsightFixture()

    private fun move(
        down: Boolean,
        before: String,
        after: String,
        outline: (OutlineSpec.Suite.() -> Unit)?,
    ) {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.performEditorAction(
            if (down) IdeActions.ACTION_MOVE_STATEMENT_DOWN_ACTION else IdeActions.ACTION_MOVE_STATEMENT_UP_ACTION,
        )
        fixture.checkResult(after)
    }

    private val q = "\"\"\""

    /**
     * A docstring line ending in `:` is not a header. The mover this replaced took `Args:` for one
     * and moved it with the lines indented under it, tearing the docstring apart.
     */
    @Test
    fun `a line of a docstring moves the whole docstring`() = move(
        down = true,
        before = "def f():\n    $q\n    Args:<caret>\n        x: a thing\n    $q\n    y = 1\n",
        after = "def f():\n    y = 1\n    $q\n    Args:<caret>\n        x: a thing\n    $q\n",
    ) {
        compound {
            clause("def f():") {
                simple("$q\n    Args:\n        x: a thing\n    $q")
                simple("y = 1")
            }
        }
    }

    @Test
    fun `a compound statement moves with its whole suite`() = move(
        down = false,
        before = "x = 1\n<caret>if a:\n    pass\nelse:\n    b()\n",
        after = "<caret>if a:\n    pass\nelse:\n    b()\nx = 1\n",
    ) {
        simple("x = 1")
        compound {
            clause("if a:") { simple("pass") }
            clause("else:") { call("b()") }
        }
    }

    @Test
    fun `a statement swaps with the compound statement next to it, suite and all`() = move(
        down = true,
        before = "<caret>x = 1\n@dec\ndef f():\n    pass\n",
        after = "@dec\ndef f():\n    pass\n<caret>x = 1\n",
    ) {
        simple("x = 1")
        compound(decorators = "@dec") { clause("def f():") { simple("pass") } }
    }

    @Test
    fun `a header with an inline suite is a statement, colon or not at the end of its line`() = move(
        down = true,
        before = "<caret>if a: b()  # note\nc = 1\n",
        after = "c = 1\n<caret>if a: b()  # note\n",
    ) {
        compound { clause("if a:") { call("b()") } }
        simple("c = 1")
    }

    @Test
    fun `a case trades places with the case next to it`() = move(
        down = false,
        before = "match x:\n    case 1:\n        a()\n    <caret>case 2:\n        b()\n",
        after = "match x:\n    <caret>case 2:\n        b()\n    case 1:\n        a()\n",
    ) {
        compound {
            clause("match x:")
            clause("case 1:") { call("a()") }
            clause("case 2:") { call("b()") }
        }
    }

    @Test
    fun `the last statement of a suite goes no further`() = move(
        down = true,
        before = "def f():\n    <caret>a = 1\nb = 2\n",
        after = "def f():\n    <caret>a = 1\nb = 2\n",
    ) {
        compound { clause("def f():") { simple("a = 1") } }
        simple("b = 2")
    }

    @Test
    fun `statements sharing a line move together`() = move(
        down = true,
        before = "<caret>a = 1; b = 2\nc = 3\n",
        after = "c = 3\n<caret>a = 1; b = 2\n",
    ) {
        simple("a = 1")
        simple("b = 2")
        simple("c = 3")
    }

    @Test
    fun `a selection of sibling statements moves as one`() = move(
        down = true,
        before = "<selection>a = 1\nb = 2\n</selection>c = 3\n",
        after = "c = 3\n<selection>a = 1\nb = 2\n</selection>",
    ) {
        simple("a = 1")
        simple("b = 2")
        simple("c = 3")
    }

    /** No outline for the text on screen is the platform's line mover, not a guess at the blocks. */
    @Test
    fun `without an answer for this text, the line moves on its own`() = move(
        down = true,
        before = "if a:\n<caret>    pass\nx = 1\n",
        after = "if a:\nx = 1\n<caret>    pass\n",
        outline = null,
    )
}

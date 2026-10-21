package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/**
 * Join Lines through the real action, with `by`'s outline of the file put in by hand. Each case is
 * one the platform's join — a space where the line break was — gets wrong.
 */
@TestFixtures
class BasedPythonJoinLinesHandlerTest {

    private val fixture by codeInsightFixture()

    private fun join(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_JOIN_LINES)
        fixture.checkResult(after)
    }

    @Test
    fun `a backslash continuation loses its backslash`() = join(
        "x = a + \\<caret>\n    b\n",
        "x = a + <caret>b\n",
    ) {
        simple("x = a + \\\n    b")
    }

    @Test
    fun `nothing goes between an opening bracket and what follows it`() = join(
        "f(<caret>\n    a,\n    b)\n",
        "f(<caret>a,\n    b)\n",
    ) {
        simple("f(\n    a,\n    b)")
    }

    @Test
    fun `nothing goes before a closing bracket`() = join(
        "f(\n    a,\n    b<caret>\n)\n",
        "f(\n    a,\n    b<caret>)\n",
    ) {
        simple("f(\n    a,\n    b\n)")
    }

    @Test
    fun `items inside brackets join with a space`() = join(
        "f(a,<caret>\n  b)\n",
        "f(a, <caret>b)\n",
    ) {
        simple("f(a,\n  b)")
    }

    @Test
    fun `two string literals of the same kind merge`() = join(
        "x = (\"ab\"<caret>\n     \"cd\")\n",
        "x = (\"ab<caret>cd\")\n",
    ) {
        simple("x = (\"ab\"\n     \"cd\")")
        string("\"ab\"")
        string("\"cd\"")
    }

    @Test
    fun `string literals with different quotes or prefixes stay apart`() = join(
        "x = (\"ab\"<caret>\n     r\"cd\")\n",
        "x = (\"ab\" <caret>r\"cd\")\n",
    ) {
        simple("x = (\"ab\"\n     r\"cd\")")
        string("\"ab\"")
        string("r\"cd\"")
    }

    @Test
    fun `two statements of a suite join with a semicolon`() = join(
        "def f():\n    x = 1<caret>\n    y = 2\n",
        "def f():\n    x = 1; <caret>y = 2\n",
    ) {
        compound {
            clause("def f():") {
                simple("x = 1")
                simple("y = 2")
            }
        }
    }

    @Test
    fun `a header joins with its one statement`() = join(
        "if a:<caret>\n    pass\n",
        "if a: <caret>pass\n",
    ) {
        compound { clause("if a:") { simple("pass") } }
    }

    @Test
    fun `a comment ending the first line goes to the end of the joined one`() = join(
        "x = 1  # one<caret>\ny = 2\n",
        "x = 1; <caret>y = 2  # one\n",
    ) {
        simple("x = 1")
        simple("y = 2")
    }

    /** `if a: b()` followed by an indented `c()` is not python, and `b()` alone is not the suite. */
    @Test
    fun `a header with a suite of several statements is not joined`() = join(
        "if a:<caret>\n    b()\n    c()\n",
        "if a:<caret>\n    b()\n    c()\n",
    ) {
        compound {
            clause("if a:") {
                call("b()")
                call("c()")
            }
        }
    }

    /** Either way round, `c = 2` would end up inside the `if` or `b = 1` outside it. */
    @Test
    fun `lines from different suites are not joined`() = join(
        "if a:\n    b = 1<caret>\nc = 2\n",
        "if a:\n    b = 1<caret>\nc = 2\n",
    ) {
        compound { clause("if a:") { simple("b = 1") } }
        simple("c = 2")
    }

    @Test
    fun `a comment line is not joined onto code`() = join(
        "# note<caret>\nx = 1\n",
        "# note<caret>\nx = 1\n",
    ) {
        simple("x = 1")
    }

    /** No outline for the text on screen is the platform's join, not a guess at the statements. */
    @Test
    fun `without an answer for this text, the platform joins the lines`() = join(
        "x = 1<caret>\ny = 2\n",
        "x = 1 y = 2\n",
        outline = null,
    )
}

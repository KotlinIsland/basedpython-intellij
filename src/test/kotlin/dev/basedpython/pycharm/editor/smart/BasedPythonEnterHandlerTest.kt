package dev.basedpython.pycharm.editor.smart

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import dev.basedpython.pycharm.testFramework.letContentHashingFinish

/**
 * Enter in a real editor, with `by`'s outline of the text put in by hand.
 *
 * The outline is what decides; the text around the caret is deliberately shaped to mislead a
 * scanner — a colon at the end of a line that opens nothing, a header that does not end its line
 * with one — so a test only passes by reading the outline.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonEnterHandlerTest {

    private val fixture by codeInsightFixture()

    @AfterEach
    fun letTheEditSettle() = letContentHashingFinish()

    private fun enter(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.type('\n')
        fixture.checkResult(after)
    }

    @Test
    fun `a line broken after a header's colon starts its suite`() = enter(
        "def f():<caret>",
        "def f():\n    <caret>",
    ) {
        compound { clause("def f():") }
    }

    @Test
    fun `the suite is one level deeper than the keyword's line, however the header wraps`() = enter(
        "class C:\n    def f(\n        a,\n    ):<caret>\n",
        "class C:\n    def f(\n        a,\n    ):\n        <caret>\n",
    ) {
        compound { clause("class C:") { compound { clause("def f(\n        a,\n    ):") } } }
    }

    @Test
    fun `a trailing comment after the colon still opens the suite`() = enter(
        "if x:  # a: note<caret>",
        "if x:  # a: note\n    <caret>",
    ) {
        compound { clause("if x:") }
    }

    @Test
    fun `breaking an inline suite puts its body in the suite`() = enter(
        "if x:<caret> return 1\n",
        "if x:\n    <caret>return 1\n",
    ) {
        compound { clause("if x:") { simple("return 1") } }
    }

    @Test
    fun `a colon that opens nothing keeps the line's indentation`() = enter(
        "def f():\n    match: int = 1\n    x = \"a:\"<caret>",
        "def f():\n    match: int = 1\n    x = \"a:\"\n    <caret>",
    ) {
        compound {
            clause("def f():") {
                simple("match: int = 1")
                simple("x = \"a:\"")
            }
        }
    }

    @Test
    fun `after the body of an inline suite, nothing more is opened`() = enter(
        "if x: return 1<caret>",
        "if x: return 1\n<caret>",
    ) {
        compound { clause("if x:") { simple("return 1") } }
    }

    /**
     * No outline for the text on screen — the key beat the server's answer to the last edit — is the
     * platform's Enter, which carries the line's indentation over. It is not a guess from the colon.
     */
    @Test
    fun `without an answer for this text, Enter is the platform's`() = enter(
        "def f():\n    if x:<caret>",
        "def f():\n    if x:\n    <caret>",
        outline = null,
    )

    @Test
    fun `an answer for an earlier revision is not used`() {
        fixture.configureByText("a.by", "def f():\n    pass<caret>")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound { clause("def f():") { simple("pass") } }
        }
        fixture.type(':')
        fixture.type('\n')
        fixture.checkResult("def f():\n    pass:\n    <caret>")
    }

    @Test
    fun `a tab-indented file gets a tab-indented suite`() = enter(
        "class C:\n\tdef f():<caret>\n\t\tpass\n",
        "class C:\n\tdef f():\n\t\t<caret>\n\t\tpass\n",
    ) {
        compound { clause("class C:") { compound { clause("def f():") { simple("pass") } } } }
    }

    @Test
    fun `the indent is as wide as the file's code style says`() = enter(
        "class C:\n  def f():\n    pass\n  def g():<caret>\n",
        "class C:\n  def f():\n    pass\n  def g():\n    <caret>\n",
    ) {
        compound {
            clause("class C:") {
                compound { clause("def f():") { simple("pass") } }
                compound { clause("def g():") }
            }
        }
    }
}

package dev.basedpython.pycharm.editor.smart

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/**
 * Enter in a real editor, with `by`'s outline of the text put in by hand.
 *
 * The outline is what decides; the text around the caret is deliberately shaped to mislead a
 * scanner — a colon at the end of a line that opens nothing, a header that does not end its line
 * with one — so a test only passes by reading the outline.
 */
@TestFixtures
class BasedPythonEnterHandlerTest {

    private val fixture by codeInsightFixture()

    private fun enter(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) = onEdt {
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
    fun `an answer for an earlier revision is not used`() = onEdt {
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

    // ---- where the new line goes, as PyCharm puts it for .py (measured in 263.7528) ----

    @Test
    fun `after a return the next line leaves the suite`() = enter(
        "def f(a):\n    if a:\n        return a<caret>\n    return 0\n",
        "def f(a):\n    if a:\n        return a\n    <caret>\n    return 0\n",
    ) {
        compound {
            clause("def f(a):") {
                compound { clause("if a:") { simple("return a") } }
                simple("return 0")
            }
        }
    }

    @Test
    fun `after pass and raise too, but not after break`() {
        enter("class C:\n    def f(self):\n        pass<caret>\n", "class C:\n    def f(self):\n        pass\n    <caret>\n") {
            compound { clause("class C:") { compound { clause("def f(self):") { simple("pass") } } } }
        }
        enter("def f():\n    raise E()<caret>\n", "def f():\n    raise E()\n<caret>\n") {
            compound { clause("def f():") { simple("raise E()") } }
        }
        enter("for i in x:\n    break<caret>\n", "for i in x:\n    break\n    <caret>\n") {
            compound { clause("for i in x:") { simple("break") } }
        }
    }

    @Test
    fun `a name that starts with return is not a return`() = enter(
        "def f():\n    return_value = 1<caret>\n",
        "def f():\n    return_value = 1\n    <caret>\n",
    ) {
        compound { clause("def f():") { simple("return_value = 1") } }
    }

    @Test
    fun `after a statement spanning lines, the next goes where it started`() = enter(
        "def f():\n    x = foo(1,\n            2)<caret>\n",
        "def f():\n    x = foo(1,\n            2)\n    <caret>\n",
    ) {
        compound { clause("def f():") { simple("x = foo(1,\n            2)") } }
    }

    @Test
    fun `inside brackets a line aligns with what follows the bracket`() = enter(
        "def f():\n    print(1,<caret>2)\n",
        "def f():\n    print(1,\n          <caret>2)\n",
    ) {
        compound { clause("def f():") { call("print(1,2)") } }
    }

    @Test
    fun `with nothing after the bracket the line hangs a level deeper`() = enter(
        "def f():\n    print(<caret>1, 2)\n",
        "def f():\n    print(\n        <caret>1, 2)\n",
    ) {
        compound { clause("def f():") { call("print(1, 2)") } }
    }

    @Test
    fun `between an empty pair the closing bracket gets a line of its own`() = enter(
        "def f():\n    x = [<caret>]\n",
        "def f():\n    x = [\n        <caret>\n    ]\n",
    ) {
        compound { clause("def f():") { simple("x = []") } }
    }

    @Test
    fun `inside a comment the rest goes on as a comment`() = enter(
        "def f():\n    # hello <caret>world\n    pass\n",
        "def f():\n    # hello \n    # <caret>world\n    pass\n",
    ) {
        compound { clause("def f():") { simple("pass") } }
    }

    @Test
    fun `a comment after code goes on at the line's indentation`() = enter(
        "def f():\n    x = 1  # hello <caret>world\n",
        "def f():\n    x = 1  # hello \n    # <caret>world\n",
    ) {
        compound { clause("def f():") { simple("x = 1") } }
    }

    @Test
    fun `at a comment's end the line is code`() = enter(
        "def f():\n    # hello<caret>\n    pass\n",
        "def f():\n    # hello\n    <caret>\n    pass\n",
    ) {
        compound { clause("def f():") { simple("pass") } }
    }

    @Test
    fun `inside a statement a backslash carries it on`() = enter(
        "def f(a):\n    return<caret> a\n",
        "def f(a):\n    return\\\n        <caret>a\n",
    ) {
        compound { clause("def f(a):") { simple("return a") } }
    }

    @Test
    fun `inside a clause header too`() = enter(
        "if a and<caret> b:\n    pass\n",
        "if a and\\\n    <caret>b:\n    pass\n",
    ) {
        compound { clause("if a and b:") { simple("pass") } }
    }

    @Test
    fun `inside an expression by says may be wrapped, it is wrapped in parentheses, as py's Enter does`() = enter(
        "x = 1 +<caret> 2\n",
        "x = (1 +\n     <caret>2)\n",
    ) {
        simple("x = 1 + 2", wrap = "1 + 2")
    }

    @Test
    fun `and inside a clause header's condition`() = enter(
        "if a and<caret> b:\n    pass\n",
        "if (a and\n    <caret>b):\n    pass\n",
    ) {
        compound { clause("if a and b:", wrap = "a and b") { simple("pass") } }
    }

    @Test
    fun `a statement with nothing to wrap still takes a backslash`() = enter(
        "def f(a):\n    return<caret> a\n",
        "def f(a):\n    return\\\n        <caret>a\n",
    ) {
        // `return a` has an expression, but the break is before it, outside what can be wrapped
        compound { clause("def f(a):") { simple("return a", wrap = "a") } }
    }

    @Test
    fun `a string is closed, reopened and wrapped so it stays one expression`() = enter(
        "x = 'a' f'bc<caret>de'\n",
        "x = ('a' f'bc'\n     f'<caret>de')\n",
    ) {
        simple("x = 'a' f'bcde'")
        string("'a'", text = listOf("a"))
        string("f'bcde'", text = listOf("bcde"))
    }

    @Test
    fun `inside brackets a split string needs no parentheses`() = enter(
        "print('abc<caret>def')\n",
        "print('abc'\n      '<caret>def')\n",
    ) {
        call("print('abcdef')")
        string("'abcdef'", text = listOf("abcdef"))
    }

    @Test
    fun `an escape is not split`() = enter(
        "print('ab\\<caret>ncd')\n",
        "print('ab'\n      '<caret>\\ncd')\n",
    ) {
        call("print('ab\\ncd')")
        string("'ab\\ncd'", escapes = listOf("\\n"), text = listOf("ab", "\\n", "cd"))
    }

    @Test
    fun `a triple-quoted string's line break is its own`() = enter(
        "x = \"\"\"ab<caret>cd\"\"\"\n",
        "x = \"\"\"ab\n<caret>cd\"\"\"\n",
    ) {
        simple("x = \"\"\"abcd\"\"\"")
        string("\"\"\"abcd\"\"\"", text = listOf("abcd"))
    }

    @Test
    fun `in an interpolation the line aligns inside the braces`() = enter(
        "x = f'{a +<caret> b}'\n",
        "x = f'{a +\n       <caret>b}'\n",
    ) {
        simple("x = f'{a + b}'")
        string("f'{a + b}'", interpolations = listOf("{a + b}"))
    }
}

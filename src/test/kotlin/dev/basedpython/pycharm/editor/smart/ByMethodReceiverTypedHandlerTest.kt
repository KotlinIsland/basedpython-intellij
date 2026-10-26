package dev.basedpython.pycharm.editor.smart

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/** `(` typed after a method's name, with `by`'s outline — which says what kind of method — put in by hand. */
@TestFixtures
class ByMethodReceiverTypedHandlerTest {

    private val fixture by codeInsightFixture()

    private fun paren(before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.type('(')
        fixture.checkResult(after)
    }

    @Test
    fun `an instance method gets self`() = paren("class C:\n    def foo<caret>\n", "class C:\n    def foo(self<caret>):\n") {
        compound { clause("class C:") { def("def foo", method = "instance") } }
    }

    @Test
    fun `a class method gets cls`() = paren(
        "class C:\n    @classmethod\n    def foo<caret>\n",
        "class C:\n    @classmethod\n    def foo(cls<caret>):\n",
    ) {
        compound { clause("class C:") { def("def foo", method = "class") } }
    }

    @Test
    fun `a static method gets nothing but its parentheses and colon`() = paren(
        "class C:\n    static def foo<caret>\n",
        "class C:\n    static def foo(<caret>):\n",
    ) {
        compound { clause("class C:") { def("def foo", method = "static") } }
    }

    @Test
    fun `__new__, which by reports static, is passed its class and gets cls, as in py`() = paren(
        "class C:\n    def __new__<caret>\n",
        "class C:\n    def __new__(cls<caret>):\n",
    ) {
        compound { clause("class C:") { def("def __new__", method = "static") } }
    }

    @Test
    fun `a function, or a by that does not say, is left to the platform`() {
        paren("def foo<caret>\n", "def foo(<caret>)\n") { def("def foo") }
        paren("class C:\n    def foo<caret>\n", "class C:\n    def foo(<caret>)\n") {
            compound { clause("class C:") { def("def foo") } }
        }
    }

    @Test
    fun `without an answer for this text, the paren is the platform's`() =
        paren("class C:\n    def foo<caret>\n", "class C:\n    def foo(<caret>)\n", outline = null)
}

package dev.basedpython.pycharm.lang

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.lang.psi.ByClass
import dev.basedpython.pycharm.lang.psi.ByFunction
import dev.basedpython.pycharm.lang.psi.ByImport
import dev.basedpython.pycharm.lang.psi.ByParameter

/**
 * End-to-end tests for the composite PSI tree built by the indent-aware parser.
 * Uses [BasePlatformTestCase] + `configureByText` to exercise the real ParserDefinition.
 */
class ParserTreeTest : BasePlatformTestCase() {

    private fun parse(text: String): PsiFile = myFixture.configureByText("sample.by", text)

    private fun functions(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, ByFunction::class.java).toList()
    private fun classes(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, ByClass::class.java).toList()
    private fun imports(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, ByImport::class.java).toList()
    private fun params(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, ByParameter::class.java).toList()
    private fun errors(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList()

    // ------------------------------------------------------------------
    // basic shapes
    // ------------------------------------------------------------------

    fun `test single function is recognised with name`() {
        val f = parse("def hello():\n    pass\n")
        val fns = functions(f)
        assertEquals(1, fns.size)
        assertEquals("hello", fns[0].name)
    }

    fun `test simple class is recognised with name`() {
        val f = parse("class Foo:\n    pass\n")
        val cs = classes(f)
        assertEquals(1, cs.size)
        assertEquals("Foo", cs[0].name)
    }

    fun `test import statement is recognised`() {
        val f = parse("import os\n")
        assertEquals(1, imports(f).size)
    }

    fun `test from import is recognised`() {
        val f = parse("from typing import List\n")
        assertEquals(1, imports(f).size)
    }

    fun `test multiple imports`() {
        val f = parse("import os\nimport sys\nfrom a import b\n")
        assertEquals(3, imports(f).size)
    }

    // ------------------------------------------------------------------
    // nesting
    // ------------------------------------------------------------------

    fun `test method nested in class`() {
        val f = parse("class C:\n    def m(self):\n        pass\n")
        val cs = classes(f)
        val fns = functions(f)
        assertEquals(1, cs.size)
        assertEquals(1, fns.size)
        assertEquals("m", fns[0].name)
        // function must be a descendant of the class
        assertTrue(PsiTreeUtil.isAncestor(cs[0], fns[0], true))
    }

    fun `test nested defs`() {
        val f = parse("def outer():\n    def inner():\n        pass\n")
        val fns = functions(f)
        assertEquals(2, fns.size)
        val names = fns.map { it.name }.toSet()
        assertTrue(names.contains("outer"))
        assertTrue(names.contains("inner"))
        val outer = fns.first { it.name == "outer" }
        val inner = fns.first { it.name == "inner" }
        assertTrue(PsiTreeUtil.isAncestor(outer, inner, true))
    }

    fun `test two top-level functions are siblings`() {
        val f = parse("def a():\n    pass\ndef b():\n    pass\n")
        val fns = functions(f)
        assertEquals(2, fns.size)
        assertFalse(PsiTreeUtil.isAncestor(fns[0], fns[1], true))
        assertFalse(PsiTreeUtil.isAncestor(fns[1], fns[0], true))
    }

    // ------------------------------------------------------------------
    // parameters & return type
    // ------------------------------------------------------------------

    fun `test typed parameters are parsed`() {
        val f = parse("def add(a: int, b: int) -> int:\n    return a + b\n")
        val ps = params(f)
        assertEquals(2, ps.size)
    }

    fun `test default value parameter`() {
        val f = parse("def greet(name: str = \"world\"):\n    pass\n")
        assertEquals(1, params(f).size)
    }

    fun `test no-arg function has no parameters`() {
        val f = parse("def f():\n    pass\n")
        assertEquals(0, params(f).size)
    }

    fun `test multiline parameter list joins implicitly`() {
        val f = parse("def f(\n    a,\n    b,\n    c,\n):\n    pass\n")
        assertEquals(1, functions(f).size)
        assertEquals(3, params(f).size)
    }

    // ------------------------------------------------------------------
    // basedpython class variants
    // ------------------------------------------------------------------

    fun `test data class is recognised`() {
        val f = parse("data class Point:\n    x: int\n    y: int\n")
        val cs = classes(f)
        assertEquals(1, cs.size)
        assertEquals("Point", cs[0].name)
    }

    fun `test frozen data class is recognised`() {
        val f = parse("frozen data class P:\n    x: int\n")
        val cs = classes(f)
        assertEquals(1, cs.size)
        assertEquals("P", cs[0].name)
    }

    fun `test enum class is recognised`() {
        val f = parse("enum class Color:\n    RED\n    GREEN\n")
        val cs = classes(f)
        assertEquals(1, cs.size)
        assertEquals("Color", cs[0].name)
    }

    fun `test protocol is recognised as class`() {
        val f = parse("protocol Drawable:\n    def draw(self):\n        pass\n")
        assertEquals(1, classes(f).size)
    }

    fun `test async def is recognised`() {
        val f = parse("async def main():\n    pass\n")
        val fns = functions(f)
        assertEquals(1, fns.size)
        assertEquals("main", fns[0].name)
    }

    fun `test decorated function`() {
        val f = parse("@staticmethod\ndef util():\n    pass\n")
        val fns = functions(f)
        assertEquals(1, fns.size)
        assertEquals("util", fns[0].name)
    }

    // ------------------------------------------------------------------
    // round-trip + error-freeness
    // ------------------------------------------------------------------

    fun `test text round-trips for representative file`() {
        val src = """
            import os
            from typing import List

            @decorator
            data class Point:
                x: int
                y: int

                def dist(self) -> float:
                    return (self.x ** 2 + self.y ** 2) ** 0.5

            async def main():
                p = Point()
                match p:
                    case _:
                        pass
        """.trimIndent() + "\n"
        val f = parse(src)
        assertEquals(src, f.text)
    }

    fun `test representative file has expected declarations`() {
        val src = """
            import os

            class Animal:
                def speak(self) -> str:
                    return "..."

            class Dog:
                def speak(self) -> str:
                    return "woof"

            def main():
                pass
        """.trimIndent() + "\n"
        val f = parse(src)
        assertEquals(2, classes(f).size)
        assertTrue("at least three functions", functions(f).size >= 3)
        assertEquals(1, imports(f).size)
    }

    fun `test well-formed file has no error elements`() {
        val src = "def f(a: int) -> int:\n    return a\n"
        val f = parse(src)
        assertEquals(0, errors(f).size)
    }

    // ------------------------------------------------------------------
    // tolerance: malformed input must not throw and must round-trip
    // ------------------------------------------------------------------

    fun `test malformed input does not throw and round-trips`() {
        val src = "def (:\n  )) class\n    @@@\nimport\n      garbage ][ {\ndef ok():\n    pass\n"
        // The contract is: never throw, always consume to EOF, and round-trip the text.
        val f = parse(src)
        assertEquals(src, f.text)
    }

    fun `test trailing valid function after garbage is still recognised`() {
        val src = "x = ??? @\ndef ok():\n    pass\n"
        val f = parse(src)
        assertEquals(src, f.text)
        assertTrue("trailing valid def should be parsed", functions(f).any { it.name == "ok" })
    }

    fun `test unclosed bracket does not hang`() {
        val src = "x = foo(\n    a,\n    b\n"
        val f = parse(src)
        assertEquals(src, f.text)
    }

    fun `test only dedents and breaks does not throw`() {
        val src = "        weird_overindent = 1\nback = 2\n"
        val f = parse(src)
        assertEquals(src, f.text)
    }

    override fun getTestDataPath(): String = "src/test/testData"
}

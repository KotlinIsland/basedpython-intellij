package dev.basedpython.pycharm.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading declarations out of `.by` text — the step between "the caret is on this line" and a
 * pytest node id, shared by the run-configuration producer, the gutter markers and the collected
 * index.
 */
class ByTestDeclarationsTest {

    private fun declaration(source: String, line: Int): ByDeclarationPath? {
        val lines = source.trimIndent().lines()
        return ByTestDeclarations.declarationAt({ lines[it] }, lines.size, line)
    }

    @Test
    fun `a top level test function is its own path`() {
        val path = declaration("def test_add():\n    assert True", 0)
        assertEquals(ByDeclarationPath(listOf("test_add"), isClass = false), path)
    }

    @Test
    fun `a method is qualified by its class`() {
        val path = declaration(
            """
            class TestMath:
                def test_add(self):
                    assert True
            """,
            1,
        )
        assertEquals(ByDeclarationPath(listOf("TestMath", "test_add"), isClass = false), path)
    }

    @Test
    fun `nested classes all appear, outermost first`() {
        val path = declaration(
            """
            class TestOuter:
                class TestInner:
                    def test_deep(self):
                        assert True
            """,
            2,
        )
        assertEquals(ByDeclarationPath(listOf("TestOuter", "TestInner", "test_deep"), isClass = false), path)
    }

    @Test
    fun `a class line is a path in its own right`() {
        val path = declaration("class TestMath:\n    def test_add(self):\n        pass", 0)
        assertEquals(ByDeclarationPath(listOf("TestMath"), isClass = true), path)
    }

    @Test
    fun `a sibling class above does not enclose anything`() {
        // `TestOne` closes before `TestTwo` opens, so the method belongs to the latter only.
        val path = declaration(
            """
            class TestOne:
                def test_a(self):
                    assert True

            class TestTwo:
                def test_b(self):
                    assert True
            """,
            5,
        )
        assertEquals(ByDeclarationPath(listOf("TestTwo", "test_b"), isClass = false), path)
    }

    @Test
    fun `an async def is a declaration like any other`() {
        val path = declaration("async def test_io():\n    assert True", 0)
        assertEquals(ByDeclarationPath(listOf("test_io"), isClass = false), path)
    }

    @Test
    fun `lines that declare nothing yield nothing`() {
        assertNull(declaration("x = 1", 0))
        assertNull(declaration("# def test_commented_out():", 0))
        assertNull(declaration("", 0))
    }

    @Test
    fun `declarations are read whatever they are called`() {
        // Names are not the test: what pytest collects decides, and that is asked separately.
        assertEquals(
            ByDeclarationPath(listOf("helper"), isClass = false),
            declaration("def helper():\n    pass", 0),
        )
        assertEquals(
            ByDeclarationPath(listOf("Widget"), isClass = true),
            declaration("class Widget(Base):\n    pass", 0),
        )
    }

    @Test
    fun `the naming convention is pytest's default, and only a fallback`() {
        assertTrue(ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("test_add"), false)))
        assertTrue(ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("TestMath"), true)))
        assertTrue(
            ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("TestMath", "test_add"), false)),
        )
        assertFalse(ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("helper"), false)))
        assertFalse(ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("Widget"), true)))
        // A test method's class is not consulted: pytest collects `Test…` classes, and a method
        // inside a differently-named class is not collected however it is spelled.
        assertFalse(ByTestDeclarations.isConventionalTest(ByDeclarationPath(listOf("Widget"), true)))
    }
}

package dev.basedpython.pycharm.run.test.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Turning a pytest node id back into a place in a `.by` file.
 *
 * pytest runs against the transpiled tree, so it reports `.py` paths relative to `by run`'s temp
 * directory. Relative paths survive transpilation, so the source is the same path with the other
 * extension — everything else here is about not navigating somewhere wrong when that assumption
 * does not hold.
 */
class ByTestLocationsTest {

    @Test
    fun `a file node id names the by source`() {
        assertEquals(
            ByTestLocation("tests/test_math.by", emptyList()),
            ByTestLocations.parse("tests/test_math.py"),
        )
    }

    @Test
    fun `a class and method become the symbol chain`() {
        assertEquals(
            ByTestLocation("test_math.by", listOf("TestGroup", "test_in_class")),
            ByTestLocations.parse("test_math.py::TestGroup::test_in_class"),
        )
    }

    /** unittest reports `mymod.MathTest`, which is a module, not a path. */
    @Test
    fun `a node id that is not a py path resolves to nothing`() {
        assertNull(ByTestLocations.parse("mymod.MathTest"))
        assertNull(ByTestLocations.parse(""))
    }

    /** `test_add[1-2]` is one generated case; the declaration is `def test_add`. */
    @Test
    fun `parametrised cases resolve to the undecorated declaration`() {
        assertEquals(
            ByTestLocation("test_p.by", listOf("test_add")),
            ByTestLocations.parse("test_p.py::test_add[1-2]"),
        )
    }

    private val source = """
        def helper():
            pass

        def test_add():
            assert True

        class TestGroup:
            def test_in_class(self):
                assert True

            async def test_async(self):
                assert True
    """.trimIndent()

    @Test
    fun `a top-level function is found at its name`() {
        val offset = ByTestLocations.declarationOffset(source, listOf("test_add"))!!
        assertEquals("test_add", source.substring(offset, offset + "test_add".length))
    }

    @Test
    fun `a class is found by name`() {
        val offset = ByTestLocations.declarationOffset(source, listOf("TestGroup"))!!
        assertEquals("TestGroup", source.substring(offset, offset + "TestGroup".length))
    }

    @Test
    fun `an async method is found`() {
        val offset = ByTestLocations.declarationOffset(source, listOf("TestGroup", "test_async"))!!
        assertEquals("test_async", source.substring(offset, offset + "test_async".length))
    }

    /**
     * Searching each symbol after the previous one is what keeps a method inside its class from
     * being confused with a same-named function earlier in the file.
     */
    @Test
    fun `a method is resolved inside its class, not at an earlier namesake`() {
        val shadowed = """
            def test_one():
                pass

            class TestGroup:
                def test_one(self):
                    pass
        """.trimIndent()
        val offset = ByTestLocations.declarationOffset(shadowed, listOf("TestGroup", "test_one"))!!
        assertEquals(shadowed.lastIndexOf("test_one"), offset)
    }

    /** A renamed or moved test should land on the file, not nowhere and not on the wrong line. */
    @Test
    fun `an unknown symbol falls back to the deepest one that was found`() {
        val offset = ByTestLocations.declarationOffset(source, listOf("TestGroup", "test_gone"))!!
        assertEquals(source.indexOf("TestGroup"), offset)
        assertNull(ByTestLocations.declarationOffset(source, listOf("test_gone")))
    }

    /** A prefix match would send `test_add_more` to `test_add`. */
    @Test
    fun `declaration matching is on whole names`() {
        assertNull(ByTestLocations.declarationOffset(source, listOf("test_ad")))
        assertNull(ByTestLocations.declarationOffset("def test_addition(): pass", listOf("test_add")))
    }
}

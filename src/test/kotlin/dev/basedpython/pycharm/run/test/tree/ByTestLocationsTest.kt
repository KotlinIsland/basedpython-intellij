package dev.basedpython.pycharm.run.test.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Taking a pytest node id apart. Which source it names, and where in it, is [ByTestLocatorTest]'s
 * subject: that is `by`'s answer, not the node id's.
 */
class ByTestLocationsTest {

    @Test
    fun `a file node id keeps the path pytest reported`() {
        assertEquals(
            ByTestLocation("tests/test_math.py", emptyList()),
            ByTestLocations.parse("tests/test_math.py"),
        )
    }

    @Test
    fun `a class and method become the symbol chain`() {
        assertEquals(
            ByTestLocation("test_math.py", listOf("TestGroup", "test_in_class")),
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
            ByTestLocation("test_p.py", listOf("test_add")),
            ByTestLocations.parse("test_p.py::test_add[1-2]"),
        )
    }
}

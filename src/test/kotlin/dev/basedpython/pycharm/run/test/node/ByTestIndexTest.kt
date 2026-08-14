package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The collected-test index the gutter icons are drawn from. */
class ByTestIndexTest {

    private val index = ByTestIndex.of(
        collectionOf(
                "tests/test_math.py::test_add",
                "tests/test_math.py::test_param[1-2]",
                "tests/test_math.py::test_param[3-4]",
                "tests/test_math.py::TestGroup::test_in_class",
                "tests/test_math.py::TestGroup::test_other",
            ),
        takenAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `a collected test is keyed by its by source and its name chain`() {
        assertTrue(index.knows("tests/test_math.by"))
        assertEquals(1, index.testsAt("tests/test_math.by", listOf("test_add")))
        assertEquals(1, index.testsAt("tests/test_math.by", listOf("TestGroup", "test_in_class")))
    }

    @Test
    fun `a parametrized test counts its cases`() {
        assertEquals(2, index.testsAt("tests/test_math.by", listOf("test_param")))
    }

    @Test
    fun `a class counts the tests under it`() {
        assertEquals(2, index.testsAt("tests/test_math.by", listOf("TestGroup")))
    }

    @Test
    fun `a declaration pytest did not collect is absent, not zero`() {
        assertNull(index.testsAt("tests/test_math.by", listOf("test_helper")))
        assertNull(index.testsAt("tests/test_math.by", listOf("TestGroup", "helper")))
    }

    @Test
    fun `a file the collection never saw is unknown, which is not the same as empty`() {
        assertFalse(index.knows("tests/test_other.by"))
        assertNull(index.testsAt("tests/test_other.by", listOf("test_add")))
    }

    @Test
    fun `a file that only failed to collect stays unknown, so its tests keep their icons`() {
        val withError = ByTestIndex.of(
            ByCollection(errors = listOf(ByCollectionError("tests/test_broken.py", "RuntimeError: boom"))),
            takenAtMillis = 1_700_000_000_000L,
        )
        assertFalse(withError.knows("tests/test_broken.by"))
        assertTrue(withError.isEmpty())
    }

    @Test
    fun `nothing collected is an empty index`() {
        assertTrue(ByTestIndex.EMPTY.isEmpty())
        assertFalse(ByTestIndex.EMPTY.knows("tests/test_math.by"))
        assertNull(ByTestIndex.EMPTY.testsAt("tests/test_math.by", listOf("test_add")))
    }

    @Test
    fun `a node id naming only a file teaches nothing about its declarations`() {
        // Knowing a file means knowing which of its declarations pytest collected. A node id with
        // no name in it says nothing about any line, so claiming the file would strip every icon
        // in it and offer nothing back.
        val fileOnly = ByTestIndex.of(
            collectionOf("tests/test_math.py"),
            takenAtMillis = 1_700_000_000_000L,
        )
        assertFalse(fileOnly.knows("tests/test_math.by"))
    }
}

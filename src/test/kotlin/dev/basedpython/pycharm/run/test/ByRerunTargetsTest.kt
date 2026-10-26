package dev.basedpython.pycharm.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Rerun Failed Tests runs the failed tests' pytest node ids — the ids pytest reported, which are what
 * the configuration's paths take: the `metainfo` `by`'s test reporter gives each test, or, read from
 * pytest's `-v` lines, the `by_test://` URL the results tree carries.
 */
class ByRerunTargetsTest {

    @Test
    fun `each failed test's node id, once, in order`() {
        val urls = listOf(
            "by_test://tests/test_geometry.py::TestClassify::test_square",
            "by_test://tests/test_geometry.py::test_add[1-2]",
            "by_test://tests/test_geometry.py::TestClassify::test_square",
        )

        assertEquals(
            listOf("tests/test_geometry.py::TestClassify::test_square", "tests/test_geometry.py::test_add[1-2]"),
            ByRerunTargets.nodeIds(urls.map { ByRerunTargets.nodeId(it, null) }),
        )
    }

    @Test
    fun `the reporter's node id, not its location`() {
        assertEquals(
            "tests/test_k.py::TestGroup::test_in_class",
            ByRerunTargets.nodeId("file:///p/tests/test_k.by:22", "tests/test_k.py::TestGroup::test_in_class"),
        )
    }

    @Test
    fun `a test with no location, or another framework's, is not rerun`() {
        assertNull(ByRerunTargets.nodeId(null, null))
        assertNull(ByRerunTargets.nodeId("python<tests>://test_x", null))
        assertNull(ByRerunTargets.nodeId("by_test://", ""))
        // the reporter's import error is a test of the file, which has no node id to run again
        assertNull(ByRerunTargets.nodeId("file:///p/tests/test_broken.by", null))
    }
}

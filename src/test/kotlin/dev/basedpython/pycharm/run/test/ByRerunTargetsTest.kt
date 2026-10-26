package dev.basedpython.pycharm.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rerun Failed Tests runs the failed tests' pytest node ids, read off the `by_test://` URLs the
 * results tree carries — the ids pytest reported, which are what the configuration's paths take.
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
            ByRerunTargets.nodeIds(urls),
        )
    }

    @Test
    fun `a test with no location, or another framework's, is not rerun`() {
        assertEquals(emptyList<String>(), ByRerunTargets.nodeIds(listOf(null, "python<tests>://test_x", "by_test://")))
    }
}

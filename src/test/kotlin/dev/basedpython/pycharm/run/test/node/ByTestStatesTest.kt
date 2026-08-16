package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How a run's outcomes land on the tree, and what a parent shows for its children. */
class ByTestStatesTest {

    private val tree = ByTestNodes.build(
        collectionOf(
            "tests/test_math.py::test_add",
            "tests/test_math.py::test_param[1-2]",
            "tests/test_math.py::test_param[3-4]",
            "tests/test_math.py::TestGroup::test_in_class",
            "tests/test_more.py::test_top",
        ),
    )

    private fun states(vararg outcomes: Pair<String, ByTestState>) =
        ByTestStates.of(tree, outcomes.toMap())

    private fun ByTestNode.child(name: String): ByTestNode =
        children.first { it.name == name }

    private val dir get() = tree.child("tests")
    private val math get() = dir.child("test_math.by")

    @Test
    fun `nothing has run, so nothing has a result`() {
        val states = states()
        assertEquals(ByTestState.NOT_RUN, states[tree])
        assertEquals(ByTestState.NOT_RUN, states[math])
        assertEquals(ByTestState.NOT_RUN, states[math.child("test_add")])
    }

    @Test
    fun `a test takes the outcome reported for its node id`() {
        val states = states("tests/test_math.py::test_add" to ByTestState.PASSED)
        assertEquals(ByTestState.PASSED, states[math.child("test_add")])
    }

    @Test
    fun `a parametrized test is the worst of its cases`() {
        val states = states(
            "tests/test_math.py::test_param[1-2]" to ByTestState.PASSED,
            "tests/test_math.py::test_param[3-4]" to ByTestState.FAILED,
        )
        assertEquals(ByTestState.FAILED, states[math.child("test_param")])
    }

    @Test
    fun `one failure is what the file, the directory and the root show`() {
        val states = states("tests/test_math.py::TestGroup::test_in_class" to ByTestState.FAILED)
        assertEquals(ByTestState.FAILED, states[math])
        assertEquals(ByTestState.FAILED, states[dir])
        assertEquals(ByTestState.FAILED, states[tree])
    }

    @Test
    fun `a file is green only when every test in it passed`() {
        val partly = states("tests/test_math.py::test_add" to ByTestState.PASSED)
        // Running one test out of four says nothing about the other three.
        assertEquals(ByTestState.NOT_RUN, partly[math])

        val fully = states(
            "tests/test_math.py::test_add" to ByTestState.PASSED,
            "tests/test_math.py::test_param[1-2]" to ByTestState.PASSED,
            "tests/test_math.py::test_param[3-4]" to ByTestState.PASSED,
            "tests/test_math.py::TestGroup::test_in_class" to ByTestState.PASSED,
        )
        assertEquals(ByTestState.PASSED, fully[math])
        // …but the root also holds test_more.py, which has not run.
        assertEquals(ByTestState.NOT_RUN, fully[tree])
    }

    @Test
    fun `running outranks everything except a failure`() {
        assertEquals(
            ByTestState.RUNNING,
            ByTestState.worst(listOf(ByTestState.PASSED, ByTestState.RUNNING, ByTestState.SKIPPED)),
        )
        assertEquals(
            ByTestState.FAILED,
            ByTestState.worst(listOf(ByTestState.RUNNING, ByTestState.FAILED)),
        )
    }

    @Test
    fun `a skip is reported, not hidden behind the passes around it`() {
        val states = states(
            "tests/test_math.py::test_add" to ByTestState.PASSED,
            "tests/test_math.py::test_param[1-2]" to ByTestState.PASSED,
            "tests/test_math.py::test_param[3-4]" to ByTestState.PASSED,
            "tests/test_math.py::TestGroup::test_in_class" to ByTestState.SKIPPED,
        )
        assertEquals(ByTestState.SKIPPED, states[math])
    }

    @Test
    fun `an outcome for a test that is no longer collected changes nothing`() {
        val states = states("tests/test_gone.py::test_removed" to ByTestState.FAILED)
        assertEquals(ByTestState.NOT_RUN, states[tree])
    }
}

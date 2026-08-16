package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** What the state filter keeps, and what it takes with it. */
class ByTestFilterTest {

    private val tree = ByTestNodes.build(
        collectionOf(
            "tests/test_math.py::test_add",
            "tests/test_math.py::test_fails",
            "tests/other/test_more.py::test_top",
        ),
    )

    private val outcomes = mapOf(
        "tests/test_math.py::test_add" to ByTestState.PASSED,
        "tests/test_math.py::test_fails" to ByTestState.FAILED,
    )

    private val states = ByTestStates.of(tree, outcomes)

    private fun filtered(vararg visible: ByTestState) =
        ByTestFilter.apply(tree, states, visible.toSet())

    private fun names(node: ByTestNode?): List<String> {
        node ?: return emptyList()
        return listOf(node.name) + node.children.flatMap { names(it) }
    }

    @Test
    fun `showing everything is the tree itself, untouched`() {
        assertSame(tree, ByTestFilter.apply(tree, states, ByTestFilter.ALL))
    }

    @Test
    fun `only failures leaves the failure and the path to it`() {
        val result = filtered(ByTestState.FAILED)
        assertEquals(listOf("Tests", "tests", "test_math.by", "test_fails"), names(result))
    }

    @Test
    fun `a directory with nothing left disappears with its file`() {
        // `tests/other` holds only a never-run test, so filtering to failures takes both away.
        val result = filtered(ByTestState.FAILED)
        assertEquals(emptyList<String>(), names(result).filter { it.contains("other") })
    }

    @Test
    fun `counts follow the filter, since they count what is shown`() {
        assertEquals(3, tree.testCount)
        assertEquals(1, filtered(ByTestState.FAILED)!!.testCount)
        assertEquals(2, filtered(ByTestState.FAILED, ByTestState.PASSED)!!.testCount)
    }

    @Test
    fun `a filter matching nothing leaves nothing`() {
        assertNull(filtered(ByTestState.RUNNING))
    }

    @Test
    fun `never-run tests are a state like any other`() {
        val result = filtered(ByTestState.NOT_RUN)
        assertEquals(listOf("Tests", "tests", "other", "test_more.by", "test_top"), names(result))
    }

    @Test
    fun `errors survive every filter`() {
        val withError = ByTestNodes.build(
            collectionOf(
                "tests/test_math.py::test_add",
                errors = listOf(ByCollectionError(null, "by run stopped on 3 diagnostics")),
            ),
        )
        val result = ByTestFilter.apply(
            withError,
            ByTestStates.of(withError, emptyMap()),
            setOf(ByTestState.FAILED),
        )
        // The tests are gone; the reason the view might be empty is not something to hide.
        assertNotNull(result)
        assertEquals(listOf("Tests", "by run stopped on 3 diagnostics"), names(result))
    }
}

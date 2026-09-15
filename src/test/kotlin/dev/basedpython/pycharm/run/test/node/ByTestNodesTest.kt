package dev.basedpython.pycharm.run.test.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shape of the tree the node view draws, from the node ids pytest collected. */
class ByTestNodesTest {

    private fun tree(vararg nodeIds: String): ByTestNode =
        ByTestNodes.build(collectionOf(*nodeIds))

    private fun ByTestNode.child(name: String): ByTestNode =
        children.firstOrNull { it.name == name }
            ?: error("no child '$name' in ${children.map { it.name }}")

    @Test
    fun `directories, the file, the class and the test each become a level`() {
        val root = tree("tests/unit/test_math.py::TestGroup::test_add")

        val file = root.child("tests/unit").child("test_math.py")
        assertEquals(ByTestNodeKind.FILE, file.kind)
        assertEquals("tests/unit/test_math.py", file.target)

        val group = file.child("TestGroup")
        assertEquals(ByTestNodeKind.CLASS, group.kind)
        assertEquals("tests/unit/test_math.py::TestGroup", group.target)

        val test = group.child("test_add")
        assertEquals(ByTestNodeKind.TEST, test.kind)
        assertEquals("tests/unit/test_math.py::TestGroup::test_add", test.target)
        assertTrue(test.children.isEmpty())
    }

    /** `by` staged `src/tests/test_math.by` at `tests/test_math.py`; the view names the source. */
    @Test
    fun `a file is named as the source by staged it from`() {
        val root = ByTestNodes.build(collectionOf("tests/test_math.py::test_add")) { staged ->
            "test_math.by".takeIf { staged == "tests/test_math.py" }
        }
        val file = root.child("tests").child("test_math.by")
        // The name is the `.by` the user edits; the target stays the path pytest knows about.
        assertEquals("tests/test_math.py", file.target)
    }

    @Test
    fun `a file whose source is not known keeps the name pytest gave it`() {
        val file = tree("tests/test_math.py::test_add").child("tests").child("test_math.py")
        assertEquals("tests/test_math.py", file.target)
    }

    @Test
    fun `the parameters of a parametrized test become its children`() {
        val file = tree(
            "tests/test_math.py::test_param[1-2]",
            "tests/test_math.py::test_param[3-4]",
        ).child("tests").child("test_math.py")

        val test = file.child("test_param")
        assertEquals(ByTestNodeKind.TEST, test.kind)
        // Running the function without its brackets runs every case of it.
        assertEquals("tests/test_math.py::test_param", test.target)
        assertEquals(listOf("[1-2]", "[3-4]"), test.children.map { it.name })
        assertEquals("tests/test_math.py::test_param[1-2]", test.children[0].target)
        assertEquals(ByTestNodeKind.CASE, test.children[0].kind)
    }

    @Test
    fun `a test counts once per case, and its grouping node is not a test`() {
        val root = tree(
            "tests/test_math.py::test_add",
            "tests/test_math.py::test_param[1-2]",
            "tests/test_math.py::test_param[3-4]",
            "tests/test_math.py::TestGroup::test_in_class",
        )
        assertEquals(4, root.testCount)
        assertEquals(2, root.child("tests").child("test_math.py").child("test_param").testCount)
    }

    @Test
    fun `collection order is kept, not alphabetical order`() {
        val file = tree(
            "tests/test_math.py::test_zebra",
            "tests/test_math.py::test_apple",
        ).child("tests").child("test_math.py")
        assertEquals(listOf("test_zebra", "test_apple"), file.children.map { it.name })
    }

    @Test
    fun `a chain of directories with nothing else in it is one node`() {
        val root = tree("src/test/python/unit/test_math.py::test_add")
        assertEquals(listOf("src/test/python/unit"), root.children.map { it.name })
        assertEquals("src/test/python/unit", root.children.single().target)
    }

    @Test
    fun `a directory that branches is not collapsed`() {
        val root = tree(
            "tests/unit/test_a.py::test_a",
            "tests/integration/test_b.py::test_b",
        )
        val tests = root.child("tests")
        assertEquals(listOf("unit", "integration"), tests.children.map { it.name })
    }

    @Test
    fun `a test file at the project root needs no directory node`() {
        val root = tree("test_math.py::test_add")
        val file = root.child("test_math.py")
        assertEquals(ByTestNodeKind.FILE, file.kind)
        assertEquals("test_math.py", file.target)
    }

    @Test
    fun `errors come last, named after the file they happened in`() {
        val root = ByTestNodes.build(
            collectionOf(
                "tests/test_math.py::test_add",
                errors = listOf(ByCollectionError("tests/test_pyerr.py", "RuntimeError: boom")),
            ),
        )
        val error = root.children.last()
        assertEquals(ByTestNodeKind.ERROR, error.kind)
        assertEquals("tests/test_pyerr.py", error.name)
        assertEquals("RuntimeError: boom", error.detail)
        // An error is not a test, so it does not inflate the count.
        assertEquals(1, root.testCount)
    }

    @Test
    fun `an error with no file to blame is its own message`() {
        val root = ByTestNodes.build(ByCollection(errors = listOf(ByCollectionError(null, "by not found"))))
        val error = root.children.single()
        assertEquals("by not found", error.name)
        assertNull(error.detail)
        assertNull(error.target)
    }

    @Test
    fun `an empty collection is an empty root`() {
        val root = ByTestNodes.build(ByCollection())
        assertTrue(root.children.isEmpty())
        assertEquals(0, root.testCount)
        assertNull(root.target)
    }
}

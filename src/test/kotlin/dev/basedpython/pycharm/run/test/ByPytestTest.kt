package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.run.byArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a test configuration turns into a `by` command line.
 *
 * The shapes here were checked against the real CLI (by ruff/0.0.1, build b2dcbfb33) on a project
 * with `test_math.by` and `tests/test_nested.by`: `by run pytest -v` collects the transpiled tree,
 * `-v` produces the `path::name PASSED [ 50%]` lines `ByTestOutputParser` reads, and node ids
 * differ from the source only in the extension because `by run` preserves relative paths when it
 * transpiles into its temp directory.
 */
class ByPytestTest {

    @Test
    fun `no targets runs the whole project`() {
        assertEquals(listOf("pytest", "-v"), ByPytest.arguments(""))
        assertEquals(listOf("pytest", "-v"), ByPytest.arguments("   "))
    }

    @Test
    fun `a file target is rewritten onto the transpiled output`() {
        assertEquals(
            listOf("pytest", "-v", "tests/test_math.py"),
            ByPytest.arguments("tests/test_math.by"),
        )
    }

    @Test
    fun `a node id keeps its suffix`() {
        assertEquals("tests/test_math.py::test_add", ByPytest.nodeId("tests/test_math.by::test_add"))
        assertEquals(
            "tests/test_math.py::TestGroup::test_one",
            ByPytest.nodeId("tests/test_math.by::TestGroup::test_one"),
        )
    }

    @Test
    fun `several targets are split on whitespace`() {
        assertEquals(
            listOf("pytest", "-v", "a/test_one.py", "b/test_two.py::test_x"),
            ByPytest.arguments("a/test_one.by b/test_two.by::test_x"),
        )
    }

    @Test
    fun `a quoted target with a space stays one argument`() {
        assertEquals(
            listOf("pytest", "-v", "my tests/test_x.py"),
            ByPytest.arguments(""""my tests/test_x.by""""),
        )
    }

    @Test
    fun `a directory target is left alone`() {
        assertEquals(listOf("pytest", "-v", "tests"), ByPytest.arguments("tests"))
    }

    @Test
    fun `a target that already names a py file is left alone`() {
        assertEquals("tests/test_x.py::test_a", ByPytest.nodeId("tests/test_x.py::test_a"))
    }

    @Test
    fun `only the trailing extension is rewritten`() {
        // A `by` directory, or a `.by` earlier in the path, is not the extension.
        assertEquals("by/nested.py", ByPytest.nodeId("by/nested.by"))
        assertEquals("src.by.pkg/test_x.py", ByPytest.nodeId("src.by.pkg/test_x.by"))
    }

    @Test
    fun `the full command line puts the version flag before the module`() {
        // `by run --min-version 3.12 pytest -v ...`: the flag belongs to `run`, so it has to
        // follow the subcommand, and everything after the module is forwarded to the program.
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "3.12",
            subcommandArgs = ByPytest.arguments("tests/test_math.by::test_add"),
            extraArgs = "",
        )
        assertEquals(
            listOf("run", "--min-version", "3.12", "pytest", "-v", "tests/test_math.py::test_add"),
            args,
        )
    }

    @Test
    fun `extra args are forwarded to pytest after the targets`() {
        val args = byArguments("run", "--min-version", "", ByPytest.arguments("tests"), "-k slow")
        assertEquals(listOf("run", "pytest", "-v", "tests", "-k", "slow"), args)
    }
}

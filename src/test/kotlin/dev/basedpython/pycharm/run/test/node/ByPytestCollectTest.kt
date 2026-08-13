package dev.basedpython.pycharm.run.test.node

import dev.basedpython.pycharm.run.byArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How `by run pytest --collect-only -q` output becomes a list of tests.
 *
 * Every blob below is real output, captured from by ruff/0.0.1 (7e30af642) with pytest 8.4.1 on a
 * project with `tests/test_math.by` and `tests/test_more.by` — including the two ways collection
 * fails, which look nothing alike: pytest reports its own errors on stdout around a collection
 * that otherwise succeeded, while a project that does not type-check never reaches pytest and
 * `by` writes diagnostics to stderr instead.
 */
class ByPytestCollectTest {

    @Test
    fun `the command is by run pytest --collect-only -q`() {
        assertEquals(listOf("pytest", "--collect-only", "-q"), ByPytestCollect.arguments())
        assertEquals(
            listOf("run", "pytest", "--collect-only", "-q"),
            byArguments("run", "--min-version", "", ByPytestCollect.arguments(), ""),
        )
    }

    @Test
    fun `node ids are taken and the footer is not`() {
        val stdout = """
            tests/test_math.py::test_add
            tests/test_math.py::test_sub
            tests/test_math.py::test_param[1-2]
            tests/test_math.py::test_param[3-4]
            tests/test_math.py::TestGroup::test_in_class
            tests/test_math.py::TestGroup::test_other
            tests/test_more.py::test_top

            7 tests collected in 0.00s
        """.trimIndent()

        val collection = ByPytestCollect.parse(stdout, stderr = "", exitCode = 0)

        assertEquals(
            listOf(
                "tests/test_math.py::test_add",
                "tests/test_math.py::test_sub",
                "tests/test_math.py::test_param[1-2]",
                "tests/test_math.py::test_param[3-4]",
                "tests/test_math.py::TestGroup::test_in_class",
                "tests/test_math.py::TestGroup::test_other",
                "tests/test_more.py::test_top",
            ),
            collection.nodeIds,
        )
        assertTrue(collection.errors.isEmpty())
    }

    @Test
    fun `a case whose parameters contain spaces is still one node id`() {
        val collection = ByPytestCollect.parse("tests/test_x.py::test_add[1 - 2]", "", 0)
        assertEquals(listOf("tests/test_x.py::test_add[1 - 2]"), collection.nodeIds)
    }

    @Test
    fun `an error while collecting one file keeps the tests of the others`() {
        val stdout = """
            tests/test_math.py::test_add
            tests/test_more.py::test_top

            ==================================== ERRORS ====================================
            _____________________ ERROR collecting tests/test_pyerr.py _____________________
            tests/test_pyerr.py:6: in <module>
                raise RuntimeError("boom at import")
            E   RuntimeError: boom at import
            =========================== short test summary info ============================
            ERROR tests/test_pyerr.py - RuntimeError: boom at import
            !!!!!!!!!!!!!!!!!!!! Interrupted: 1 error during collection !!!!!!!!!!!!!!!!!!!!
            7 tests collected, 1 error in 0.06s
        """.trimIndent()

        val collection = ByPytestCollect.parse(stdout, stderr = "", exitCode = 2)

        // The report is full of lines carrying a `.py`; none of them is a node id.
        assertEquals(
            listOf("tests/test_math.py::test_add", "tests/test_more.py::test_top"),
            collection.nodeIds,
        )
        assertEquals(
            listOf(ByCollectionError("tests/test_pyerr.py", "RuntimeError: boom at import")),
            collection.errors,
        )
    }

    @Test
    fun `a project that does not type-check reports the by diagnostic and where it is`() {
        val stderr = """
            error[unresolved-import]: Cannot resolve imported module `nonexistent_module_xyz`
             --> tests/test_broken.by:1:8
              |
            1 | import nonexistent_module_xyz
              |        ^^^^^^^^^^^^^^^^^^^^^^
              |
            info: Searched in the following paths during module resolution:
            info:   1. /project (first-party code)

            Found 1 diagnostic
        """.trimIndent()

        val collection = ByPytestCollect.parse(stdout = "", stderr = stderr, exitCode = 11)

        assertTrue(collection.nodeIds.isEmpty())
        assertEquals(1, collection.errors.size)
        val error = collection.errors.single()
        assertEquals(null, error.target)
        assertTrue(error.message.startsWith("error[unresolved-import]: Cannot resolve"), error.message)
        assertTrue(error.message.endsWith("(tests/test_broken.by:1:8)"), error.message)
    }

    @Test
    fun `a failure with nothing to say still says something`() {
        val collection = ByPytestCollect.parse(stdout = "", stderr = "", exitCode = 137)
        assertEquals(1, collection.errors.size)
        assertTrue(collection.errors.single().message.contains("137"))
    }

    @Test
    fun `a run that collects nothing is not a failure`() {
        val collection = ByPytestCollect.parse("no tests collected in 0.01s", "", exitCode = 5)
        // Exit code 5 is pytest's "nothing to run", not something to show as an error node.
        assertTrue(collection.nodeIds.isEmpty())
        assertTrue(collection.errors.isEmpty())
    }

    @Test
    fun `duplicate node ids are collapsed`() {
        val collection = ByPytestCollect.parse(
            "tests/test_x.py::test_a\ntests/test_x.py::test_a",
            "",
            0,
        )
        assertEquals(listOf("tests/test_x.py::test_a"), collection.nodeIds)
    }
}

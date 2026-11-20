package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.run.byArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a test configuration turns into a `by` command line.
 *
 * The shapes here were checked against the real CLI on a project with `test_math.by` and
 * `tests/test_nested.by`: `by run --in-build pytest -v` collects the transpiled tree, and `-v`
 * produces the `path::name PASSED [ 50%]` lines `ByTestOutputParser` reads. Targets arrive already
 * naming files where `by run` stages them, so they are passed on untouched.
 *
 * `--in-build` is what makes those targets resolve: without it `by run` runs pytest in the directory
 * the IDE started `by` in, where the test is still a `.by` — measured against a real `by` on a
 * src-layout and a flat-layout project, `by run pytest -v tests/test_calc.py` reported `ERROR: file
 * or directory not found: tests/test_calc.py` and collected nothing.
 */
class ByPytestTest {

    /**
     * In place pytest runs in the project, where `by`'s pytest plugin collects a `.by` itself, so a
     * target names the `.by` and no `--in-build` comes before `pytest`. `--in-place` is the state's
     * to add, as for every `by run` ([dev.basedpython.pycharm.run.ByCommandLineState.inPlace]).
     */
    @Test
    fun `a run in place hands pytest the project's own files`() {
        assertEquals(listOf("pytest", "-v"), ByPytest.arguments("", inPlace = true))
        assertEquals(
            listOf("pytest", "-v", "tests/test_calc.by::test_add", "tests/test_plain.py"),
            ByPytest.arguments("tests/test_calc.by::test_add tests/test_plain.py", inPlace = true),
        )
    }

    @Test
    fun `no targets runs the whole project`() {
        assertEquals(listOf("--in-build", "pytest", "-v"), ByPytest.arguments("", inPlace = false))
        assertEquals(listOf("--in-build", "pytest", "-v"), ByPytest.arguments("   ", inPlace = false))
    }

    @Test
    fun `a node id is passed on as it is`() {
        assertEquals(
            listOf("--in-build", "pytest", "-v", "tests/test_math.py::TestGroup::test_one"),
            ByPytest.arguments("tests/test_math.py::TestGroup::test_one", inPlace = false),
        )
    }

    @Test
    fun `several targets are split on whitespace`() {
        assertEquals(
            listOf("--in-build", "pytest", "-v", "a/test_one.py", "b/test_two.py::test_x"),
            ByPytest.arguments("a/test_one.py b/test_two.py::test_x", inPlace = false),
        )
    }

    @Test
    fun `a quoted target with a space stays one argument`() {
        assertEquals(
            listOf("--in-build", "pytest", "-v", "my tests/test_x.py"),
            ByPytest.arguments(""""my tests/test_x.py"""", inPlace = false),
        )
    }

    @Test
    fun `a directory target is left alone`() {
        assertEquals(listOf("--in-build", "pytest", "-v", "tests"), ByPytest.arguments("tests", inPlace = false))
    }

    /**
     * `by run` forwards everything after the module to the program, so `--in-build` placed after
     * `pytest` would reach pytest as an argument — which pytest rejects — instead of `by` as a flag.
     */
    @Test
    fun `--in-build comes before the module`() {
        val args = ByPytest.arguments("tests/test_math.py", inPlace = false)
        assertEquals(ByPytest.IN_BUILD, args.first())
        assertEquals(ByPytest.MODULE, args[1])
    }

    @Test
    fun `the full command line puts the version flag before the module`() {
        // `by run --min-version 3.12 --in-build pytest -v ...`: both flags belong to `run`, so they
        // have to follow the subcommand, and everything after the module is forwarded to the program.
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "3.12",
            subcommandArgs = ByPytest.arguments("tests/test_math.py::test_add", inPlace = false),
            extraArgs = "",
        )
        assertEquals(
            listOf(
                "run", "--min-version", "3.12", "--in-build", "pytest", "-v",
                "tests/test_math.py::test_add",
            ),
            args,
        )
    }

    @Test
    fun `extra args are forwarded to pytest after the targets`() {
        // The test configuration's extra args are pytest's, which it says with `extraArgsForProgram`.
        val args = byArguments("run", "--min-version", "", ByPytest.arguments("tests", inPlace = false), "-k slow", extraArgsForProgram = true)
        assertEquals(listOf("run", "--in-build", "pytest", "-v", "tests", "-k", "slow"), args)
    }
}

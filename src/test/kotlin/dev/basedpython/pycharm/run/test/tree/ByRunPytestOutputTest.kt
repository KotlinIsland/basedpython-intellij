package dev.basedpython.pycharm.run.test.tree

import junit.framework.TestCase

/**
 * The parser against output a real `by run pytest -v` actually produced.
 *
 * Captured from `by` ruff/0.0.1 (build b2dcbfb33) with pytest 9.1.1, on a project holding
 * `test_math.by` (three functions plus a `TestGroup` class) and `tests/test_nested.by`. The point
 * of pinning the real bytes is that the configuration used to invoke a `by test` subcommand that
 * does not exist, so nothing had ever confirmed that what the CLI prints is what this parser reads.
 */
class ByRunPytestOutputTest : TestCase() {

    private val output = """
        ============================= test session starts ==============================
        platform darwin -- Python 3.14.7, pytest-9.1.1, pluggy-1.6.0 -- /tmp/venv/bin/python
        cachedir: .pytest_cache
        rootdir: /var/folders/5s/T/.tmpKl2Cjo
        collecting ... collected 5 items

        test_math.py::test_add_positive PASSED                                   [ 20%]
        test_math.py::test_add_negative PASSED                                   [ 40%]
        test_math.py::test_fails FAILED                                          [ 60%]
        test_math.py::TestGroup::test_in_class PASSED                            [ 80%]
        tests/test_nested.py::test_nested_one PASSED                             [100%]

        =================================== FAILURES ===================================
        __________________________________ test_fails __________________________________

            def test_fails():
        >       assert add(1, 1) == 3
        E       assert 2 == 3
        E        +  where 2 = add(1, 1)

        test_math.py:14: AssertionError
        =========================== short test summary info ============================
        FAILED test_math.py::test_fails - assert 2 == 3
        ========================= 1 failed, 4 passed in 0.01s ==========================
    """.trimIndent()

    fun `test every test in the run becomes a tree event`() {
        val events = ByTestOutputParser().parseAll(output)
        val started = events.filterIsInstance<ByTestEvent.TestStarted>().map { it.name }
        assertEquals(
            listOf(
                "test_add_positive",
                "test_add_negative",
                "test_fails",
                "test_in_class",
                "test_nested_one",
            ),
            started,
        )
    }

    fun `test outcomes match the run`() {
        val events = ByTestOutputParser().parseAll(output)
        assertEquals(
            listOf("test_add_positive", "test_add_negative", "test_in_class", "test_nested_one"),
            events.filterIsInstance<ByTestEvent.TestPassed>().map { it.name },
        )
        assertEquals(
            listOf("test_fails"),
            events.filterIsInstance<ByTestEvent.TestFailed>().map { it.name },
        )
    }

    fun `test one suite per file, opened and closed`() {
        val events = ByTestOutputParser().parseAll(output)
        assertEquals(
            listOf("test_math.py", "tests/test_nested.py"),
            events.filterIsInstance<ByTestEvent.TestSuiteStarted>().map { it.name },
        )
        assertEquals(
            listOf("test_math.py", "tests/test_nested.py"),
            events.filterIsInstance<ByTestEvent.SuiteFinished>().map { it.name },
        )
    }

    fun `test the short summary line does not report a second failure`() {
        // `FAILED test_math.py::test_fails - assert 2 == 3` in the footer names the same test
        // again; counting it would show two failures for one failing test.
        val parser = ByTestOutputParser()
        parser.parseAll(output)
        assertEquals(4, parser.passed)
        assertEquals(1, parser.failed)
    }

    fun `test the tally matches pytest's own footer`() {
        val parser = ByTestOutputParser()
        parser.parseAll(output)
        // `1 failed, 4 passed in 0.01s`
        assertEquals(5, parser.passed + parser.failed)
    }
}

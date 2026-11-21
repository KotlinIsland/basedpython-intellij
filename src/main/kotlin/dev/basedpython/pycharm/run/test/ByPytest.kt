package dev.basedpython.pycharm.run.test

import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.run.IN_BUILD_FLAG

/**
 * How the plugin runs basedpython tests.
 *
 * There is no `by test`. The CLI's subcommands are `check`, `server`, `version`, `explain`, `run`,
 * `build`, `generate-api-file` and `transpile` — a configuration asking for `test` died on
 * `error: unrecognized subcommand 'test'` before producing a single line of output, which is why
 * the test tree never showed anything.
 *
 * What is run is `by run --in-place pytest`: pytest, run by `by run` against the project's own
 * directories, where `by`'s pytest plugin collects each `.by` test module pytest would collect the
 * `.py` of — a target names the `.by` itself, as the project holds it, and every node id pytest
 * reports names it too: `tests/test_math.by::TestGroup::test_one`. So a target is written relative
 * to the directory pytest runs in ([ByTestTargets]), and a node id from the test tree goes back to
 * the file it names.
 *
 * A `by` whose `by run` does not take `--in-place` ([dev.basedpython.pycharm.lsp.ByCommandFlags.runsInPlace])
 * stages the project instead, and [IN_BUILD] runs pytest in that tree: `by run` otherwise runs the
 * program in the directory `by` was started in, where the only `tests/test_math` is a `.by` pytest
 * does not collect — measured, `by run pytest -v tests/test_calc.py` ended in `ERROR: file or
 * directory not found`, and a bare `by run pytest` collected 0 items. There a target names the file
 * where the tree holds it, which such a `by` laid out by the module tree rather than the directories
 * — a src-layout project's `src/tests/test_math.by` is staged as `tests/test_math.py` — so each `.by`
 * target is written as `by` says it is staged ([ByTestTargets.staged]) when the run starts. A `by`
 * that runs programs in place no longer stages one, and its build mirrors the project instead.
 *
 * Worth knowing too: `pytest` has to be importable by the interpreter `by run` picks: the project
 *    environment, otherwise the one named by the `PYTHON` environment variable, otherwise `python3`
 *    from `PATH`. A missing one fails with `ImportError: No module named pytest`.
 */
internal object ByPytest {

    /**
     * Run pytest in the tree `by run` staged rather than in the directory the IDE started `by` in,
     * for a run that is not in place.
     *
     * A flag of `by run`, so it comes before the module: `by run` forwards everything after the
     * module to the program, and `by run pytest -v --in-build` would reach pytest as an argument.
     * `--in-place` is not added here but by the state, as for every `by run`
     * ([dev.basedpython.pycharm.run.ByCommandLineState.inPlace]), and lands in the same place.
     */
    const val IN_BUILD: String = IN_BUILD_FLAG

    /** The module `by run` is pointed at. */
    const val MODULE: String = "pytest"

    /**
     * Has the pytest plugin `by run` puts in the staged tree report the run as TeamCity service
     * messages, which the test console reads as they come: each test with its pytest node id, its
     * `.by` line, its captured output, and a failing `==`'s actual and expected values.
     *
     * A `by` that predates the reporter ignores the variable, and then the tree is built from
     * [VERBOSE]'s lines instead — see [dev.basedpython.pycharm.run.test.tree.ByTestEventsConverter].
     */
    const val REPORTER_VARIABLE: String = "BY_TEST_REPORTER"

    /** The [REPORTER_VARIABLE] value that asks for TeamCity service messages. */
    const val REPORTER_TEAMCITY: String = "teamcity"

    /**
     * Verbose output is required, not cosmetic, wherever no reporter speaks for pytest: plain pytest,
     * and a `by` without [REPORTER_VARIABLE]. `ByTestOutputParser` builds the test tree from
     * pytest's per-test `path::name PASSED` lines, and without `-v` pytest prints only the
     * one-character progress line, which carries no names to build a tree from. Under the reporter
     * the lines stay in each test's output, as PyCharm's own pytest runs show them.
     */
    const val VERBOSE: String = "-v"

    /**
     * The arguments that follow `by run`, for the configured [paths].
     *
     * @param paths whitespace-separated pytest targets, each optionally carrying a node id suffix
     *   (`tests/test_math.by::TestGroup::test_one`), as pytest is to be handed them: in the project
     *   for a run [inPlace], in the staged tree otherwise. Blank runs the whole project.
     */
    fun arguments(paths: String, inPlace: Boolean): List<String> = buildList {
        if (!inPlace) add(IN_BUILD)
        add(MODULE)
        add(VERBOSE)
        if (paths.isNotBlank()) addAll(ParametersListUtil.parse(paths))
    }
}

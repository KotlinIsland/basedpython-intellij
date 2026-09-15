package dev.basedpython.pycharm.run.test

import com.intellij.util.execution.ParametersListUtil

/**
 * How the plugin runs basedpython tests.
 *
 * There is no `by test`. The CLI's subcommands are `check`, `server`, `version`, `explain`, `run`,
 * `build`, `generate-api-file` and `transpile` — a configuration asking for `test` died on
 * `error: unrecognized subcommand 'test'` before producing a single line of output, which is why
 * the test tree never showed anything.
 *
 * What is run is `by run pytest`. `by run <module>` transpiles the whole project into a temp
 * directory and runs `<module>` with that tree first on `sys.path`, and the module does not have to
 * be one of yours — so `pytest` is what is handed the transpiled tree. That tree follows the module
 * tree, not the directory tree: a src-layout project's `src/tests/test_math.by` is staged as
 * `tests/test_math.py`. So a target is written the way pytest names a file in that tree —
 * [dev.basedpython.pycharm.run.model.ByProgramModel.stagedPath], which is `by`'s answer — and passed
 * on as it is.
 *
 * `by run` runs the module in the directory it was started in, not in the tree it staged, and pytest
 * resolves a path target against that directory: a target naming a staged file is only found where
 * the run's working directory is the staged tree.
 *
 * Worth knowing too: `pytest` has to be importable by the interpreter `by run` picks: the one named by the `PYTHON`
 *    environment variable, otherwise `python3` from `PATH`. A missing one fails with
 *    `ImportError: No module named pytest`.
 */
internal object ByPytest {

    /** The module `by run` is pointed at. */
    const val MODULE: String = "pytest"

    /**
     * Verbose output is required, not cosmetic: `ByTestOutputParser` builds the test tree from
     * pytest's per-test `path::name PASSED` lines, and without `-v` pytest prints only the
     * one-character progress line, which carries no names to build a tree from.
     */
    const val VERBOSE: String = "-v"

    /**
     * The arguments that follow `by run`, for the configured [paths].
     *
     * @param paths whitespace-separated pytest targets in the staged tree, each optionally carrying a
     *   node id suffix (`tests/test_math.py::TestGroup::test_one`). Blank runs the whole project.
     */
    fun arguments(paths: String): List<String> = buildList {
        add(MODULE)
        add(VERBOSE)
        if (paths.isNotBlank()) addAll(ParametersListUtil.parse(paths))
    }
}

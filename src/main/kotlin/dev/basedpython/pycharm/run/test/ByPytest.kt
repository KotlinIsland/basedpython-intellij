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
 * What works is `by run pytest`. `by run <module>` transpiles the whole project into a temp
 * directory and runs `python -m <module>` there, and the module does not have to be one of yours —
 * so `pytest` runs against the transpiled tree, discovering the `.py` files `by` just produced.
 * Relative paths are preserved, so `tests/test_math.by` is collected as `tests/test_math.py` and
 * every node id differs from the source only in its extension.
 *
 * Two consequences worth knowing:
 *
 *  - pytest's rootdir is the temp directory, so configuration in the project's `pyproject.toml`
 *    (`[tool.pytest.ini_options]`) and any hand-written `conftest.py` are *not* picked up — only
 *    `.by` files are transpiled into that directory. A `conftest.by` works fine.
 *  - `pytest` has to be importable by the interpreter `by run` picks: the one named by the `PYTHON`
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
     * @param paths whitespace-separated `.by` targets, each optionally carrying a pytest node id
     *   suffix (`tests/test_math.by::TestGroup::test_one`). Blank runs the whole project.
     */
    fun arguments(paths: String): List<String> = buildList {
        add(MODULE)
        add(VERBOSE)
        if (paths.isNotBlank()) {
            ParametersListUtil.parse(paths).mapTo(this, ::nodeId)
        }
    }

    /**
     * Rewrites one configured target onto the transpiled tree: `tests/test_x.by::test_a` becomes
     * `tests/test_x.py::test_a`.
     *
     * Only the file part is touched — the `::` suffix is a pytest node id, and a target that is a
     * directory, or that already names a `.py`, is left exactly as it is.
     */
    fun nodeId(target: String): String {
        val separator = target.indexOf("::")
        val path = if (separator < 0) target else target.substring(0, separator)
        val suffix = if (separator < 0) "" else target.substring(separator)
        if (!path.endsWith(BY_EXTENSION)) return target
        return path.dropLast(BY_EXTENSION.length) + PY_EXTENSION + suffix
    }

    private const val BY_EXTENSION = ".by"
    private const val PY_EXTENSION = ".py"
}

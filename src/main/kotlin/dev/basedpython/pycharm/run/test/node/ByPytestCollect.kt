package dev.basedpython.pycharm.run.test.node

import dev.basedpython.pycharm.run.test.ByPytest

/**
 * Something that stopped a test from being collected.
 *
 * @param target the pytest target it happened in — a path naming the *transpiled* `.py`, as pytest
 *   reports it — or null when nothing narrower than the run itself is to blame (a `by` diagnostic,
 *   a missing binary).
 */
internal data class ByCollectionError(val target: String?, val message: String)

/** Everything one `--collect-only` run had to say: the node ids it found, and what went wrong. */
internal data class ByCollection(
    val nodeIds: List<String> = emptyList(),
    val errors: List<ByCollectionError> = emptyList(),
)

/**
 * Reads the test tree out of `by run pytest --collect-only -q`.
 *
 * Quiet mode is what makes this parseable: plain `--collect-only` prints an indented
 * `<Module …>` / `<Class …>` / `<Function …>` listing whose shape varies with pytest's version and
 * plugins, while `-q` prints one node id per line and a `N tests collected` footer. Node ids are
 * also exactly what the run configuration takes as a target, so a line of this output can be handed
 * straight back to [ByPytest] to run it.
 *
 * The output shapes below were taken from a real run (by ruff/0.0.1, pytest 8.4.1):
 *
 * ```
 * tests/test_math.py::test_add
 * tests/test_math.py::test_param[1-2]
 * tests/test_math.py::TestGroup::test_in_class
 *
 * 7 tests collected in 0.00s
 * ```
 *
 * A file that raises while being imported is still an error pytest reports *around* a successful
 * collection of everything else, on stdout, in the short summary:
 *
 * ```
 * ERROR tests/test_pyerr.py - RuntimeError: boom at import
 * ```
 *
 * A project that does not type-check never reaches pytest at all: `by run` transpiles first, and a
 * failure there exits non-zero having written its diagnostics to *stderr* with no node ids on
 * stdout. That is [summarize]'s case.
 */
internal object ByPytestCollect {

    /** Ask pytest to collect without running. */
    const val COLLECT_ONLY: String = "--collect-only"

    /** One node id per line instead of the indented object listing; see the class doc. */
    const val QUIET: String = "-q"

    /** The arguments that follow `by run`. */
    fun arguments(): List<String> = listOf(ByPytest.MODULE, COLLECT_ONLY, QUIET)

    /**
     * A node id line: a path ending in `.py`, optionally followed by `::`-separated names.
     *
     * The path may not contain whitespace, but everything after `::` may — a parametrized case is
     * `test_add[1 - 2]` whenever the parameters render with spaces. Anchoring at both ends is what
     * keeps the lines of a failure report out: `_____ ERROR collecting tests/test_x.py _____` and
     * `tests/test_x.py:6: in <module>` both carry a `.py`, and neither is one of these.
     */
    private val NODE_ID = Regex("""^(\S+\.py)(?:::(.+))?$""")

    /** A short-summary error line: `ERROR tests/test_x.py - RuntimeError: boom`. */
    private val ERROR_LINE = Regex("""^ERROR\s+(\S+?)(?:\s+-\s+(.*))?$""")

    /** A `by` diagnostic's first line: `error[unresolved-import]: Cannot resolve …`. */
    private val BY_DIAGNOSTIC = Regex("""^error(\[[^]]*])?:.*""")

    /** The location line under a `by` diagnostic: ` --> tests/test_x.by:1:8`. */
    private val BY_LOCATION = Regex("""^-->\s*(\S+)""")

    /** `by`'s footer when it refuses to run: `Found 398 diagnostics` / `Found 1 diagnostic`. */
    private val DIAGNOSTIC_COUNT = Regex("""Found (\d+) diagnostics?""")

    /**
     * What [stdout] and [stderr] of one collection run amount to.
     *
     * pytest writes both the node ids and its own error summary to stdout; [stderr] carries `by`'s
     * diagnostics, and is only consulted when the run failed without collecting anything, since a
     * successful collection can still print warnings there.
     */
    fun parse(stdout: String, stderr: String, exitCode: Int): ByCollection {
        val nodeIds = ArrayList<String>()
        val errors = ArrayList<ByCollectionError>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (NODE_ID.matches(line)) {
                nodeIds += line
                continue
            }
            val error = ERROR_LINE.matchEntire(line) ?: continue
            errors += ByCollectionError(
                target = error.groupValues[1],
                message = error.groupValues[2].ifBlank { COLLECTION_FAILED },
            )
        }
        if (nodeIds.isEmpty() && errors.isEmpty() && exitCode != 0 && exitCode != NO_TESTS_COLLECTED) {
            errors += ByCollectionError(null, summarize(stderr, stdout, exitCode))
        }
        return ByCollection(nodeIds.distinct(), errors)
    }

    /**
     * One line describing a run that produced no tests and no pytest error of its own.
     *
     * `by run` type-checks the project and refuses to run anything while it has diagnostics —
     * pytest is never reached, so *every* test disappears from the view because of code that may
     * have nothing to do with the tests. That is by far the most confusing way for this to fail,
     * and the one users hit when the IDE disagrees with the `pytest --collect-only` they just ran
     * by hand, so it is reported as what it is. Its own count is the summary: quoting the first of
     * 398 diagnostics would suggest that one is special.
     *
     * A single diagnostic is worth quoting, though: the first `error:` line says what is wrong and
     * the ` --> ` line under it says where, while the pages of `info:` context that follow are
     * dropped. Anything else falls back to the first line of output there is, and to the exit code
     * when there is none. The whole report is in *View Collection Output* either way.
     */
    private fun summarize(stderr: String, stdout: String, exitCode: Int): String {
        val lines = (stderr.lineSequence() + stdout.lineSequence())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val diagnostics = lines.firstNotNullOfOrNull { DIAGNOSTIC_COUNT.matchEntire(it) }
            ?.groupValues?.get(1)?.toIntOrNull()
        if (diagnostics != null && diagnostics > 1) {
            return "by run stopped on $diagnostics diagnostics, so no tests were collected — " +
                "the project has to type-check before its tests can run"
        }
        val diagnostic = lines.indexOfFirst { BY_DIAGNOSTIC.matches(it) }
        if (diagnostic >= 0) {
            val message = lines[diagnostic]
            val where = lines.getOrNull(diagnostic + 1)?.let { BY_LOCATION.find(it)?.groupValues?.get(1) }
            return if (where == null) message else "$message ($where)"
        }
        return lines.firstOrNull() ?: "by run pytest --collect-only exited with code $exitCode"
    }

    private const val COLLECTION_FAILED = "collection failed"

    /**
     * pytest's exit code for "collection ran fine and found nothing", which `by run` passes
     * through unchanged (observed: a project with no test file exits 5 having printed
     * `no tests collected in 0.00s`). An empty tree already says this; an error node would be
     * saying it twice, in red.
     */
    private const val NO_TESTS_COLLECTED = 5
}

package dev.basedpython.pycharm.run.test.node

/**
 * The raw record of one collection: the command, where it ran, and everything it printed.
 *
 * Kept because the tree can only ever be a summary, and the question it cannot answer is the one
 * users actually have — *why does the IDE see different tests from the `pytest --collect-only` I
 * just typed?* The answer is almost always visible in this text and nowhere else: the plugin runs
 * pytest through `by run`, which transpiles the project into a temp directory and runs pytest
 * *there*, so the rootdir differs, `[tool.pytest.ini_options]` and a hand-written `conftest.py` are
 * not picked up, and only `.by` files are in the tree pytest walks.
 */
internal data class ByCollectionRun(
    val commandLine: String,
    val workingDirectory: String?,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    /**
     * Wall-clock time the run started, already formatted.
     *
     * Preformatted so that rendering stays pure and testable, and shown because the output tab is a
     * snapshot: with it, a tab left open across a Refresh says which run it is instead of quietly
     * describing the previous one.
     */
    val startedAt: String,
    /** Set when the run never happened, e.g. no `by` binary was found. */
    val failure: String? = null,
)

/** Renders a [ByCollectionRun] as the text the "View Collection Output" action opens. */
internal object ByCollectionOutput {

    /** Name of the read-only tab; also what the user sees in the editor tab strip. */
    const val FILE_NAME: String = "basedpython-collect-only.log"

    fun render(run: ByCollectionRun?): String {
        if (run == null) return NOTHING_YET
        return buildString {
            appendLine("$ ${run.commandLine}")
            run.workingDirectory?.let { appendLine("  working directory: $it") }
            appendLine("  started at ${run.startedAt}")
            if (run.failure != null) {
                appendLine("  did not run: ${run.failure}")
            } else {
                appendLine("  exit code ${run.exitCode}, ${run.durationMillis} ms")
            }
            appendLine()
            appendSection("stdout", run.stdout)
            appendLine()
            appendSection("stderr", run.stderr)
            if (run.failure == null) {
                appendLine()
                appendLine(WHY_IT_DIFFERS)
            }
        }
    }

    private fun StringBuilder.appendSection(name: String, text: String) {
        appendLine("--- $name ---")
        if (text.isBlank()) appendLine("(empty)") else appendLine(text.trimEnd())
    }

    private const val NOTHING_YET =
        "No collection has run yet. Press Refresh in the basedpython Tests tool window."

    /**
     * The footer exists because the difference from a hand-typed `pytest --collect-only` is a
     * property of how the plugin runs pytest at all, not of anything the user did wrong — and
     * without saying so, the output above looks like a bug rather than an explanation.
     */
    private val WHY_IT_DIFFERS = """
        --- why this can differ from running pytest yourself ---
        Tests run through `by run pytest`, which transpiles the project into a temporary
        directory and runs pytest there. So:
          - pytest's rootdir is that temp directory, not the project, and
            [tool.pytest.ini_options] in pyproject.toml, pytest.ini, tox.ini and setup.cfg
            are not read;
          - only .by files are transpiled into it, so tests in .py files are not collected,
            and a conftest.py is not either (a conftest.by is);
          - the interpreter is the one `by run` picks, which needs pytest importable.
    """.trimIndent()
}

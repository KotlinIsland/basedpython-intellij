package dev.basedpython.pycharm.run.test.tree

/**
 * Pure, line-oriented parser for the test output of `by run pytest -v` (see
 * [dev.basedpython.pycharm.run.test.ByPytest]). Unittest's verbose format is recognised too.
 *
 * The parser is intentionally tolerant: it is fed one raw output line at a time via
 * [parseLine] and returns the (possibly empty) list of [ByTestEvent]s that line
 * implies. Unknown / decorative lines yield no events. State (such as the currently
 * "started" suite) is tracked so that suite open/close events are balanced.
 *
 * Recognized signals:
 *  - pytest verbose: `path/to/test_x.py::test_name PASSED|FAILED|SKIPPED|ERROR`
 *    optionally trailed by a progress tag like `[ 12%]`. Verbose is why the configuration
 *    always passes `-v`: without it pytest prints only a one-character progress line, which
 *    carries no names to build a tree from.
 *  - unittest verbose: `test_name (module.Class) ... ok|FAIL|ERROR|skipped`.
 *  - the pytest summary line: `=== 3 passed, 1 failed in 0.12s ===`.
 *  - a pytest one-char progress/summary line such as `....F..s` (fallback count only;
 *    exposed via [parseSummaryDots], not emitted as tree events to avoid duplicate nodes).
 */
class ByTestOutputParser {

    /**
     * The suites currently open, outermost first — `["tests/test_math.py", "TestGroup"]`.
     *
     * A stack rather than a single name because pytest node ids nest: a test in a class is
     * `tests/test_math.py::TestGroup::test_in_class`, and collapsing that to one level (which is
     * what taking everything before the first `::` used to do) dropped the class from the tree
     * entirely.
     */
    private val openSuites = ArrayList<String>()

    /** Per-test outcome tally derived from emitted events (diagnostic / fallback). */
    var passed: Int = 0; private set
    var failed: Int = 0; private set
    var ignored: Int = 0; private set

    /**
     * Parse a single line of output, returning the events it produces (order matters).
     * The returned list is never null; an empty list means "nothing recognized".
     */
    fun parseLine(rawLine: String): List<ByTestEvent> {
        val line = rawLine.trimEnd('\r', '\n')
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()

        parsePytestVerbose(trimmed)?.let { return it }
        parseUnittestVerbose(trimmed)?.let { return it }
        // Summary lines are recognized but produce no tree events (counts are folded in).
        parseSummaryLine(trimmed)
        return emptyList()
    }

    /** Convenience: parse a whole multi-line blob, accumulating all events. */
    fun parseAll(text: String): List<ByTestEvent> {
        val out = ArrayList<ByTestEvent>()
        for (l in text.split('\n')) out.addAll(parseLine(l))
        out.addAll(finish())
        return out
    }

    /**
     * Flush trailing state: closes any suite that is still open. Call once after the
     * last line of output has been parsed.
     */
    fun finish(): List<ByTestEvent> = closeSuitesBelow(0)

    // ---- pytest verbose ----------------------------------------------------

    /**
     * Matches e.g. `tests/test_math.py::test_add PASSED [ 50%]`.
     * Group 1 = node id (path::test), group 2 = outcome word.
     */
    private val pytestLine = Regex(
        """^(\S+::\S+)\s+(PASSED|FAILED|ERROR|SKIPPED|XFAIL|XPASS)\b.*$""",
        RegexOption.IGNORE_CASE,
    )

    private fun parsePytestVerbose(line: String): List<ByTestEvent>? {
        val m = pytestLine.matchEntire(line) ?: return null
        val nodeId = m.groupValues[1]
        val outcome = m.groupValues[2].uppercase()

        // "tests/test_math.py::TestGroup::test_in_class" -> suites [file, TestGroup], test last.
        val parts = nodeId.split("::")
        val suites = parts.dropLast(1)
        val testName = parts.last()

        val events = ArrayList<ByTestEvent>()
        events.addAll(ensureSuites(suites) { depth -> locationHint(parts.take(depth + 1)) })
        events.add(ByTestEvent.TestStarted(testName, locationHint(parts)))
        events.add(outcomeEvent(testName, outcome, line))
        events.add(ByTestEvent.TestFinished(testName))
        return events
    }

    /**
     * The `by_test://` URL for a node id, already split on `::`.
     *
     * Emitted with the extension pytest reported. The node id names the *transpiled* tree, which
     * differs from the `.by` source only in that extension (see [dev.basedpython.pycharm.run.test.ByPytest]);
     * mapping it back is [ByTestLocator]'s job, since only it knows the project layout.
     */
    private fun locationHint(parts: List<String>): String? {
        if (parts.isEmpty() || !parts[0].endsWith(".py", ignoreCase = true)) return null
        return ByTestLocator.PROTOCOL + "://" + parts.joinToString("::")
    }

    // ---- unittest verbose --------------------------------------------------

    /**
     * Matches e.g. `test_add (mymod.MathTest) ... ok` or `... FAIL` / `ERROR` /
     * `skipped 'reason'`. Group 1 = test, group 2 = qualifier, group 3 = outcome,
     * group 4 = optional trailing reason.
     */
    private val unittestLine = Regex(
        """^(\w+)\s+\(([^)]+)\)\s*(?:\.\.\.)?\s*(ok|OK|FAIL|ERROR|skipped|expected failure|unexpected success)\b(.*)$""",
    )

    private fun parseUnittestVerbose(line: String): List<ByTestEvent>? {
        val m = unittestLine.matchEntire(line) ?: return null
        val testName = m.groupValues[1]
        val suite = m.groupValues[2]
        val outcome = m.groupValues[3]
        val tail = m.groupValues[4].trim()

        val events = ArrayList<ByTestEvent>()
        // No location hint: unittest reports a dotted module, not a path, and guessing a file
        // layout from it would produce nodes that navigate to the wrong place.
        events.addAll(ensureSuites(listOf(suite)) { null })
        events.add(ByTestEvent.TestStarted(testName))
        events.add(
            when (outcome.lowercase()) {
                "ok" -> { passed++; ByTestEvent.TestPassed(testName) }
                "fail" -> { failed++; ByTestEvent.TestFailed(testName, tail.ifEmpty { "test failed" }, line) }
                "error" -> { failed++; ByTestEvent.TestFailed(testName, tail.ifEmpty { "error" }, line) }
                "skipped" -> { ignored++; ByTestEvent.TestIgnored(testName, tail.trim('\'', '"', ' ')) }
                "expected failure" -> { ignored++; ByTestEvent.TestIgnored(testName, "expected failure") }
                "unexpected success" -> { failed++; ByTestEvent.TestFailed(testName, "unexpected success", line) }
                else -> { passed++; ByTestEvent.TestPassed(testName) }
            }
        )
        events.add(ByTestEvent.TestFinished(testName))
        return events
    }

    // ---- shared outcome mapping -------------------------------------------

    private fun outcomeEvent(testName: String, outcome: String, line: String): ByTestEvent =
        when (outcome) {
            "PASSED", "XPASS" -> { passed++; ByTestEvent.TestPassed(testName) }
            "FAILED", "ERROR" -> { failed++; ByTestEvent.TestFailed(testName, "$outcome: $testName", line) }
            "SKIPPED", "XFAIL" -> { ignored++; ByTestEvent.TestIgnored(testName, outcome) }
            else -> { passed++; ByTestEvent.TestPassed(testName) }
        }

    /**
     * Reconcile the open suite stack with [suites], closing what no longer applies and opening
     * what is new. [hintAt] supplies the location hint for the suite at a given depth.
     *
     * Only the differing tail moves: consecutive tests in the same class keep both the file and
     * the class node open, and a jump to another class closes just that one.
     */
    private fun ensureSuites(suites: List<String>, hintAt: (Int) -> String?): List<ByTestEvent> {
        var shared = 0
        while (shared < suites.size && shared < openSuites.size && suites[shared] == openSuites[shared]) {
            shared++
        }
        val events = ArrayList<ByTestEvent>(closeSuitesBelow(shared))
        for (depth in shared until suites.size) {
            events.add(ByTestEvent.TestSuiteStarted(suites[depth], hintAt(depth)))
            openSuites.add(suites[depth])
        }
        return events
    }

    /** Close every open suite deeper than [depth], innermost first so the pairs stay balanced. */
    private fun closeSuitesBelow(depth: Int): List<ByTestEvent> {
        val events = ArrayList<ByTestEvent>()
        while (openSuites.size > depth) {
            events.add(ByTestEvent.SuiteFinished(openSuites.removeAt(openSuites.size - 1)))
        }
        return events
    }

    // ---- summary lines -----------------------------------------------------

    /** e.g. `=== 3 passed, 1 failed, 2 skipped in 0.12s ===` */
    private val summaryLine = Regex(""".*\b(\d+)\s+(passed|failed|error|errors|skipped)\b.*""")

    /** Recognizes a pytest summary footer; folds counts in but emits nothing. */
    private fun parseSummaryLine(line: String): Boolean {
        if (!line.contains("passed") && !line.contains("failed") &&
            !line.contains("error") && !line.contains("skipped")
        ) return false
        // Only treat as a summary footer when it carries a count token.
        return summaryLine.containsMatchIn(line)
    }

    /**
     * Fallback parser for a pytest one-char progress line such as `....F..s`.
     * Returns a triple of (passed, failed, skipped) counts, or null if [line] is
     * not a pure progress line. Pure helper — does not mutate parser state.
     */
    fun parseSummaryDots(line: String): Triple<Int, Int, Int>? {
        val t = line.trim()
        if (t.isEmpty()) return null
        // Strip a trailing progress tag like `[100%]`.
        val core = t.substringBefore('[').trim()
        if (core.isEmpty()) return null
        if (!core.all { it in ".FEsxX" }) return null
        var p = 0; var f = 0; var s = 0
        for (c in core) when (c) {
            '.' -> p++
            'F', 'E' -> f++
            's', 'x', 'X' -> s++
        }
        return Triple(p, f, s)
    }
}

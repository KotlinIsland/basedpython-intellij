package dev.basedpython.pycharm.run.test.tree

/**
 * Pure, line-oriented parser for `by test` output (which is pytest/unittest-like).
 *
 * The parser is intentionally tolerant: it is fed one raw output line at a time via
 * [parseLine] and returns the (possibly empty) list of [ByTestEvent]s that line
 * implies. Unknown / decorative lines yield no events. State (such as the currently
 * "started" suite) is tracked so that suite open/close events are balanced.
 *
 * Recognized signals:
 *  - pytest verbose: `path/to/test_x.py::test_name PASSED|FAILED|SKIPPED|ERROR`
 *    optionally trailed by a progress tag like `[ 12%]`.
 *  - unittest verbose: `test_name (module.Class) ... ok|FAIL|ERROR|skipped`.
 *  - the pytest summary line: `=== 3 passed, 1 failed in 0.12s ===`.
 *  - a pytest one-char progress/summary line such as `....F..s` (fallback count only;
 *    exposed via [parseSummaryDots], not emitted as tree events to avoid duplicate nodes).
 */
class ByTestOutputParser {

    /** A started-but-not-finished suite name, or null when no suite is open. */
    private var openSuite: String? = null

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
    fun finish(): List<ByTestEvent> {
        val s = openSuite ?: return emptyList()
        openSuite = null
        return listOf(ByTestEvent.SuiteFinished(s))
    }

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
        val suite = suiteOf(nodeId)
        val testName = nodeId.substringAfterLast("::")

        val events = ArrayList<ByTestEvent>()
        events.addAll(ensureSuite(suite))
        events.add(ByTestEvent.TestStarted(testName))
        events.add(outcomeEvent(testName, outcome, line))
        events.add(ByTestEvent.TestFinished(testName))
        return events
    }

    /** Suite name for a pytest node id: the file path before `::`. */
    private fun suiteOf(nodeId: String): String = nodeId.substringBefore("::")

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
        events.addAll(ensureSuite(suite))
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

    /** Emit a SuiteFinished/TestSuiteStarted pair when the suite changes. */
    private fun ensureSuite(suite: String): List<ByTestEvent> {
        if (openSuite == suite) return emptyList()
        val events = ArrayList<ByTestEvent>()
        openSuite?.let { events.add(ByTestEvent.SuiteFinished(it)) }
        events.add(ByTestEvent.TestSuiteStarted(suite))
        openSuite = suite
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

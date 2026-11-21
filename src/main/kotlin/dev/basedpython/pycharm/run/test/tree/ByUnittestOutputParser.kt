package dev.basedpython.pycharm.run.test.tree

/**
 * Pure, line-oriented parser for what unittest's text runner prints at verbosity 2, which is what
 * `manage.py test -v 2` prints: one status line per test, and every failure's traceback only after the
 * last test has run.
 *
 * Measured on django 5.2 under python 3.15, every shape below is one it printed:
 *
 * ```
 * test_create (polls.tests.test_views.QuestionTests.test_create) ... ok
 * test_fail (polls.tests.test_views.QuestionTests.test_fail)
 * Counts wrongly on purpose. ... FAIL
 * test_sub (polls.tests.test_views.QuestionTests.test_sub) ...
 *   test_sub (polls.tests.test_views.QuestionTests.test_sub) (i=2) ... FAIL
 * test_skip (polls.tests.test_views.OtherTests.test_skip) ... skipped 'not today'
 *   Applying sessions.0001_initial...test_create (polls.tests.test_views.QuestionTests.test_create) ... ok
 *
 * ======================================================================
 * FAIL: test_fail (polls.tests.test_views.QuestionTests.test_fail)
 * Counts wrongly on purpose.
 * ----------------------------------------------------------------------
 * Traceback (most recent call last):
 *   …
 * AssertionError: 0 != 2
 * ```
 *
 * - The qualifier names the test's class and, from python 3.11, the test itself too; before 3.11 it
 *   is the class alone.
 * - A test with a docstring prints the docstring's first line, and the status after it, on the next
 *   line.
 * - A failing subtest is a line of its own under its test, indented, with the subtest's parameters.
 * - The runner writes to stderr and the program to stdout, so a status line can arrive on the end of
 *   what the program was printing — django's migration progress above, which has no newline until
 *   its ` OK`. The status is found anywhere in a line, and what comes before it is the program's.
 *
 * Every failure's traceback comes after every test, so a test that failed stays open until its
 * traceback has been read, and the events are for a tree built by node id
 * ([ByUnittestEvent.parentId]), where a test can finish after its siblings and its class have moved on.
 *
 * @param moduleFile the path, as a node id names it, of the file a dotted module is — relative to the
 *   project for a module of the project, or to the tree `by run` staged
 * @param fileSuiteName what a file's suite is called in the tree, given that path
 * @param labels the labels the run was given, which name a module that failed to import: the runner
 *   reports it by the last part of its name alone when a label named it, and by all of it when it was
 *   discovered
 */
class ByUnittestOutputParser(
    private val moduleFile: (String) -> String,
    private val fileSuiteName: (String) -> String = { it },
    private val labels: List<String> = emptyList(),
) {

    /** A test the runner reported, by the node id it is given. */
    private class Test(val id: String, val parentId: String, val name: String) {
        /** `FAIL`, `ERROR` or `unexpected success` once a status says so; a subtest's failure counts. */
        var failure: String? = null

        /** The tracebacks read for it, each with what it was for. */
        val blocks = ArrayList<Pair<String, String>>()
    }

    /** Suites opened, in order, so they close innermost first. */
    private val openSuites = LinkedHashMap<String, String>()

    /** Tests started and not yet finished, in the order they started. */
    private val open = LinkedHashMap<String, Test>()

    /** A test whose status line was cut by its docstring: `test_fail (q)` with the status on the next line. */
    private var header: Pair<String, String>? = null

    /** The traceback being read: the test it is for, its subtest's parameters, and its lines. */
    private var block: Triple<Test, String?, StringBuilder>? = null
    private var blockPastRule = false
    private var finished = false

    fun parseLine(rawLine: String): List<ByUnittestEvent> {
        val line = rawLine.trimEnd('\r', '\n')
        if (block != null) return readBlock(line)
        BLOCK_HEADER.matchEntire(line)?.let { return startBlock(it) }
        if (RAN.matches(line)) return flush()
        header?.let { (name, qualifier) ->
            header = null
            DOCSTRING_STATUS.matchEntire(line)?.let { return status(name, qualifier, null, it.groupValues[1], "") }
        }
        STATUS.find(line)?.let { match ->
            val before = line.substring(0, match.range.first)
            val (name, qualifier, params, outcome) = match.destructured
            val events = ArrayList<ByUnittestEvent>()
            if (before.isNotBlank()) events += ByUnittestEvent.Output(before)
            events += status(name, qualifier, params.ifEmpty { null }, outcome.trim(), line)
            return events
        }
        HEADER_ALONE.matchEntire(line)?.let { header = it.groupValues[1] to it.groupValues[2]; return emptyList() }
        return emptyList()
    }

    /** Closes everything still open: the run ended, or was stopped, without its tracebacks. */
    fun finish(): List<ByUnittestEvent> = if (finished) emptyList() else flush()

    private fun status(name: String, qualifier: String, params: String?, outcome: String, line: String): List<ByUnittestEvent> {
        val events = ArrayList<ByUnittestEvent>()
        val test = test(name, qualifier, events)
        when {
            params != null -> if (outcome.startsWith("FAIL") || outcome.startsWith("ERROR")) test.failure = outcome.substringBefore(' ')
            outcome == "ok" -> events += finish(test, null)
            outcome.startsWith("skipped") -> {
                events += ByUnittestEvent.TestIgnored(test.id, test.name, outcome.removePrefix("skipped").trim().trim('\'', '"'))
                events += finish(test, null)
            }
            outcome == "expected failure" -> {
                events += ByUnittestEvent.TestIgnored(test.id, test.name, outcome)
                events += finish(test, null)
            }
            outcome == "FAIL" || outcome == "ERROR" || outcome == "unexpected success" -> test.failure = outcome
            // `test_sub (…) ... ` with its subtests to follow: open until they, or the run, are done
            else -> Unit
        }
        return events
    }

    /** The test [name] in [qualifier], started — with the suites it is in — the first time it is seen. */
    private fun test(name: String, qualifier: String, events: MutableList<ByUnittestEvent>): Test {
        val (file, symbols) = place(name, qualifier)
        val id = (listOf(file) + symbols).joinToString("::")
        open[id]?.let { return it }
        var parent = ROOT
        events += suite(file, parent, fileSuiteName(file), file)
        parent = file
        for (depth in 0 until symbols.size - 1) {
            val suiteId = (listOf(file) + symbols.take(depth + 1)).joinToString("::")
            events += suite(suiteId, parent, symbols[depth], suiteId)
            parent = suiteId
        }
        val test = Test(id, parent, symbols.last())
        open[id] = test
        events += ByUnittestEvent.TestStarted(id, parent, test.name, hint(id))
        return test
    }

    /**
     * Where a test is: the file its module is, and the names under it. `name (module.Class.name)` from
     * python 3.11, `name (module.Class)` before it. A module that failed to import is reported as a
     * test of `unittest.loader._FailedTest` named after the module, and is that file: `polls.tests.
     * test_bad` when discovered, `test_bad` when the label `polls.tests.test_bad` named it.
     */
    private fun place(name: String, qualifier: String): Pair<String, List<String>> {
        if (qualifier.startsWith(FAILED_IMPORT)) {
            val module = if ('.' in name) name else labels.firstNotNullOfOrNull { label ->
                val parts = label.split('.')
                parts.indexOf(name).takeIf { it >= 0 }?.let { parts.take(it + 1).joinToString(".") }
            } ?: name
            return moduleFile(module) to listOf(module.substringAfterLast('.'))
        }
        val owner = qualifier.removeSuffix(".$name")
        val module = owner.substringBeforeLast('.', "")
        val className = owner.substringAfterLast('.')
        return if (module.isEmpty()) moduleFile(owner) to listOf(name) else moduleFile(module) to listOf(className, name)
    }

    private fun suite(id: String, parentId: String, name: String, nodeId: String): List<ByUnittestEvent> {
        if (id in openSuites) return emptyList()
        openSuites[id] = name
        return listOf(ByUnittestEvent.SuiteStarted(id, parentId, name, hint(nodeId)))
    }

    private fun hint(nodeId: String): String = ByTestLocator.PROTOCOL + "://" + nodeId

    private fun startBlock(match: MatchResult): List<ByUnittestEvent> {
        val (kind, name, qualifier, params) = match.destructured
        val events = ArrayList<ByUnittestEvent>()
        val test = test(name, qualifier, events)
        if (test.failure == null) test.failure = kind
        block = Triple(test, params.ifEmpty { null }, StringBuilder())
        blockPastRule = false
        return events
    }

    private fun readBlock(line: String): List<ByUnittestEvent> {
        val (test, params, text) = block ?: return emptyList()
        if (!blockPastRule) {
            // the docstring's first line, as in the status, then the rule the traceback follows
            if (RULE.matches(line)) blockPastRule = true
            return emptyList()
        }
        val ends = RULE.matches(line) || DOUBLE_RULE.matches(line)
        if (!ends) {
            text.append(line).append('\n')
            return emptyList()
        }
        test.blocks += (params?.let { "($it) " } ?: "") to text.toString().trimEnd()
        block = null
        return emptyList()
    }

    /** Everything open, finished: the tracebacks have all been read. */
    private fun flush(): List<ByUnittestEvent> {
        finished = true
        block?.let { (test, params, text) -> test.blocks += (params?.let { "($it) " } ?: "") to text.toString().trimEnd() }
        block = null
        val events = ArrayList<ByUnittestEvent>()
        for (test in open.values.toList()) events += finish(test, test.failure)
        for ((id, name) in openSuites.entries.reversed()) events += ByUnittestEvent.SuiteFinished(id, name)
        openSuites.clear()
        return events
    }

    private fun finish(test: Test, failure: String?): List<ByUnittestEvent> {
        open.remove(test.id)
        val events = ArrayList<ByUnittestEvent>()
        if (failure != null) {
            val message = test.blocks.firstOrNull()?.let { (subtest, traceback) -> subtest + (exception(traceback) ?: failure) } ?: failure
            val details = test.blocks.joinToString("\n\n") { (subtest, traceback) -> subtest + traceback }
            events += ByUnittestEvent.TestFailed(test.id, test.name, message, details)
        }
        events += ByUnittestEvent.TestFinished(test.id, test.name)
        return events
    }

    /**
     * The exception a traceback ends in: the first line after its last `Traceback (most recent call
     * last):` that is not a frame, which are indented. Not the last line: `assertEqual` on two strings
     * follows `AssertionError: 'hello a' != 'bye'` with a diff of them, `- hello a` and `+ bye`.
     */
    private fun exception(traceback: String): String? {
        val lines = traceback.lines()
        val start = lines.indexOfLast { it.startsWith(TRACEBACK) }
        return lines.drop(start + 1).firstOrNull { it.isNotBlank() && !it.first().isWhitespace() }?.trim()
            ?: lines.lastOrNull { it.isNotBlank() }?.trim()
    }

    private companion object {
        const val ROOT = "0"
        const val TRACEBACK = "Traceback (most recent call last):"
        const val FAILED_IMPORT = "unittest.loader._FailedTest"

        /**
         * `name (qualifier) [(subtest params)] ... [outcome]`, anywhere in the line. The name has no two
         * dots running, so `initial...test_create (q)` is `test_create`, not all of it.
         */
        val STATUS = Regex("""(\w+(?:\.\w+)*) \(([\w.]+)\)(?: \((.*?)\))? \.\.\. ?(ok|FAIL|ERROR|skipped.*|expected failure|unexpected success|)$""")

        /** The status of a test whose docstring line came between: `Counts wrongly on purpose. ... FAIL`. */
        val DOCSTRING_STATUS = Regex("""^.* \.\.\. ?(ok|FAIL|ERROR|skipped.*|expected failure|unexpected success)$""")

        /** `name (qualifier)` and nothing else: its docstring and status follow. */
        val HEADER_ALONE = Regex("""^([\w.]+) \(([\w.]+)\)$""")

        /** `FAIL: name (qualifier) [(subtest params)]`, the start of a traceback. */
        val BLOCK_HEADER = Regex("""^(FAIL|ERROR|UNEXPECTED SUCCESS): ([\w.]+) \(([\w.]+)\)(?: \((.*)\))?$""")

        val RULE = Regex("""^-{20,}$""")
        val DOUBLE_RULE = Regex("""^={20,}$""")
        val RAN = Regex("""^Ran \d+ tests? in .*$""")
    }
}

/**
 * A test event of a tree built by node id: [parentId] says where each node goes, so a test can finish
 * after the runner has moved past its class. `0` is the root.
 */
sealed interface ByUnittestEvent {
    data class SuiteStarted(val id: String, val parentId: String, val name: String, val locationHint: String) : ByUnittestEvent
    data class SuiteFinished(val id: String, val name: String) : ByUnittestEvent
    data class TestStarted(val id: String, val parentId: String, val name: String, val locationHint: String) : ByUnittestEvent
    data class TestFailed(val id: String, val name: String, val message: String, val details: String) : ByUnittestEvent
    data class TestIgnored(val id: String, val name: String, val message: String) : ByUnittestEvent
    data class TestFinished(val id: String, val name: String) : ByUnittestEvent

    /** What the program printed on the line the runner's status was written on the end of. */
    data class Output(val text: String) : ByUnittestEvent
}

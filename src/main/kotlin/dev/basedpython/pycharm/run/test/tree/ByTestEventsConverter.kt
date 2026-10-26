package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import dev.basedpython.pycharm.run.model.ByProgramModel

/**
 * Bridges `by run pytest` output into the SMTRunner test tree.
 *
 * A `by` with a test reporter ([dev.basedpython.pycharm.run.test.ByPytest.REPORTER_VARIABLE]) writes
 * the run as TeamCity service messages itself, starting with `##teamcity[enteredTheMatrix]`. From that
 * message on, everything goes to the base converter untouched: the messages build the tree, with
 * pytest's report of each failure, a failed `==`'s expected and actual values, the `.by` line and
 * node id of each test, and its captured output, and pytest's own `-v` lines are just output.
 *
 * Until it arrives — plain pytest, or a `by` without the reporter — the base
 * [OutputToGeneralTestEventsConverter] hands us already-buffered, newline-consistent text via
 * [processConsistentText]. We run each line through the pure [ByTestOutputParser], turn the
 * resulting [ByTestEvent]s into TeamCity service messages (via the platform's
 * [ServiceMessageBuilder]), and re-feed those to the base converter so the standard
 * `GeneralTestEventsProcessor` builds the tree.
 *
 * All recognized lines are consumed; unrecognized text still flows to the console
 * via the base implementation so nothing is lost.
 */
class ByTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    /**
     * A test file's suite is named after the `.by` file `by` staged it from ([ByProgramModel.stagedFile],
     * `by`'s answer), relative to the project, as the `.py` it ran as means nothing in a `.by` project;
     * a file `by` has not placed — a plain `.py` test, or before the answer — keeps pytest's path.
     */
    private val parser = ByTestOutputParser { staged ->
        val project = consoleProperties.project
        val source = ByProgramModel.getInstance(project).stagedFile(staged)
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        source?.let { file -> base?.let { VfsUtilCore.getRelativePath(file, it) } } ?: staged
    }
    private var finished = false

    /** Whether the run's own reporter has said it speaks for the run; see the class comment. */
    private var reported = false

    override fun processConsistentText(text: String, outputType: Key<*>) {
        if (!reported && text.trimStart().startsWith(ENTERED_THE_MATRIX)) {
            reported = true
            // nothing the parser opened can be closed by the reporter, which knows nothing of it
            for (event in parser.finish()) emit(event, outputType)
        }
        if (reported) {
            super.processConsistentText(text, outputType)
            return
        }
        var handledAny = false
        for (rawLine in text.split('\n')) {
            if (rawLine.isEmpty()) continue
            val events = parser.parseLine(rawLine)
            if (events.isEmpty()) continue
            handledAny = true
            for (event in events) emit(event, outputType)
        }
        if (!handledAny) {
            // Pass through plain output (e.g. compiler errors, banners) unchanged.
            super.processConsistentText(text, outputType)
        }
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        if (!finished) {
            finished = true
            for (event in parser.finish()) emit(event, KEY)
        }
        super.flushBufferOnProcessTermination(exitCode)
    }

    /** Translate one [ByTestEvent] to a [ServiceMessageBuilder] and feed it back. */
    private fun emit(event: ByTestEvent, outputType: Key<*>) {
        val builder: ServiceMessageBuilder = when (event) {
            is ByTestEvent.TestSuiteStarted ->
                ServiceMessageBuilder.testSuiteStarted(event.name)
                    .also { b -> event.locationHint?.let { b.addAttribute("locationHint", it) } }

            is ByTestEvent.SuiteFinished ->
                ServiceMessageBuilder.testSuiteFinished(event.name)

            is ByTestEvent.TestStarted ->
                ServiceMessageBuilder.testStarted(event.name)
                    .also { b -> event.locationHint?.let { b.addAttribute("locationHint", it) } }
                    .addAttribute("captureStandardOutput", "true")

            is ByTestEvent.TestPassed ->
                ServiceMessageBuilder.testFinished(event.name)

            is ByTestEvent.TestFailed ->
                ServiceMessageBuilder.testFailed(event.name)
                    .addAttribute("message", event.message)
                    .also { if (event.details.isNotEmpty()) it.addAttribute("details", event.details) }

            is ByTestEvent.TestIgnored ->
                ServiceMessageBuilder.testIgnored(event.name)
                    .also { if (event.message.isNotEmpty()) it.addAttribute("message", event.message) }

            is ByTestEvent.TestFinished ->
                ServiceMessageBuilder.testFinished(event.name)
        }
        super.processConsistentText(builder.toString() + "\n", outputType)
    }

    private companion object {
        val KEY: Key<*> = Key.create<Any>("by.test.flush")

        /** The message a TeamCity reporter starts with, before its first test. */
        const val ENTERED_THE_MATRIX = "##teamcity[enteredTheMatrix"
    }
}

package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.util.Key

/**
 * Bridges `by test` process output into the SMTRunner test tree.
 *
 * The base [OutputToGeneralTestEventsConverter] hands us already-buffered,
 * newline-consistent text via [processConsistentText]. We run each line through
 * the pure [ByTestOutputParser], turn the resulting [ByTestEvent]s into TeamCity
 * service messages (via the platform's [ServiceMessageBuilder]), and re-feed those
 * to the base converter so the standard `GeneralTestEventsProcessor` builds the tree.
 *
 * All recognized lines are consumed; unrecognized text still flows to the console
 * via the base implementation so nothing is lost.
 */
class ByTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    private val parser = ByTestOutputParser()
    private var finished = false

    override fun processConsistentText(text: String, outputType: Key<*>) {
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

            is ByTestEvent.SuiteFinished ->
                ServiceMessageBuilder.testSuiteFinished(event.name)

            is ByTestEvent.TestStarted ->
                ServiceMessageBuilder.testStarted(event.name)
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
    }
}

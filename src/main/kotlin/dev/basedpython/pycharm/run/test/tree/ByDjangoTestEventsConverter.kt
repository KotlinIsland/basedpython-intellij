package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import dev.basedpython.pycharm.run.model.ByProgramModel

/**
 * Bridges a run of django's `manage.py test -v 2` into a results tree built by node id.
 *
 * A `by` whose test reporter speaks for django's runner writes the run as service messages itself,
 * starting with `##teamcity[enteredTheMatrix]`, each carrying a `nodeId` and a `parentNodeId`, and from
 * that message on everything goes to the base converter untouched — as a pytest run's reporter does
 * ([ByTestEventsConverter]). Until it arrives, each line is read by [ByUnittestOutputParser] and its
 * events written as service messages for the base converter; a line it does not recognise is the
 * program's output.
 *
 * By node id because unittest prints every failure's traceback after the last test: a failed test stays
 * open until its traceback has been read, while the tests after it, in other classes, run and finish.
 *
 * @param moduleFile the path a node id of the run names a module's file by: relative to the project
 *   for a plain run or a `by run` in place, to a `by run`'s staged tree otherwise
 * @param labels what the run named to `manage.py test`
 */
class ByDjangoTestEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    moduleFile: (String) -> String,
    labels: List<String> = emptyList(),
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    /** A file's suite is named after the `.by` `by` staged it from, as a pytest run's is. */
    private val parser = ByUnittestOutputParser(
        moduleFile = moduleFile,
        fileSuiteName = { path ->
            val project = consoleProperties.project
            val source = ByProgramModel.getInstance(project).stagedFile(path)
            val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            source?.let { file -> root?.let { VfsUtilCore.getRelativePath(file, it) } } ?: path
        },
        labels = labels,
    )

    private var reported = false
    private var finished = false

    override fun processConsistentText(text: String, outputType: Key<*>) {
        if (!reported && text.trimStart().startsWith(ENTERED_THE_MATRIX)) {
            reported = true
            for (event in parser.finish()) emit(event, outputType)
        }
        if (reported) {
            super.processConsistentText(text, outputType)
            return
        }
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val events = parser.parseLine(line)
            if (events.isEmpty()) super.processConsistentText(line + "\n", outputType)
            else for (event in events) emit(event, outputType)
        }
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        if (!finished && !reported) {
            finished = true
            for (event in parser.finish()) emit(event, KEY)
        }
        super.flushBufferOnProcessTermination(exitCode)
    }

    private fun emit(event: ByUnittestEvent, outputType: Key<*>) {
        val message = when (event) {
            is ByUnittestEvent.Output -> {
                super.processConsistentText(event.text + "\n", outputType)
                return
            }
            is ByUnittestEvent.SuiteStarted -> ServiceMessageBuilder.testSuiteStarted(event.name)
                .addAttribute(NODE_ID, event.id).addAttribute(PARENT_NODE_ID, event.parentId)
                .addAttribute("locationHint", event.locationHint)
            is ByUnittestEvent.SuiteFinished -> ServiceMessageBuilder.testSuiteFinished(event.name)
                .addAttribute(NODE_ID, event.id)
            is ByUnittestEvent.TestStarted -> ServiceMessageBuilder.testStarted(event.name)
                .addAttribute(NODE_ID, event.id).addAttribute(PARENT_NODE_ID, event.parentId)
                .addAttribute("locationHint", event.locationHint)
            is ByUnittestEvent.TestFailed -> ServiceMessageBuilder.testFailed(event.name)
                .addAttribute(NODE_ID, event.id)
                .addAttribute("message", event.message)
                .also { if (event.details.isNotEmpty()) it.addAttribute("details", event.details) }
            is ByUnittestEvent.TestIgnored -> ServiceMessageBuilder.testIgnored(event.name)
                .addAttribute(NODE_ID, event.id)
                .also { if (event.message.isNotEmpty()) it.addAttribute("message", event.message) }
            is ByUnittestEvent.TestFinished -> ServiceMessageBuilder.testFinished(event.name)
                .addAttribute(NODE_ID, event.id)
        }
        super.processConsistentText(message.toString() + "\n", outputType)
    }

    private companion object {
        val KEY: Key<*> = Key.create<Any>("by.django.test.flush")
        const val ENTERED_THE_MATRIX = "##teamcity[enteredTheMatrix"
        const val NODE_ID = "nodeId"
        const val PARENT_NODE_ID = "parentNodeId"
    }
}

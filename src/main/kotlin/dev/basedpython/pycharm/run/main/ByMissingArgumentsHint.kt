package dev.basedpython.pycharm.run.main

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.basedpython.pycharm.run.ByRunConfiguration

/**
 * Turns a run that died for want of arguments into one click that supplies them.
 *
 * `main`'s parameters are the program's command-line interface, so a run started without them ends
 * on argparse's `error: the following arguments are required: …` and exit code 2. Everything needed
 * to fix that is already known — the module, its signature, the configuration that ran it — so the
 * console offers it rather than leaving the user to find the arguments field.
 *
 * This is the backstop for every path that does not go through the gutter's own
 * [ByRunWithArgumentsAction]: a saved configuration, a `main` whose signature this plugin failed to
 * read, a `by` that exposes parameters differently from the version this was written against.
 */
internal class ByMissingArgumentsHint(
    private val configuration: ByRunConfiguration,
    private val environment: ExecutionEnvironment,
) {
    private var console: ConsoleView? = null

    @Volatile
    private var missing = false

    /** Called with the console this run prints to, once the platform has built it. */
    fun show(view: ConsoleView) {
        console = view
    }

    /** Called with the process, to read the failure out of its output. */
    fun watch(handler: ProcessHandler) {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (REQUIRED.containsMatchIn(event.text)) missing = true
            }

            override fun processTerminated(event: ProcessEvent) {
                if (!missing) return
                val view = console ?: return
                ApplicationManager.getApplication().invokeLater { offer(view) }
            }
        })
    }

    /** Adds the offer under argparse's own complaint, which has already named what is missing. */
    private fun offer(view: ConsoleView) {
        val project = configuration.project
        if (project.isDisposed) return
        val main = ByMainModules.mainFor(project, configuration.options.module) ?: return
        if (!main.takesArguments) return
        view.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        view.printHyperlink(LINK, HyperlinkInfo { rerun(it, main) })
        view.print("\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun rerun(project: Project, main: ByMainFunction) {
        val result = promptForArguments(project, configuration, main) ?: return
        val settings = environment.runnerAndConfigurationSettings
        if (settings == null) {
            // Nothing to run but the environment itself; it holds this same configuration, so the
            // arguments just written are the ones it starts with.
            ExecutionUtil.restart(environment)
            return
        }
        val executor =
            if (result.debug) DefaultDebugExecutor.getDebugExecutorInstance()
            else DefaultRunExecutor.getRunExecutorInstance()
        ExecutionUtil.runConfiguration(settings, executor)
    }

    private companion object {
        const val LINK = "Run with arguments…"

        /**
         * argparse's own wording, from the parser basedpython generates: the missing names follow
         * the phrase, comma-separated, to the end of the line.
         */
        val REQUIRED = Regex("""error: the following arguments are required: (.+)$""", RegexOption.MULTILINE)
    }
}

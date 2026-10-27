package dev.basedpython.pycharm.console

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.util.ProgramParametersConfigurator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import dev.basedpython.pycharm.run.ByRunConfiguration
import com.intellij.execution.util.configureEnvsFromFiles
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Run of a `by run` configuration with *Run with basedpython Console* on: the module runs first in a
 * basedpython console — `by repl --module`, which runs it as `__main__` as `by run` would — and the
 * console stays open on its names, in the run's tab, as `.py`'s *Run with Python Console* leaves a
 * Python console. Its `def main` is a function of the session, which nothing calls.
 *
 * The configuration's environment, working directory and `.env` files apply as they do to `by run`;
 * a blank module is the project's `run.main`, as `by run` reads it.
 */
internal class ByConsoleRunState(private val configuration: ByRunConfiguration) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val options = configuration.options
        val module = options.module.trim().ifBlank { null }
            ?: ByProgramModel.getInstance(project).configuredMain().module
            ?: throw ExecutionException(BasedPythonBundle.message("run.error.consoleNoModule"))
        val fileEnvironment = try {
            configureEnvsFromFiles(configuration)
        } catch (e: ProgramParametersConfigurator.ParametersConfiguratorException) {
            throw ExecutionException(e.message, e)
        }
        val start = suspend { ByConsoleSession.startProcess(project, module, options, fileEnvironment) }
        // it asks `by repl --help` whether it has `--events` before it starts the session
        val started = if (ApplicationManager.getApplication().isDispatchThread) {
            runWithModalProgressBlocking(project, BasedPythonBundle.message("console.starting")) { start() }
        } else {
            runBlockingCancellable { start() }
        }
        val session = ConsoleOnEdt.build(project, started)
        ByConsoles.getInstance(project).adopt(session)
        return DefaultExecutionResult(session.console, started.handler)
    }

    private object ConsoleOnEdt {
        fun build(project: com.intellij.openapi.project.Project, started: ByConsoleSession.Started): ByConsoleSession {
            var session: ByConsoleSession? = null
            ApplicationManager.getApplication().invokeAndWait { session = ByConsoleSession.build(project, started, ownsConsole = false) }
            return session!!
        }
    }
}

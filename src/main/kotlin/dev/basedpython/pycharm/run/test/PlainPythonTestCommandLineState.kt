package dev.basedpython.pycharm.run.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.run.ByCommonOptions

/**
 * Runs tests that are already `.py` on the project's interpreter: `python -m pytest -v <targets>` in
 * the project, or django's `python manage.py test -v 2 <labels>` beside `manage.py`.
 *
 * `by run pytest` cannot run these at all: it transpiles the `.by` files into a temp directory and
 * runs pytest *there*, and `by build` transpiles ".by files" only — so a `.py` test is not in the
 * tree pytest walks. Until `by run` can be told to include them, running one means running pytest
 * the ordinary way, on the interpreter [ByEnvironments.resolvePython] finds, in the project itself
 * so that its `pyproject.toml`, `conftest.py` and rootdir all apply the way the user expects.
 *
 * Everything downstream is shared with the transpiled path: the output is pytest's, so the same
 * parser, the same SM console, the same tree.
 */
internal class PlainPythonTestCommandLineState(
    private val project: Project,
    private val options: ByCommonOptions,
    environment: ExecutionEnvironment,
    /** What the interpreter is given, before [ByCommonOptions.extraArgs]: `-m pytest -v <targets>`. */
    private val arguments: List<String>,
    /** Where it runs; null for the configuration's working directory, or the project's. */
    private val directory: String? = null,
    private val attachConsole: (ProcessHandler, Executor) -> ConsoleView,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val python = ByEnvironments.resolvePython(project)
            ?: throw ExecutionException(
                "No Python interpreter found for this project — set one in Settings, or create a .venv",
            )
        val cmd = GeneralCommandLine()
            .withExePath(python.exe.toString())
            .withCharset(Charsets.UTF_8)
        cmd.addParameters(python.prependArgs)
        cmd.addParameters(arguments)
        if (options.extraArgs.isNotBlank()) cmd.addParameters(ParametersListUtil.parse(options.extraArgs))

        val workingDir = directory ?: options.workingDir.ifBlank { project.basePath ?: System.getProperty("user.home") }
        cmd.withWorkDirectory(FileUtil.toSystemDependentName(workingDir))
        cmd.withParentEnvironmentType(
            if (options.passParentEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else GeneralCommandLine.ParentEnvironmentType.NONE,
        )
        // Activation first, so a user-set variable of the same name still wins.
        cmd.withEnvironment(python.env)
        cmd.withEnvironment(PYTHONUNBUFFERED, "1")
        cmd.withEnvironment(options.envVars)

        val handler = KillableColoredProcessHandler(cmd)
        handler.setShouldKillProcessSoftly(false)
        handler.setShouldDestroyProcessRecursively(true)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    /**
     * Starts the process, then attaches the console to *that* handler.
     *
     * The same trap the transpiled path documents: `CommandLineState.execute` starts the process
     * and then asks for a console, so building one with `createAndAttachConsole(…, startProcess(),
     * …)` would run the suite twice.
     */
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val console = attachConsole(handler, executor)
        return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
            .withTestRestartActions(console)
    }

    private companion object {
        /** CPython block-buffers a pipe, which would hold the tree empty until the run ended. */
        const val PYTHONUNBUFFERED = "PYTHONUNBUFFERED"
    }
}

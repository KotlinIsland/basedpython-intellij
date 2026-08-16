package dev.basedpython.pycharm.tasks

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * One task of one runner, as a run configuration.
 *
 * Everything the command needs is persisted: which runner, which file it came from, what kind of
 * node it was and what selects it. That is more than a command line would be, and it is why the
 * configuration keeps working when the tool it runs changes its flags — and why re-running from the
 * Run window updates the very row in the task view that started it, since the same five fields make
 * the key the view stores verdicts under.
 */
class ByTaskOptions : RunConfigurationOptions() {
    private val runnerProp = string("").provideDelegate(this, "runner")
    private val configPathProp = string("").provideDelegate(this, "configPath")
    private val taskKindProp = string("").provideDelegate(this, "taskKind")
    private val taskIdProp = string("").provideDelegate(this, "taskId")
    private val stageProp = string("").provideDelegate(this, "stage")
    private val extraArgsProp = string("").provideDelegate(this, "extraArgs")
    private val workingDirProp = string("").provideDelegate(this, "workingDir")
    private val allFilesProp = property(true).provideDelegate(this, "allFiles")

    /** The runner's id — see `ByTaskRunner`; an unknown one degrades rather than failing to load. */
    var runner: String
        get() = runnerProp.getValue(this) ?: ""
        set(v) { runnerProp.setValue(this, v) }

    /** The configuration file this task was read from, relative to the project base. */
    var configPath: String
        get() = configPathProp.getValue(this) ?: ""
        set(v) { configPathProp.setValue(this, v) }

    /** The node kind — see `ByTaskKind`; decides what [taskId] and [stage] mean to the command. */
    var taskKind: String
        get() = taskKindProp.getValue(this) ?: ""
        set(v) { taskKindProp.setValue(this, v) }

    /** What the runner's CLI is given to select this task: a hook id, a command name, an alias. */
    var taskId: String
        get() = taskIdProp.getValue(this) ?: ""
        set(v) { taskIdProp.setValue(this, v) }

    /** The git hook this belongs to, when that is part of the command. */
    var stage: String
        get() = stageProp.getValue(this) ?: ""
        set(v) { stageProp.setValue(this, v) }

    /**
     * Run against every file rather than only what is staged.
     *
     * Defaulted on, which is the opposite of what the hook does at commit time and the right thing
     * here: a hook run from a tool window with nothing staged inspects nothing and reports success,
     * which is the least useful thing a button can do.
     */
    var allFiles: Boolean
        get() = allFilesProp.getValue(this)
        set(v) { allFilesProp.setValue(this, v) }

    var extraArgs: String
        get() = extraArgsProp.getValue(this) ?: ""
        set(v) { extraArgsProp.setValue(this, v) }

    var workingDir: String
        get() = workingDirProp.getValue(this) ?: ""
        set(v) { workingDirProp.setValue(this, v) }

    // As in ByCommonOptions: no StoredProperty supports a map, and EnvironmentVariablesComponent
    // does its own (de)serialisation at the editor level.
    var envVars: MutableMap<String, String> = linkedMapOf()
    var passParentEnv: Boolean = true
}

class ByTaskConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByTaskOptions>(project, factory, name) {

    public override fun getOptions(): ByTaskOptions = super.getOptions() as ByTaskOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByTaskOptions>> =
        ByTaskSettingsEditor()

    /**
     * Refuses a configuration that could not produce a command, and one whose runner is not
     * installed.
     *
     * The second is a *warning* in every other sense — the file is right, the task exists, the
     * machine simply has no `lefthook` — but it is raised as an error because the alternative is a
     * process that fails to start with a stack trace where the missing tool's name should be.
     */
    override fun checkConfiguration() {
        val runner = ByTaskRunner.fromId(options.runner)
        arguments() ?: throw RuntimeConfigurationError(
            BasedPythonBundle.message("tasks.error.notRunnable", options.taskId.ifBlank { name }),
        )
        if (ByTaskLaunch.resolve(project, runner) == null) {
            throw RuntimeConfigurationError(BasedPythonBundle.message("tasks.error.binaryMissing", runner.binary))
        }
    }

    /** The runner's arguments for this configuration, or null when its fields name no runnable task. */
    internal fun arguments(): List<String>? = ByTaskCommands.arguments(
        runner = ByTaskRunner.fromId(options.runner),
        kind = kind(),
        id = options.taskId.takeIf { it.isNotBlank() },
        stage = options.stage.takeIf { it.isNotBlank() },
        allFiles = options.allFiles,
    )

    private fun kind(): ByTaskKind =
        ByTaskKind.entries.firstOrNull { it.name == options.taskKind } ?: ByTaskKind.FILE

    /** The key the task view stores this task's verdict under; see [taskKey]. */
    internal fun taskKey(): String = taskKey(
        runner = ByTaskRunner.fromId(options.runner),
        path = options.configPath,
        kind = kind(),
        id = options.taskId.takeIf { it.isNotBlank() },
        stage = options.stage.takeIf { it.isNotBlank() },
    )

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val runner = ByTaskRunner.fromId(options.runner)
                val launch = ByTaskLaunch.resolve(project, runner) ?: throw ExecutionException(
                    BasedPythonBundle.message("tasks.error.binaryMissing", runner.binary),
                )
                val arguments = arguments() ?: throw ExecutionException(
                    BasedPythonBundle.message("tasks.error.notRunnable", options.taskId.ifBlank { name }),
                )

                val cmd = GeneralCommandLine()
                    .withExePath(launch.exe.toString())
                    .withCharset(Charsets.UTF_8)
                cmd.addParameters(launch.prependArgs)
                cmd.addParameters(arguments)
                if (options.extraArgs.isNotBlank()) {
                    cmd.addParameters(ParametersListUtil.parse(options.extraArgs))
                }

                // The repository root unless told otherwise: every one of these tools resolves its
                // configuration, and the paths inside it, from where it was started.
                val workingDir = options.workingDir.ifBlank { project.basePath ?: System.getProperty("user.home") }
                cmd.withWorkDirectory(FileUtil.toSystemDependentName(workingDir))

                cmd.withParentEnvironmentType(
                    if (options.passParentEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE
                    else GeneralCommandLine.ParentEnvironmentType.NONE,
                )
                // Venv activation first, so a variable the user set here still wins.
                cmd.withEnvironment(launch.env)
                cmd.withEnvironment(options.envVars)

                val handler = KillableColoredProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                handler.addProcessListener(OutcomeListener(project, taskKey()))
                return handler
            }
        }
}

/**
 * Reports what a run did back to the task view.
 *
 * Attached to the process rather than driven from the button that started it, so a re-run from the
 * Run window, a run from the run combo box and a run from the tree all land in the same place — the
 * same reason the test view listens to the SM runner instead of to its own toolbar.
 */
private class OutcomeListener(private val project: Project, private val key: String) : ProcessListener {

    override fun startNotified(event: ProcessEvent) {
        ByTaskService.getInstance(project).setOutcome(key, ByTaskState.RUNNING)
    }

    override fun processTerminated(event: ProcessEvent) {
        ByTaskService.getInstance(project).setOutcome(
            key,
            if (event.exitCode == 0) ByTaskState.PASSED else ByTaskState.FAILED,
        )
    }
}

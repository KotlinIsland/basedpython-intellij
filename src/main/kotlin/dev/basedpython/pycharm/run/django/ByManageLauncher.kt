package dev.basedpython.pycharm.run.django

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.ByRunOptions

/** Runs a `manage.py` command as a `by run` configuration named after it, a temporary one unless the user has saved it. */
internal object ByManageLauncher {

    /** Runs [manage] with [arguments]; with [select], the run widget has its configuration selected. */
    fun run(project: Project, manage: ByDjangoManage, arguments: List<String>, executor: Executor, select: Boolean) {
        ByManageHistory.getInstance(project).ran(ParametersListUtil.join(arguments))
        start(project, configuration(project, manage, arguments), executor, select)
    }

    /** Runs [arguments] as [like] was run: its `manage.py`, environment and `by` options. */
    fun run(project: Project, like: ByRunConfiguration, arguments: List<String>, executor: Executor) =
        start(project, configuration(project, like, arguments), executor, select = false)

    private fun start(project: Project, settings: RunnerAndConfigurationSettings, executor: Executor, select: Boolean) {
        val runManager = RunManager.getInstance(project)
        if (settings !in runManager.allSettings) {
            settings.isTemporary = true
            runManager.addConfiguration(settings)
        }
        if (select) runManager.selectedConfiguration = settings
        ProgramRunnerUtil.executeConfiguration(settings, executor)
    }

    /** The configuration of that command the project has, when it has one, or a new one. */
    fun configuration(project: Project, manage: ByDjangoManage, arguments: List<String>): RunnerAndConfigurationSettings {
        val name = name(arguments)
        val made = ByDjangoManage.configuration(project, manage, BasedPythonRunConfigurationType.getInstance().runFactory, name, arguments)
        terminal(made)
        val options = (made.configuration as ByRunConfiguration).options
        return reused(project, made) { settings, theirs ->
            // one the user has saved is that command as they want it run
            theirs == options || !settings.isTemporary &&
                theirs.module == options.module && theirs.programArgs == options.programArgs && theirs.workingDir == options.workingDir
        }
    }

    fun configuration(project: Project, like: ByRunConfiguration, arguments: List<String>): RunnerAndConfigurationSettings {
        val made = RunManager.getInstance(project).createConfiguration(name(arguments), BasedPythonRunConfigurationType.getInstance().runFactory)
        val options = (made.configuration as ByRunConfiguration).options
        options.copyFrom(like.options)
        options.programArgs = ParametersListUtil.join(arguments)
        options.redirectInput = false
        options.runWithConsole = false
        terminal(made)
        return reused(project, made) { _, theirs -> theirs == options }
    }

    /**
     * A run gets a terminal, since some of django's commands refuse to run without one:
     * `createsuperuser` answers *Superuser creation skipped due to not running in a TTY* (measured,
     * django 6.1.3).
     */
    private fun terminal(settings: RunnerAndConfigurationSettings) {
        (settings.configuration as ByRunConfiguration).options.terminalInRun = true
    }

    /**
     * The configuration named as [made] is when it is the [same]: the platform then knows a second run of
     * it as a rerun, and offers to stop the first. A different one of that name is left as it is — adding
     * a configuration replaces the one of its name — and [made] gets another name.
     */
    private fun reused(
        project: Project,
        made: RunnerAndConfigurationSettings,
        same: (RunnerAndConfigurationSettings, ByRunOptions) -> Boolean,
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val ours = runManager.allSettings.filter { it.type == made.type }
        ours.firstOrNull { settings ->
            val configuration = settings.configuration as? ByRunConfiguration
            configuration != null && settings.name.startsWith(made.name) && same(settings, configuration.options)
        }?.let { return it }
        if (ours.any { it.name == made.name }) made.name = runManager.suggestUniqueName(made.name, made.type)
        return made
    }

    private fun name(arguments: List<String>) = "manage.py " + ParametersListUtil.join(arguments)
}

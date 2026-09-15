package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByBuildOptions
import dev.basedpython.pycharm.run.ByBuildService
import dev.basedpython.pycharm.run.ByCheckConfiguration
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.ByConfiguration
import dev.basedpython.pycharm.run.byArguments
import dev.basedpython.pycharm.run.byCommandLine
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * Lets users attach a "Run `by build` first" step to any run configuration.
 *
 * When a [BuildBeforeRunTask] is present and enabled, [executeTask] runs `by build` before the
 * main configuration launches — with that configuration's `by`, working directory and environment
 * when it is a `by` configuration (see [buildCommandLine]), at the project base otherwise. A
 * non-zero exit code, an unresolvable `by` binary, or a cancelled build stops the run.
 *
 * Registered via the `stepsBeforeRunProvider` extension point.
 */
class BuildBeforeRunTaskProvider : BeforeRunTaskProvider<BuildBeforeRunTask>() {

    override fun getId(): Key<BuildBeforeRunTask> = BuildBeforeRunTask.PROVIDER_ID

    override fun getName(): String = BasedPythonBundle.message("runConfig.buildBeforeRun.name")

    override fun getIcon(): Icon = AllIcons.Actions.Compile

    override fun getTaskIcon(task: BuildBeforeRunTask): Icon = AllIcons.Actions.Compile

    override fun isConfigurable(): Boolean = false

    override fun isSingleton(): Boolean = true

    /** Available on every configuration; defaults to disabled until the user toggles it on. */
    override fun createTask(runConfiguration: RunConfiguration): BuildBeforeRunTask =
        BuildBeforeRunTask().apply { isEnabled = false }

    override fun canExecuteTask(configuration: RunConfiguration, task: BuildBeforeRunTask): Boolean =
        BasedPythonBinaries.launchBy(configuration.project, kind = buildOptions(configuration).environmentKind) != null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        env: ExecutionEnvironment,
        task: BuildBeforeRunTask,
    ): Boolean {
        val project = configuration.project
        val cmd = try {
            buildCommandLine(configuration)
        } catch (e: ExecutionException) {
            reportFailure(project, e.message ?: BasedPythonBundle.message("runConfig.buildBeforeRun.binaryMissing"))
            return false
        }

        val output = try {
            ByBuildService.getInstance(project).buildBlocking(cmd, BasedPythonBundle.message("runConfig.buildBeforeRun.name"))
        } catch (e: ExecutionException) {
            LOG.warn("Failed to launch `by build`", e)
            reportFailure(project, BasedPythonBundle.message("runConfig.buildBeforeRun.launchFailed", e.message ?: ""))
            return false
        }
        // Cancelled: the run does not start, and there is nothing to report — the user asked for it.
        if (output == null) return false

        if (output.exitCode != 0) {
            LOG.warn("`by build` failed (exit ${output.exitCode}): ${output.stderr}")
            reportFailure(
                project,
                BasedPythonBundle.message("runConfig.buildBeforeRun.failed", output.exitCode, output.stderr.trim().ifEmpty { output.stdout.trim() }),
            )
            return false
        }
        return true
    }

    private fun reportFailure(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, BasedPythonBundle.message("runConfig.buildBeforeRun.failed.title"))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BuildBeforeRunTaskProvider::class.java)
    }
}

/**
 * The options a build before [configuration] runs with: that configuration's own, when it is a `by`
 * configuration, so the build resolves the same `by` in the same directory with the same
 * environment as the run it precedes. Anything else builds as the project's defaults would.
 */
internal fun buildOptions(configuration: RunConfiguration): ByCommonOptions =
    (configuration as? ByConfiguration)?.getOptions() ?: ByBuildOptions()

/**
 * `by build` for the step before [configuration].
 *
 * The configuration's Python version is passed on as `--min-version` wherever it means that; a
 * check configuration's is the version to *assume* while checking, which says nothing about what
 * the output must run on. Its extra args are not: they are flags for the subcommand it runs, and
 * `by run --compiled` is not something `by build` accepts.
 *
 * @throws ExecutionException when no `by` can be found.
 */
internal fun buildCommandLine(configuration: RunConfiguration): GeneralCommandLine {
    val options = buildOptions(configuration)
    val version = if (configuration is ByCheckConfiguration) "" else options.pythonVersion
    return byCommandLine(
        project = configuration.project,
        options = options,
        arguments = byArguments(
            subcommand = "build",
            pythonVersionFlag = "--min-version",
            pythonVersion = version,
            subcommandArgs = emptyList(),
            extraArgs = "",
        ),
    )
}

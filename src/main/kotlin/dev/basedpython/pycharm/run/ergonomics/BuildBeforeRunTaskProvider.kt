package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByBuildConfiguration
import dev.basedpython.pycharm.run.ByCheckConfiguration
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * Lets users attach a "Run `by build` first" step to any run configuration.
 *
 * When a [BuildBeforeRunTask] is present and enabled, [executeTask] runs `by build` at the
 * project base (or the configuration's working dir, if it is a `by` configuration that defines
 * one) before the main configuration launches. A non-zero exit code (or an unresolvable `by`
 * binary) fails the run.
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
        BasedPythonBinaries.resolveBy(configuration.project) != null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        env: ExecutionEnvironment,
        task: BuildBeforeRunTask,
    ): Boolean {
        val project = configuration.project
        val by = BasedPythonBinaries.resolveBy(project)
        if (by == null) {
            reportFailure(project, BasedPythonBundle.message("runConfig.buildBeforeRun.binaryMissing"))
            return false
        }

        val workDir = resolveWorkingDir(project, configuration)
        val cmd = GeneralCommandLine()
            .withExePath(by.toString())
            .withCharset(Charsets.UTF_8)
            .withParameters("build")
            .withWorkDirectory(FileUtil.toSystemDependentName(workDir))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        return try {
            val output = CapturingProcessHandler(cmd).runProcess(BUILD_TIMEOUT_MS)
            if (output.exitCode != 0) {
                LOG.warn("`by build` failed (exit ${output.exitCode}): ${output.stderr}")
                reportFailure(
                    project,
                    BasedPythonBundle.message("runConfig.buildBeforeRun.failed", output.exitCode, output.stderr.trim().ifEmpty { output.stdout.trim() }),
                )
                false
            } else {
                true
            }
        } catch (e: Exception) {
            LOG.warn("Failed to launch `by build`", e)
            reportFailure(project, BasedPythonBundle.message("runConfig.buildBeforeRun.launchFailed", e.message ?: ""))
            false
        }
    }

    private fun resolveWorkingDir(project: Project, configuration: RunConfiguration): String {
        // `by` configs expose a public getOptions() returning a ByCommonOptions subtype that
        // carries a workingDir; honor it when present.
        val fromConfig = when (configuration) {
            is ByRunConfiguration -> configuration.options.workingDir
            is ByBuildConfiguration -> configuration.options.workingDir
            is ByCheckConfiguration -> configuration.options.workingDir
            else -> null
        }?.takeIf { it.isNotBlank() }
        return fromConfig ?: project.basePath ?: System.getProperty("user.home")
    }

    private fun reportFailure(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, BasedPythonBundle.message("runConfig.buildBeforeRun.failed.title"))
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BuildBeforeRunTaskProvider::class.java)
        private const val BUILD_TIMEOUT_MS = 120_000
    }
}

package dev.basedpython.pycharm.run.coverage

import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageHelper
import com.intellij.coverage.CoverageRunnerData
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.showRunContent
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.basedpython.pycharm.run.ByCommandLineState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs a `by run` or test configuration under *Run with Coverage*.
 *
 * The run is the configuration's own, with `--coverage <report>` added to `by run`'s flags: `by`
 * measures, maps the transpiled modules onto their `.by` lines, and writes the report, which the
 * platform loads through [ByCoverageRunner] once the process ends.
 */
class ByCoverageProgramRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "BasedPythonCoverageRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == CoverageExecutor.EXECUTOR_ID &&
            profile is RunConfigurationBase<*> &&
            ByCoverageEngine.measurable(profile)

    /** What makes the platform load the report when the process ends; see `processGatheredCoverage`. */
    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = CoverageRunnerData()

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as RunConfigurationBase<*>
        val byState = state as? ByCommandLineState
            ?: throw ExecutionException("${configuration.name} does not run through `by run`, which is what measures coverage")
        val report = CoverageEnabledConfiguration.getOrCreate(configuration).coverageFilePath
            ?: throw ExecutionException("No coverage runner for ${configuration.name}")
        // a report left by an earlier run would be loaded as this one's when this one writes none
        Files.deleteIfExists(Path.of(report))
        byState.infrastructureArgs += listOf(COVERAGE_FLAG, report)
        FileDocumentManager.getInstance().saveAllDocuments()
        val result = state.execute(environment.executor, this)
        CoverageHelper.attachToProcess(configuration, result.processHandler, environment.runnerSettings)
        return showRunContent(result, environment)
    }

    internal companion object {
        /** `by run`'s flag naming where it writes the LCOV report. */
        const val COVERAGE_FLAG = "--coverage"
    }
}

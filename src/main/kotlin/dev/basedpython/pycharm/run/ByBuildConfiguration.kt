package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

class ByBuildConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByBuildOptions>(project, factory, name) {

    public override fun getOptions(): ByBuildOptions = super.getOptions() as ByBuildOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByBuildOptions>> =
        ByBuildSettingsEditor()

    override fun checkConfiguration() {
        if (BasedPythonBinaries.resolveBy(project) == null) {
            throw RuntimeConfigurationException("`by` binary not found — set path in Settings | BasedPython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : ByCommandLineState(project, options, environment) {
            override fun buildSubcommandArgs(): List<String> = listOf("build")
        }
}

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
import com.intellij.util.execution.ParametersListUtil

class ByRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByRunOptions>(project, factory, name) {

    public override fun getOptions(): ByRunOptions = super.getOptions() as ByRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByRunOptions>> =
        ByRunSettingsEditor()

    override fun checkConfiguration() {
        if (options.module.isBlank()) {
            throw RuntimeConfigurationException("Module is required (e.g. `mypkg.main`)")
        }
        if (BasedPythonBinaries.resolveBy(project) == null) {
            throw RuntimeConfigurationException("`by` binary not found — set path in Settings | BasedPython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        return object : ByCommandLineState(project, opts, environment) {
            override fun buildSubcommandArgs(): List<String> = buildList {
                add("run")
                add(opts.module.trim())
            }
        }
    }
}

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

class ByCheckConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByCheckOptions>(project, factory, name) {

    public override fun getOptions(): ByCheckOptions = super.getOptions() as ByCheckOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByCheckOptions>> =
        ByCheckSettingsEditor()

    override fun checkConfiguration() {
        if (BasedPythonBinaries.resolveBy(project) == null) {
            throw RuntimeConfigurationException("by binary not found — set path in Settings | basedpython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        return object : ByCommandLineState(project, opts, environment) {
            override fun buildSubcommandArgs(): List<String> = buildList {
                add("check")
                if (opts.paths.isNotBlank()) addAll(ParametersListUtil.parse(opts.paths))
            }
        }
    }
}

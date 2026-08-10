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
        if (!BasedPythonBinaries.isByAvailable(project)) {
            throw RuntimeConfigurationException("by binary not found — set path in Settings | basedpython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "check"

            // `by check` has no `--min-version`: it type-checks rather than emitting code, so the
            // version it cares about is the one to assume while resolving types.
            override val pythonVersionFlag = "--python-version"

            override fun buildSubcommandArgs(): List<String> =
                if (opts.paths.isBlank()) emptyList() else ParametersListUtil.parse(opts.paths)
        }
    }
}

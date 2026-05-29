package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByCommonOptions
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

/** Options for `by test`; reuses common working dir / extra args / env handling. */
class ByTestOptions : ByCommonOptions() {
    private val pathsProp = string("").provideDelegate(this, "paths")
    var paths: String
        get() = pathsProp.getValue(this) ?: ""
        set(v) { pathsProp.setValue(this, v) }
}

class ByTestConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByTestOptions>(project, factory, name) {

    public override fun getOptions(): ByTestOptions = super.getOptions() as ByTestOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByTestOptions>> =
        ByTestSettingsEditor()

    override fun checkConfiguration() {
        if (BasedPythonBinaries.resolveBy(project) == null) {
            throw RuntimeConfigurationException("`by` binary not found — set path in Settings | BasedPython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        return object : ByCommandLineState(project, opts, environment) {
            override fun buildSubcommandArgs(): List<String> = buildList {
                add("test")
                if (opts.paths.isNotBlank()) addAll(ParametersListUtil.parse(opts.paths))
            }
        }
    }
}

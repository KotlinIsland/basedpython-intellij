package dev.basedpython.pycharm.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

class ByBuildConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    ByTargetAwareConfiguration<ByBuildOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByBuildOptions>> =
        ByBuildSettingsEditor(project)

    override fun checkConfiguration() = checkBy()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        refuseOnTarget(executor)
        return object : ByCommandLineState(project, options, environment) {
            override val subcommand = "build"
            override fun buildSubcommandArgs(): List<String> = emptyList()
        }
    }
}

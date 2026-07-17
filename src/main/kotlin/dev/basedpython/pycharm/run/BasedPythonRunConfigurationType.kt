package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.Icon

class BasedPythonRunConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "basedpython"
    override fun getConfigurationTypeDescription(): String = "Run, build and check basedpython sources via the by CLI"
    override fun getIcon(): Icon = AllIcons.RunConfigurations.Application
    override fun getId(): String = ID

    val runFactory: ConfigurationFactory = RunFactory(this)
    val buildFactory: ConfigurationFactory = BuildFactory(this)
    val checkFactory: ConfigurationFactory = CheckFactory(this)

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        arrayOf(runFactory, buildFactory, checkFactory)

    companion object {
        const val ID = "BasedPythonRunConfiguration"
        @JvmStatic
        fun getInstance(): BasedPythonRunConfigurationType =
            com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(BasedPythonRunConfigurationType::class.java)
    }

    class RunFactory(type: BasedPythonRunConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Run"
        override fun getName(): String = "by run"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByRunConfiguration(project, this, "by run")
        override fun getOptionsClass(): Class<out com.intellij.execution.configurations.RunConfigurationOptions> =
            ByRunOptions::class.java
    }

    class BuildFactory(type: BasedPythonRunConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Build"
        override fun getName(): String = "by build"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByBuildConfiguration(project, this, "by build")
        override fun getOptionsClass(): Class<out com.intellij.execution.configurations.RunConfigurationOptions> =
            ByBuildOptions::class.java
    }

    class CheckFactory(type: BasedPythonRunConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Check"
        override fun getName(): String = "by check"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByCheckConfiguration(project, this, "by check")
        override fun getOptionsClass(): Class<out com.intellij.execution.configurations.RunConfigurationOptions> =
            ByCheckOptions::class.java
    }
}

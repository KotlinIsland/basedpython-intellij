package dev.basedpython.pycharm.run.test

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.BasedPythonIcons
import javax.swing.Icon

/**
 * Standalone configuration type for running basedpython tests. Kept separate from
 * `BasedPythonRunConfigurationType` (whose factory list is hardcoded) so the shared file
 * does not need editing.
 *
 * [ID] and the factory's `getId` are the persisted form and must not change; the names below are
 * only what the user reads, and they used to promise a `by test` subcommand that does not exist.
 */
class ByTestConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "basedpython Test"
    override fun getConfigurationTypeDescription(): String =
        "Run basedpython tests with pytest, via by run"
    override fun getIcon(): Icon = BasedPythonIcons.Logo
    override fun getId(): String = ID

    val testFactory: ConfigurationFactory = TestFactory(this)

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(testFactory)

    companion object {
        const val ID = "BasedPythonTestRunConfiguration"
        @JvmStatic
        fun getInstance(): ByTestConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(ByTestConfigurationType::class.java)
    }

    class TestFactory(type: ByTestConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Test"
        override fun getName(): String = "pytest"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByTestConfiguration(project, this, "pytest")
        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            ByTestOptions::class.java
    }
}

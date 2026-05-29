package dev.basedpython.pycharm.run.test

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Standalone configuration type for `by test`. Kept separate from
 * `BasedPythonRunConfigurationType` (whose factory list is hardcoded) so the shared file
 * does not need editing.
 */
class ByTestConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "BasedPython Test"
    override fun getConfigurationTypeDescription(): String = "Run BasedPython tests via `by test`"
    override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run
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
        override fun getName(): String = "by test"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByTestConfiguration(project, this, "by test")
        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            ByTestOptions::class.java
    }
}

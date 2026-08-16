package dev.basedpython.pycharm.tasks

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.BasedPythonIcons
import javax.swing.Icon

/**
 * The configuration type behind every task the hook view runs.
 *
 * One type for all four runners rather than one each: they differ in an executable and a flag
 * spelling, and four entries in the *Add New Configuration* list — three of which any given project
 * has no use for — would be four times the clutter for none of the clarity. Which runner a
 * configuration is for is a field, and the editor lets it be changed.
 *
 * [ID] and the factory's `getId` are the persisted form and must not change.
 */
class ByTaskConfigurationType : ConfigurationType {
    override fun getDisplayName(): String = "Hook Task"
    override fun getConfigurationTypeDescription(): String =
        "Run a pre-commit, prek, lefthook or pyprojectx task"
    override fun getIcon(): Icon = BasedPythonIcons.Tasks
    override fun getId(): String = ID

    val taskFactory: ConfigurationFactory = TaskFactory(this)

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(taskFactory)

    companion object {
        const val ID = "BasedPythonHookTaskConfiguration"

        @JvmStatic
        fun getInstance(): ByTaskConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(ByTaskConfigurationType::class.java)
    }

    class TaskFactory(type: ByTaskConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "Task"
        override fun getName(): String = "Hook Task"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByTaskConfiguration(project, this, "Hook Task")
        override fun getOptionsClass(): Class<out RunConfigurationOptions> = ByTaskOptions::class.java
    }
}

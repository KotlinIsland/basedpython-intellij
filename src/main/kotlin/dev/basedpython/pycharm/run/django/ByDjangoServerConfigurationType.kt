package dev.basedpython.pycharm.run.django

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.NotNullLazyValue
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureToolWindow
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.ByRunOptions
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * *Django Server* in Add New Configuration: `manage.py runserver` as a `by run` configuration, so it
 * runs and debugs as any `by run` does — the reloader's child, which serves the requests, debugged in
 * a session of its own. Every other option is `by run`'s and edited in its form; the address is
 * `runserver`'s own argument.
 */
class ByDjangoServerConfigurationType : ConfigurationTypeBase(
    ID,
    BasedPythonBundle.message("django.server.type"),
    BasedPythonBundle.message("django.server.type.description"),
    NotNullLazyValue.createValue { BasedPythonIcons.Logo },
) {
    init {
        addFactory(Factory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    private class Factory(type: ByDjangoServerConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = "DjangoServer"

        override fun getName(): String = BasedPythonBundle.message("django.server.type")

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            ByRunConfiguration(project, this, RUNSERVER).also { configuration ->
                val options = configuration.options
                options.programArgs = RUNSERVER
                val manage = ByDjangoManage.find(project)
                options.module = manage?.script ?: ByDjangoManage.SCRIPT
                manage?.let { options.workingDir = it.directory.toString() }
            }

        override fun getOptionsClass(): Class<out RunConfigurationOptions> = ByRunOptions::class.java
    }

    companion object {
        const val ID = "BasedPythonDjangoServer"
        const val RUNSERVER = "runserver"

        fun getInstance(): ByDjangoServerConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(ByDjangoServerConfigurationType::class.java)
    }
}

/**
 * A *runserver* configuration for a django project that has none, made once when the project first
 * opens with this plugin, as a django project in PyCharm Professional comes with one: a project
 * whose user deleted it is not given another.
 */
internal class ByDjangoServerConfigurationActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(MADE)) return
        if (!ByDjangoStructureToolWindow.looksLikeDjango(project)) return
        val manage = ByDjangoManage.find(project) ?: return
        withContext(Dispatchers.EDT) {
            val runManager = RunManager.getInstance(project)
            val type = ByDjangoServerConfigurationType.getInstance()
            properties.setValue(MADE, true)
            if (runManager.getConfigurationsList(type).isNotEmpty()) return@withContext
            val settings = ByDjangoManage.configuration(project, manage, type.factory, ByDjangoServerConfigurationType.RUNSERVER, listOf(ByDjangoServerConfigurationType.RUNSERVER))
            runManager.addConfiguration(settings)
            if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = settings
        }
    }

    private companion object {
        const val MADE = "basedpython.django.serverConfigurationMade"
    }
}

package dev.basedpython.pycharm.settings.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.project.Project

internal class BasedPythonConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    override fun createConfigurable(): Configurable = BasedPythonConfigurable(project)
}

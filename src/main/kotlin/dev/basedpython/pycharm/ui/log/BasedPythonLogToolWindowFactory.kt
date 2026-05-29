package dev.basedpython.pycharm.ui.log

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Backs the "basedpython" tool window (registered in plugin.xml) with the
 * [ConsoleView][com.intellij.execution.ui.ConsoleView] owned by [BasedPythonLog].
 */
internal class BasedPythonLogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = BasedPythonLog.getInstance(project).getOrCreateConsole()
        val content = ContentFactory.getInstance()
            .createContent(console.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}

package dev.basedpython.pycharm.ui.log

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

/**
 * Backs the "basedpython" tool window (registered in plugin.xml) with the
 * [ConsoleView][com.intellij.execution.ui.ConsoleView] owned by [BasedPythonLog].
 *
 * Offered on its own only in a basedpython project ([BasedPythonProjectDetector.isBasedPythonProject]);
 * anywhere else it appears when something asks to show it ([BasedPythonLog.show]) — `by` started
 * for a lone `.by` file and failed, say.
 */
internal class BasedPythonLogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = BasedPythonLog.getInstance(project).getOrCreateConsole()
        val content = ContentFactory.getInstance()
            .createContent(console.component, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
    }
}

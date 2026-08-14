package dev.basedpython.pycharm.run.test.node

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

/**
 * Backs the "basedpython Tests" tool window (registered in plugin.xml) with [ByTestNodePanel].
 *
 * Only offered to projects that are actually basedpython: the view's one source of data is
 * `by run pytest --collect-only`, so a project with no `by` to run has nothing to show and no
 * business growing a stripe button for it.
 */
internal class ByTestNodeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ByTestNodePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setAdditionalGearActions(panel.gearActions())
        // Opening the window is the request to collect; nothing runs `by` before that.
        ByTestNodeService.getInstance(project).refreshIfNeeded()
    }
}

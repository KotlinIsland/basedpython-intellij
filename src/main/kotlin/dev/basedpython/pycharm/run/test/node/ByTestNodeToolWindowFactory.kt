package dev.basedpython.pycharm.run.test.node

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

/**
 * Backs the "basedpython Tests" tool window with [ByTestNodePanel].
 *
 * Registered in `basedpython-testrunner.xml`, not `plugin.xml`: everything the window does with a
 * test — run it, debug it, show how it did — goes through the pytest configuration, which is built
 * on the SM test runner, so without that plugin the window would be a list of buttons that throw.
 *
 * Only offered to projects that are actually basedpython: its data is `by`'s, so a project with no
 * `by` has nothing to show and no business growing a stripe button for it.
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
        // What the server already knows; pytest itself is only run from the window's own button.
        ByTestNodeService.getInstance(project).showStatic()
    }
}

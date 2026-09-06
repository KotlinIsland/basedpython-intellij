package dev.basedpython.pycharm.debug.recompose

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

/**
 * Backs the "basedpython Recompositions" tool window (registered in plugin.xml) with
 * [ByRecompositionPanel].
 *
 * Offered to basedpython projects, the way the test view is: its one source of data is a bpd
 * session of a `by run` configuration, and a project with no `by` has none to start.
 */
internal class ByRecompositionToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ByRecompositionPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** The tool window's identity, and when the IDE is allowed to show its stripe button. */
internal object ByRecompositionToolWindow {

    /**
     * Must match the `id` in plugin.xml — the platform keys layout and visibility on this string.
     * `PluginXmlResourcesTest` is what makes that true rather than hoped for.
     */
    const val ID: String = "basedpython Recompositions"
}

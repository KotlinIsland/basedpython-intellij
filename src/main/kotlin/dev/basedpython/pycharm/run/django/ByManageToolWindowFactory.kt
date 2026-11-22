package dev.basedpython.pycharm.run.django

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureToolWindow
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The *basedpython manage.py* window: what a django project's commands are doing ([ByManageNowPanel])
 * and running one ([ByManageCommandsPanel]).
 *
 * Beside the Run window rather than in its place, so a command's output and its state are seen together.
 */
internal class ByManageToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project) && ByDjangoStructureToolWindow.looksLikeDjango(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val manager = toolWindow.contentManager
        val commands = ByManageCommandsPanel(project)
        val now = ByManageNowPanel(project) { arguments -> ByManageToolWindow.edit(project, arguments) }
        val nowContent = ContentFactory.getInstance().createContent(now, BasedPythonBundle.message("manage.now.tab"), false)
        val commandsContent = ContentFactory.getInstance().createContent(commands, BasedPythonBundle.message("manage.commands.tab"), false)
        for ((content, panel) in listOf(nowContent to now, commandsContent to commands)) {
            content.isCloseable = false
            content.setDisposer(panel)
            manager.addContent(content)
        }
        commandsContent.preferredFocusableComponent = commands.line
    }
}

internal object ByManageToolWindow {

    /** Must match the `id` in plugin.xml. */
    const val ID: String = "basedpython manage.py"

    /** Shows the Commands view with [arguments] written, or as it is when they are null. EDT. */
    fun edit(project: Project, arguments: String?) {
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        if (!window.isAvailable) window.isAvailable = true
        window.activate {
            val content = window.contentManager.contents.firstOrNull { it.component is ByManageCommandsPanel } ?: return@activate
            window.contentManager.setSelectedContent(content, true)
            val panel = content.component as ByManageCommandsPanel
            panel.edit(arguments ?: panel.line.text)
        }
    }
}

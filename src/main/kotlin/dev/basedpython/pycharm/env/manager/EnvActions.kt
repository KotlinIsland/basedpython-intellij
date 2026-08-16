package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * The environment operations that belong on the Tools menu rather than only in the tool window.
 *
 * Deliberately few. The tool window is where this feature lives; a menu entry per operation would be
 * a second, worse copy of a toolbar that has the environment's state next to it. What is here is the
 * one gesture worth reaching for without opening anything ([EnvSetUpAction]) and the way in
 * ([ShowEnvToolWindowAction]).
 */

/**
 * *Set Up Python Environment* — install the tool, create the environment, sync it, as far as this
 * project needs.
 *
 * Visible only while there is something to do, so the menu says whether the project is in order
 * without the user having to run anything to find out.
 */
internal class EnvSetUpAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val service = project?.let { EnvService.getInstance(it) }
        e.presentation.isEnabledAndVisible =
            service != null && service.status.health.isActionable && !service.busy
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EnvOperations.setUp(project)
    }
}

/** *Python Environment* — opens the tool window, and makes sure it is available first. */
internal class ShowEnvToolWindowAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow(EnvToolWindow.ID) ?: return
        // A project the availability check turned the window off for can still be asked for it
        // directly — the window then says what it found, which is the answer the user came for.
        window.isAvailable = true
        window.activate(null)
        EnvService.getInstance(project).refresh()
    }
}

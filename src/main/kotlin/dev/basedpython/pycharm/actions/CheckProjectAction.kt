package dev.basedpython.pycharm.actions

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import dev.basedpython.pycharm.lsp.diagnostics.ByProjectDiagnostics
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Lists what `by` finds in every file of the project in Problems | Project Errors, and keeps the
 * list current from then on; see [ByProjectDiagnostics].
 *
 * This ran `by check` in a console once. That was a second process reading the files from disk —
 * so an unsaved edit was not checked — and resolving the project its own way, printing text to
 * read rather than problems to navigate, and stale the moment anything changed. The server the
 * editor already talks to checks the same files, from the same buffers, and says when a result
 * changes.
 */
class CheckProjectAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && BasedPythonSettings.getInstance(project).byEnabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ByProjectDiagnostics.getInstance(project).follow()
        ProblemsViewToolWindowUtils.selectProjectErrorsTab(project)
    }
}

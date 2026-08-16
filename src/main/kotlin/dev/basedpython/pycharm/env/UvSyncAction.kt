package dev.basedpython.pycharm.env

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.basedpython.pycharm.env.manager.EnvOperations
import dev.basedpython.pycharm.env.manager.EnvService

/**
 * Runs the project's environment manager's sync — `uv sync` for a uv project.
 *
 * A thin front for [EnvOperations.sync], which is where the work and the wiring live: output streams
 * into the plugin's log, the environment view re-reads afterwards, and the language servers are
 * restarted because a sync is exactly when `by` starts or stops resolving. This action used to spawn
 * `uv sync` itself and do none of that, which is how a successful sync could leave the editor still
 * insisting the binary was missing.
 *
 * Enabled whenever a backend claims the project. Unlike before, that no longer includes projects
 * where the tool is absent — [EnvOperations.sync] would have nothing to run, and the tool window's
 * banner is where a missing uv gets offered an install.
 */
class UvSyncAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val service = e.project?.let { EnvService.getInstance(it) }
        e.presentation.isEnabledAndVisible =
            service != null && service.status.backend != null && service.status.toolPath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        EnvOperations.sync(project)
    }
}

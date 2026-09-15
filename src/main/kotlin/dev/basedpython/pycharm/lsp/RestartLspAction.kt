package dev.basedpython.pycharm.lsp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.platform.lsp.api.LspClientManager

/**
 * Stops and (if needed) restarts both basedpython LSP servers (`by`, `buff`).
 *
 * Registered as `basedpython.RestartLsp` in plugin.xml.
 */
internal class RestartLspAction : AnAction() {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val mgr = LspClientManager.getInstance(project)
    mgr.stopAndRestartClientsIfNeeded(ByLspServerSupportProvider::class.java)
    mgr.stopAndRestartClientsIfNeeded(BuffLspServerSupportProvider::class.java)
  }
}

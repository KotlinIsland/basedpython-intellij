package dev.basedpython.pycharm.lsp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.platform.lsp.api.LspServerManager

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
    val mgr = LspServerManager.getInstance(project)
    mgr.stopAndRestartIfNeeded(ByLspServerSupportProvider::class.java)
    mgr.stopAndRestartIfNeeded(BuffLspServerSupportProvider::class.java)
  }
}

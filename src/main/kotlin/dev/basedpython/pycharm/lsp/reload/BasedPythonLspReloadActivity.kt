package dev.basedpython.pycharm.lsp.reload

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Wires up the basedpython LSP crash-recovery listener once per project on open.
 *
 * The actual work lives in [BasedPythonLspReloader]; this activity simply instantiates
 * the service and registers its listener so it's active even before any settings change.
 */
internal class BasedPythonLspReloadActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    BasedPythonLspReloader.getInstance(project).ensureListenerRegistered()
  }
}

package dev.basedpython.pycharm.lsp.reload

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

/**
 * Wires up the basedpython LSP crash-recovery listener once per project on open.
 *
 * The actual work lives in [BasedPythonLspReloader]; this activity simply instantiates
 * the service and registers its listener so it's active even before any settings change.
 */
internal class BasedPythonLspReloadActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    // Nothing to recover in a project that will never start a server.
    if (!BasedPythonProjectDetector.isPythonProject(project)) return
    BasedPythonLspReloader.getInstance(project).ensureListenerRegistered()
  }
}

package dev.basedpython.pycharm.lsp

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.settings.BasedPythonSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts `by` when a project that holds basedpython sources opens, rather than when the first of them
 * is opened in an editor.
 *
 * The project answers questions before any file is opened: a module renamed or moved in the Project
 * view asks `by` which imports to rewrite, and so does a rename on the Modules settings page. Those
 * are asked from inside a modal dialog or a modal progress, and the platform cannot start a server
 * there — `LspClientManager.ensureClientStarted` adds its client in a write action on the EDT that
 * waits for every modal dialog to close (measured: a start asked for under a rename's progress came
 * up only once the conflicts dialog it caused had been dismissed). So `by` has to be running before
 * such a question is asked, and the one moment that is always before it is the project opening.
 *
 * Which projects count follows [ByLspServerSupportProvider.fileOpened]: one with a `.by` source
 * anywhere — a `.by` file is ours wherever it lives — or one carrying a basedpython marker. A project
 * with neither starts nothing, as before.
 */
internal class ByStartOnOpen : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!BasedPythonSettings.getInstance(project).byEnabled) return
        val serves = BasedPythonProjectDetector.isBasedPythonProject(project) ||
            smartReadAction(project) {
                FileTypeIndex.containsFileOfType(BasedPythonFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            }
        if (!serves) return
        // resolving the binary can mean asking uv
        withContext(Dispatchers.IO) { startByServer(project) }
    }
}

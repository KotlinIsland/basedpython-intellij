package dev.basedpython.pycharm.lsp

import com.intellij.openapi.application.ApplicationManager
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
        if (!byServesProject(project)) return
        // resolving the binary can mean asking uv
        withContext(Dispatchers.IO) { startByServer(project) }
    }
}

/**
 * Whether `by` has anything to serve in [project]: it is switched on, and the project either carries a
 * basedpython marker or holds a `.by` source somewhere — the projects [ByLspServerSupportProvider]
 * starts a server for when one of their files is opened. Waits for the index.
 */
internal suspend fun byServesProject(project: Project): Boolean {
    if (!BasedPythonSettings.getInstance(project).byEnabled) return false
    return BasedPythonProjectDetector.isBasedPythonProject(project) ||
        smartReadAction(project) {
            FileTypeIndex.containsFileOfType(BasedPythonFileType.INSTANCE, GlobalSearchScope.projectScope(project))
        }
}

/**
 * Starts `by` and `buff` for a project that has just come to use basedpython for its Python files
 * ([dev.basedpython.pycharm.lang.dialect.BasedPythonOptIn]), as [ByStartOnOpen] starts `by`.
 *
 * Not `stopAndRestartClientsIfNeeded`, which is what a settings change restarts them with: the
 * platform starts a server again only for open files that are project content
 * (`LspClientManagerImpl.startIfNeeded`), and a directory IntelliJ IDEA opened with no Python plugin
 * has no module, so no content — measured in the sandbox: after the restart `by` was not running,
 * after [startByServer] it was.
 */
internal fun startServersForPython(project: Project) {
    // resolving a binary can mean asking uv
    ApplicationManager.getApplication().executeOnPooledThread {
        if (!project.isDisposed) startByServer(project)
        if (!project.isDisposed) startBuffServer(project)
    }
}

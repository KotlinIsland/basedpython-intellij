package dev.basedpython.pycharm.env

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import dev.basedpython.pycharm.env.manager.EnvDependencyList
import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvOperations
import dev.basedpython.pycharm.env.manager.EnvService
import dev.basedpython.pycharm.env.manager.EnvToolWindow
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.ui.BasedPythonSettingsPage
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a banner above open `.by` files when the `by` binary cannot be resolved.
 *
 * Detection delegates to [BasedPythonBinaries.isByAvailable]; the banner shows when `by` cannot be
 * resolved. The install offer goes through [EnvOperations], so it is the same code path as the tool
 * window's — which matters for what happens *after* a successful install: the environment view
 * re-reads, the language servers restart against the newly resolvable binary, and this banner
 * re-evaluates itself and disappears. Spawning `uv add` here directly, as this used to, did the
 * install and none of the rest.
 *
 * This is the consent-gated bootstrap path. Auto-detection deliberately never invokes uv itself
 * (see [ByEnvironmentKind.UV]), so an environment only ever gets created because the user clicked.
 */
class ByMissingBannerProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (file.fileType !is BasedPythonFileType) return null
        if (dismissed.contains(file)) return null
        // Show only when `by` is unresolved.
        if (BasedPythonBinaries.isByAvailable(project)) return null

        return Function { _ -> buildPanel(project, file) }
    }

    private fun buildPanel(project: Project, file: VirtualFile): EditorNotificationPanel {
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Warning)
        panel.text = BasedPythonBundle.message("banner.byMissing.text")

        // Offered only where it can work. A project with no manifest has nothing for `uv add` to add
        // to, and the button would fail with a message about a missing `pyproject.toml` — the
        // environment view is where that project is told what it actually needs. A missing uv is
        // not such a case: the gesture installs it first. Before the first scan there is no answer
        // yet; the scan is asked for, and re-asks this banner when it has one.
        val service = EnvService.getInstance(project)
        service.refreshIfNeeded()
        if (service.status.backend != null) {
            panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.installWithUv")) {
                // The toolchain is a development dependency: it builds and checks the project, and
                // nothing that installs the project needs it. The root project's `dev`, with no
                // module named — a workspace's toolchain belongs to the workspace, not to a member.
                EnvOperations.add(
                    project,
                    listOf(BASEDPYTHON_PACKAGE),
                    EnvDependencyList(EnvDependencyTarget.DEV),
                )
            }
        }
        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.environment")) {
            ToolWindowManager.getInstance(project).getToolWindow(EnvToolWindow.ID)
                ?.apply { isAvailable = true }
                ?.activate(null)
        }
        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.configure")) {
            BasedPythonSettingsPage.show(project)
        }
        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.dismiss")) {
            dismissed.add(file)
            EditorNotifications.getInstance(project).updateNotifications(file)
        }
        return panel
    }

    private companion object {
        /** The distribution that provides the `by` and `buff` binaries. */
        const val BASEDPYTHON_PACKAGE = "basedpython"

        /** Files the user dismissed the banner for, for this IDE session. */
        val dismissed: MutableSet<VirtualFile> =
            java.util.Collections.synchronizedSet(java.util.HashSet())
    }
}

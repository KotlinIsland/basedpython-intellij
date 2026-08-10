package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows a banner above open `.by` files when the `by` binary cannot be resolved.
 *
 * Detection delegates to [BasedPythonBinaries.isByAvailable]; the banner shows when `by` cannot be
 * resolved. Actions: install via `uv add --dev basedpython`, open the basedpython settings page, or
 * dismiss for the current editor session.
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

        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.installWithUv")) {
            installWithUv(project)
        }
        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.configure")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "basedpython")
        }
        panel.createActionLabel(BasedPythonBundle.message("banner.byMissing.dismiss")) {
            dismissed.add(file)
            EditorNotifications.getInstance(project).updateNotifications(file)
        }
        return panel
    }

    private fun installWithUv(project: Project) {
        val base: Path = UvSupport.basePath(project) ?: run {
            UvSupport.notify(project, BasedPythonBundle.message("install.basedpython.title"), BasedPythonBundle.message("uv.noBasePath"), NotificationType.WARNING)
            return
        }
        val uv = UvSupport.findUv()
        val cmd = GeneralCommandLine()
            .withExePath(uv?.toString() ?: "uv")
            .withParameters("add", "--dev", "basedpython")
            .withWorkDirectory(base.toFile())
            .withCharset(Charsets.UTF_8)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val handler = OSProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        if (event.exitCode == 0) {
                            UvSupport.notify(
                                project, BasedPythonBundle.message("install.basedpython.title"),
                                BasedPythonBundle.message("install.basedpython.success"), NotificationType.INFORMATION,
                            )
                            ApplicationManager.getApplication().invokeLater {
                                EditorNotifications.getInstance(project).updateAllNotifications()
                            }
                        } else {
                            UvSupport.notify(
                                project, BasedPythonBundle.message("install.basedpython.title"),
                                BasedPythonBundle.message("install.basedpython.exitCode", event.exitCode),
                                NotificationType.ERROR,
                            )
                        }
                    }
                })
                handler.startNotify()
            } catch (ex: Exception) {
                UvSupport.notify(
                    project, BasedPythonBundle.message("install.basedpython.title"),
                    BasedPythonBundle.message("install.basedpython.startFailed", ex.message ?: ""), NotificationType.ERROR,
                )
            }
        }
    }

    private companion object {
        /** Files the user dismissed the banner for, for this IDE session. */
        private val dismissed: MutableSet<VirtualFile> =
            java.util.Collections.synchronizedSet(java.util.HashSet())
    }
}

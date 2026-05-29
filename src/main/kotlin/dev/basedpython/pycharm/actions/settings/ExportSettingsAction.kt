package dev.basedpython.pycharm.actions.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Serializes the current project's [BasedPythonSettings] state to a user-chosen `.xml`
 * file so it can be shared across machines / projects and re-applied via
 * [ImportSettingsAction].
 */
class ExportSettingsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileSaverDescriptor(
            "Export basedpython Settings",
            "Choose where to write the basedpython settings file.",
            "xml",
        )
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = dialog.save(null as com.intellij.openapi.vfs.VirtualFile?, DEFAULT_FILE_NAME) ?: return
        val path = wrapper.file.toPath()

        try {
            val state = BasedPythonSettings.getInstance(project).state
            val element = XmlSerializer.serialize(state)
            JDOMUtil.write(element, path)
            notify(
                project,
                "basedpython settings exported",
                "Settings written to ${path.fileName}.",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            notify(
                project,
                "basedpython settings export failed",
                ex.message ?: ex.javaClass.simpleName,
                NotificationType.ERROR,
            )
        }
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID = "BasedPython.Actions"
        const val DEFAULT_FILE_NAME = "basedpython-settings.xml"
    }
}

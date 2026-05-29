package dev.basedpython.pycharm.actions.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Reads a basedpython settings file previously written by [ExportSettingsAction] and applies
 * it to the current project's [BasedPythonSettings], then restarts the LSP servers so the
 * imported configuration takes effect.
 */
class ImportSettingsAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Import basedpython Settings")
            .withDescription("Choose a basedpython settings file (.xml) to import.")
            .withFileFilter { it.extension.equals("xml", ignoreCase = true) }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = chosen.toNioPath()

        try {
            val element = JDOMUtil.load(path)
            val imported = XmlSerializer.deserialize(element, BasedPythonSettings.State::class.java)

            val settings = BasedPythonSettings.getInstance(project)
            XmlSerializerUtil.copyBean(imported, settings.state)

            BasedPythonLspReloader.getInstance(project).onSettingsChanged()

            notify(
                project,
                "basedpython settings imported",
                "Settings applied from ${path.fileName}. Language servers will restart.",
                NotificationType.INFORMATION,
            )
        } catch (ex: Exception) {
            notify(
                project,
                "basedpython settings import failed",
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
    }
}

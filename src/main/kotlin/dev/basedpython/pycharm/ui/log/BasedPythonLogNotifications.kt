package dev.basedpython.pycharm.ui.log

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Builds `basedpython.Actions` notifications carrying the common plugin actions:
 * Open Settings, Restart LSP, and View Log.
 */
internal object BasedPythonLogNotifications {

    /** Notification group id (see [dev.basedpython.pycharm.actions.ByCli]). */
    private const val GROUP_ID = "basedpython.Actions"

    /** Settings configurable id (see plugin.xml / BasedPythonWelcomeActivity). */
    private const val SETTINGS_ID = "dev.basedpython.pycharm.settings"

    /** Action id of the existing Restart LSP action (plugin.xml). */
    private const val RESTART_LSP_ACTION_ID = "basedpython.RestartLsp"

    /** Tool window id of the basedpython log console. */
    const val TOOL_WINDOW_ID = "basedpython"

    /**
     * Build a notification of [type] carrying the Open Settings, Restart LSP and
     * View Log actions. Callers invoke `.notify(project)` on the result.
     */
    fun create(
        project: Project,
        title: String,
        content: String,
        type: NotificationType = NotificationType.INFORMATION,
    ): Notification {
        return NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .addAction(openSettings(project))
            .addAction(restartLsp(project))
            .addAction(viewLog(project))
    }

    fun openSettings(project: Project): NotificationAction =
        NotificationAction.createSimple(BasedPythonBundle.message("notification.action.openSettings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_ID)
        }

    fun restartLsp(project: Project): NotificationAction =
        NotificationAction.createSimple(BasedPythonBundle.message("notification.action.restartLsp")) {
            val action = ActionManager.getInstance().getAction(RESTART_LSP_ACTION_ID)
                ?: return@createSimple
            ActionUtil.invokeAction(
                action,
                SimpleDataContext.getProjectContext(project),
                ActionPlaces.NOTIFICATION,
                null,
                null,
            )
        }

    fun viewLog(project: Project): NotificationAction =
        NotificationAction.createSimple(BasedPythonBundle.message("notification.action.viewLog")) {
            ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)
                ?.activate(null)
        }
}

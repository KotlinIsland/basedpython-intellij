package dev.basedpython.pycharm.lsp

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.ui.BasedPythonSettingsPage
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces user-visible warnings for the basedpython plugin via the `basedpython`
 * notification group (registered in plugin.xml).
 *
 * Each `(project, key)` pair only fires once per IDE session to avoid balloon spam
 * (e.g. one warning per file opened when a binary is missing).
 */
internal object BasedPythonNotifications {
  private const val GROUP_ID = "basedpython"

  /** *Download Prebuilt by/buff Binaries* (plugin.xml). */
  private const val DOWNLOAD_ACTION_ID = "dev.basedpython.pycharm.env.download.DownloadBinaries"

  private val shown = ConcurrentHashMap.newKeySet<String>()

  /**
   * [binaryMissing], once per project in an IDE session: what a server start says.
   *
   * Once per project rather than per binary, naming every server binary missing at that moment:
   * opening one `.by` file starts both servers, and with neither installed that was two balloons
   * saying the same thing. *Download* fetches whichever are missing.
   */
  fun warnBinaryMissing(project: Project, binary: String) {
    if (!shown.add(project.locationHash)) return
    val settings = BasedPythonSettings.getInstance(project)
    val missing = buildList {
      if (binary == BasedPythonBinaries.BIN_BY || (settings.byEnabled && !BasedPythonBinaries.isByAvailable(project))) add(BasedPythonBinaries.BIN_BY)
      if (binary == BasedPythonBinaries.BIN_BUFF || (settings.buffEnabled && !BasedPythonBinaries.isBuffAvailable(project))) add(BasedPythonBinaries.BIN_BUFF)
    }
    binaryMissing(project, missing).notify(project)
  }

  /**
   * That [binary] was not found, with the two ways out as actions: point Settings at it, or download
   * it. Shared by a server that could not start and a command that could not run, which used to say
   * this in two different wordings, one naming a settings page that does not exist and neither
   * offering to do anything about it.
   */
  fun binaryMissing(project: Project, binary: String): Notification = binaryMissing(project, listOf(binary))

  private fun binaryMissing(project: Project, binaries: List<String>): Notification {
    // the title quotes each name ('by' and 'buff'); the sentence in the content does not
    val title = binaries.joinToString(" and ") { BasedPythonBundle.message("notification.lspBinaryMissing.name", it) }
    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(GROUP_ID)
      .createNotification(
        BasedPythonBundle.message("notification.lspBinaryMissing.title", title),
        BasedPythonBundle.message("notification.lspBinaryMissing.content", binaries.joinToString(" and ")),
        NotificationType.WARNING,
      )
      .addAction(NotificationAction.createSimpleExpiring(BasedPythonBundle.message("notification.action.configure")) {
        BasedPythonSettingsPage.show(project)
      })
    val download = ActionManager.getInstance().getAction(DOWNLOAD_ACTION_ID)
    if (download != null) {
      notification.addAction(NotificationAction.createSimpleExpiring(BasedPythonBundle.message("notification.action.download")) {
        val event = AnActionEvent.createEvent(
          download, SimpleDataContext.getProjectContext(project), null, ActionPlaces.NOTIFICATION, ActionUiKind.POPUP, null,
        )
        ActionUtil.performAction(download, event)
      })
    }
    return notification
  }
}

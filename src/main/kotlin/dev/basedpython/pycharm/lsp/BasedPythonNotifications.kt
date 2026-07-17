package dev.basedpython.pycharm.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
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
  private val shown = ConcurrentHashMap.newKeySet<String>()

  fun warnBinaryMissing(project: Project, binary: String) {
    val key = "${project.locationHash}:$binary"
    if (!shown.add(key)) return
    NotificationGroupManager.getInstance()
      .getNotificationGroup(GROUP_ID)
      .createNotification(
        BasedPythonBundle.message("notification.lspBinaryMissing.title", binary),
        BasedPythonBundle.message("notification.lspBinaryMissing.content"),
        NotificationType.WARNING,
      )
      .notify(project)
  }
}

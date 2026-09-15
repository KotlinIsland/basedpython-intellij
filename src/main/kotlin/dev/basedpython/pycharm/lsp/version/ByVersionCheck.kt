package dev.basedpython.pycharm.lsp.version

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The version a running basedpython server reported for itself, or `null` when none is running or
 * it said nothing.
 *
 * Read from the `initialize` reply (`serverInfo.version`), which both `by` and `buff` send: the
 * server that is actually answering is the one whose version matters, and asking it costs nothing,
 * where running `<binary> version` to find out spawns a second process that may not even be the
 * same binary.
 */
internal fun runningServerVersion(project: Project, provider: Class<out LspIntegrationProvider>): String? =
    LspClientManager.getInstance(project).getClients(provider)
        .firstOrNull { it.state == LspServerState.Running }
        ?.initializeResult?.serverInfo?.version

/**
 * When `by` starts, warns once if the version it reports is below [MIN_BY_VERSION], with an action
 * to open settings.
 *
 * Asks the server rather than the binary, so it only ever looks at a `by` that is running — none in
 * a project with `by` switched off or nowhere to be found, where the missing-binary banner
 * ([dev.basedpython.pycharm.env.ByMissingBannerProvider]) says what there is to say — and it never
 * starts a process of its own.
 */
internal class ByVersionCheck(private val project: Project) : ByLspLifecycleListener {

    override fun serverInitialized(serverName: String) {
        if (serverName != "by" || project.isDisposed) return
        val detected = outdated(runningServerVersion(project, ByLspServerSupportProvider::class.java)) ?: return

        // One-shot guard, keyed by detected version: re-warn only if the version changes.
        val flagKey = "$WARNED_KEY_PREFIX$detected"
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(flagKey, false)) return
        props.setValue(flagKey, true)

        ApplicationManager.getApplication().invokeLater({ notifyOutdated(detected) }, project.disposed)
    }

    private fun notifyOutdated(detected: ByVersion) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ByCli.NOTIFICATION_GROUP_ID)
            .createNotification(
                BasedPythonBundle.message("notification.byOutdated.title"),
                BasedPythonBundle.message("notification.byOutdated.content", detected, MIN_BY_VERSION),
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimple(BasedPythonBundle.message("notification.action.openSettings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "basedpython")
            })
            .notify(project)
    }

    companion object {
        private const val WARNED_KEY_PREFIX = "dev.basedpython.pycharm.lsp.version.warned."

        /** The version in [reported] when it is below [MIN_BY_VERSION]; `null` when it is not, or says none. */
        fun outdated(reported: String?): ByVersion? {
            val detected = ByVersion.parse(reported) ?: return null
            val minimum = ByVersion.parse(MIN_BY_VERSION) ?: return null
            return detected.takeIf { it < minimum }
        }
    }
}

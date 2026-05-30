package dev.basedpython.pycharm.lsp.version

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * On project open, runs `by --version` in the background. If the resolved `by`
 * binary reports a version below [MIN_BY_VERSION], shows a one-time warning
 * notification (with an action to open settings).
 *
 * If `by` is not resolvable, this does nothing — the missing-binary banner
 * ([dev.basedpython.pycharm.env.ByMissingBannerProvider]) covers that case.
 */
internal class ByVersionCheckActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return

        // ByCli.run resolves the binary; returns null (and notifies) when missing.
        // Detect missing-ness up front so we stay silent per the contract.
        val output = try {
            ByCli.run(project, "--version", title = "by --version")
        } catch (e: Exception) {
            LOG.warn("Failed to run `by --version`", e)
            null
        } ?: return

        if (output.exitCode != 0) return

        val raw = output.stdout.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return

        val detected = ByVersion.parse(raw) ?: return
        val minimum = ByVersion.parse(MIN_BY_VERSION) ?: return
        if (detected >= minimum) return

        // One-shot guard, keyed by detected version: re-warn only if the version changes.
        val flagKey = "$WARNED_KEY_PREFIX$detected"
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(flagKey, false)) return
        props.setValue(flagKey, true)

        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater({ notifyOutdated(project, detected) }, project.disposed)
    }

    private fun notifyOutdated(project: Project, detected: ByVersion) {
        if (project.isDisposed) return
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
        private val LOG = Logger.getInstance(ByVersionCheckActivity::class.java)
        private const val WARNED_KEY_PREFIX = "dev.basedpython.pycharm.lsp.version.warned."
    }
}

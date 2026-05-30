package dev.basedpython.pycharm.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.basedpython.pycharm.docs.BasedPythonDocEntries
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * First-run welcome notification.
 *
 * The first time a project that contains `.by` files (or that has a resolvable
 * `by`/`buff` binary) is opened after the plugin is installed, this shows a
 * single, sticky "Welcome to basedpython" notification with quick links to the
 * settings page and the online documentation. The notification is shown at most
 * once ever, gated by an application-level flag in [PropertiesComponent].
 */
internal class BasedPythonWelcomeActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(WELCOME_SHOWN_KEY, false)) return

        if (!project.isBasedPythonProject()) return

        // Re-check + set the flag together to avoid a duplicate from a second
        // project opening concurrently.
        if (props.getBoolean(WELCOME_SHOWN_KEY, false)) return
        props.setValue(WELCOME_SHOWN_KEY, true)

        showWelcome(project)
    }

    private suspend fun Project.isBasedPythonProject(): Boolean {
        val project = this
        val hasByFiles = readAction {
            DumbService.getInstance(project).runReadActionInSmartMode<Boolean> {
                FileTypeIndex.getFiles(
                    BasedPythonFileType.INSTANCE,
                    GlobalSearchScope.projectScope(project),
                ).isNotEmpty()
            }
        }
        if (hasByFiles) return true
        return BasedPythonBinaries.resolveBy(project) != null ||
            BasedPythonBinaries.resolveBuff(project) != null
    }

    private fun showWelcome(project: Project) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                BasedPythonBundle.message("notification.welcome.title"),
                BasedPythonBundle.message("notification.welcome.content"),
                NotificationType.INFORMATION,
            )
            .setImportant(true)

        notification
            .addAction(
                NotificationAction.createSimple({ BasedPythonBundle.message("notification.action.openSettings") }) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_ID)
                },
            )
            .addAction(
                NotificationAction.createSimple({ BasedPythonBundle.message("notification.action.documentation") }) {
                    BrowserUtil.browse(BasedPythonDocEntries.DOCS_BASE)
                },
            )
            .addAction(
                NotificationAction.createSimple({ BasedPythonBundle.message("notification.action.dontShowAgain") }) {
                    PropertiesComponent.getInstance().setValue(WELCOME_SHOWN_KEY, true)
                    notification.expire()
                },
            )
            .notify(project)
    }

    private companion object {
        const val NOTIFICATION_GROUP_ID: String = "BasedPython.Actions"
        const val WELCOME_SHOWN_KEY: String = "dev.basedpython.pycharm.welcomeShown"

        /** Matches the `<projectConfigurable id>` registered in plugin.xml. */
        const val SETTINGS_ID: String = "dev.basedpython.pycharm.settings"
    }
}

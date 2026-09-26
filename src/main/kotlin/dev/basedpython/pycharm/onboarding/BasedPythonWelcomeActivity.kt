package dev.basedpython.pycharm.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.basedpython.pycharm.docs.BasedPythonDocEntries
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.settings.ui.BasedPythonSettingsPage
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * First-run welcome notification.
 *
 * The first time a basedpython project is opened after the plugin is installed, this shows a
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

    /**
     * True when this project has anything to do with basedpython.
     *
     * A resolvable `by` binary used to count on its own, which meant installing the CLI once made
     * every project — Rust, JS, anything — greet the user. It now takes actual basedpython content:
     * a `.by` file somewhere in the project, or a basedpython marker at the project base.
     */
    private suspend fun Project.isBasedPythonProject(): Boolean {
        val project = this
        if (BasedPythonProjectDetector.isBasedPythonProject(project)) return true
        // Waits for indexing to finish. `runReadActionInSmartMode` inside a read action never
        // waited — it ran at once, and threw IndexNotReadyException when the project was dumb.
        return smartReadAction(project) {
            FileTypeIndex.getFiles(
                BasedPythonFileType.INSTANCE,
                GlobalSearchScope.projectScope(project),
            ).isNotEmpty()
        }
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
                    BasedPythonSettingsPage.show(project)
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
        const val NOTIFICATION_GROUP_ID: String = "basedpython.Actions"
        const val WELCOME_SHOWN_KEY: String = "dev.basedpython.pycharm.welcomeShown"
    }
}

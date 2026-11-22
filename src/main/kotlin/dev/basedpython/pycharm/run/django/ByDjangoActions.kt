package dev.basedpython.pycharm.run.django

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.console.ByConsoleSetup
import dev.basedpython.pycharm.console.ByConsoles
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureToolWindow
import dev.basedpython.pycharm.util.BasedPythonBundle

/** Shown in a basedpython project that looks like a django one, as the django tool window is. */
internal abstract class ByDjangoProjectAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            BasedPythonProjectDetector.isBasedPythonProject(project) &&
            (ByDjangoStructureService.getInstance(project).current() != null || ByDjangoStructureToolWindow.looksLikeDjango(project))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** *Tools | Run manage.py Task…*: the *manage.py* window's Commands view, its command line focused ([ByManageCommandsPanel]). */
internal class ByRunManageTaskAction : ByDjangoProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ByManageToolWindow.edit(project, null)
    }
}

/**
 * *Tools | Django Console*: a basedpython console with django set up — `DJANGO_SETTINGS_MODULE` the
 * settings module `by` names (`by/djangoStructure`) and `django.setup()` run first — so the models
 * import and query as in `manage.py shell`, with `by`'s completion. A Django console already running
 * is shown rather than another started.
 */
internal class ByDjangoConsoleAction : ByDjangoProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>(
            { settingsModule(project) },
            BasedPythonBundle.message("django.console.asking"),
            true,
            project,
        )
        if (settings == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
                .createNotification(BasedPythonBundle.message("django.console.noSettings"), NotificationType.WARNING)
                .notify(project)
            return
        }
        ApplicationManager.getApplication().invokeLater({ ByConsoles.getInstance(project).show(setup(settings)) }, project.disposed)
    }

    private fun settingsModule(project: Project): String? {
        val service = ByDjangoStructureService.getInstance(project)
        return (service.fresh() ?: service.current())?.projects?.firstNotNullOfOrNull { it.settings?.name }
    }

    companion object {
        fun setup(settings: String) = ByConsoleSetup(
            title = BasedPythonBundle.message("django.console.tab"),
            environment = mapOf("DJANGO_SETTINGS_MODULE" to settings),
            startup = "import django\ndjango.setup()",
        )
    }
}

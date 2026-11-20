package dev.basedpython.pycharm.database

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.basedpython.pycharm.navigation.django.ByDataSourceSpec
import dev.basedpython.pycharm.navigation.django.ByDjangoDataSources
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * *Add DATABASES as Data Sources*, in the *basedpython Django* tool window and *Tools*: each database
 * of the project's `DATABASES` that `by/djangoStructure` reads, made a data source of the Database
 * tool window ([ByDjangoDataSources] says which driver and URL), unless one with its URL is there
 * already, and that window shown. Loaded only with the Database plugin.
 */
internal class ByDjangoDataSourcesAction : DumbAwareAction(
    BasedPythonBundle.message("django.dataSources.text"),
    BasedPythonBundle.message("django.dataSources.description"),
    AllIcons.Nodes.DataSchema,
) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && specs(project).isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = LocalDataSourceManager.getInstance(project)
        val existing = manager.dataSources.mapNotNull { it.url }.toSet()
        val added = specs(project).filter { it.url !in existing }.mapNotNull { spec -> create(spec)?.also(manager::addDataSource) }
        NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
            .createNotification(
                if (added.isEmpty()) BasedPythonBundle.message("django.dataSources.none")
                else BasedPythonBundle.message("django.dataSources.added", added.size, added.joinToString(", ") { it.name }),
                NotificationType.INFORMATION,
            )
            .notify(project)
        ToolWindowManager.getInstance(project).getToolWindow(DATABASE_WINDOW)?.activate(null)
    }

    private fun create(spec: ByDataSourceSpec): LocalDataSource? {
        val driver = DatabaseDriverManager.getInstance().getDriver(spec.driver) ?: return null
        return LocalDataSource().apply {
            name = spec.name
            url = spec.url
            spec.user?.let { username = it }
            databaseDriver = driver
            resolveDriver()
        }
    }

    private fun specs(project: Project): List<ByDataSourceSpec> =
        ByDjangoStructureService.getInstance(project).current()?.projects.orEmpty().flatMap { django ->
            ByDjangoDataSources.specs(django.databases, django.settings?.name?.substringBefore('.') ?: project.name)
        }

    private companion object {
        /** The Database plugin's tool window. */
        const val DATABASE_WINDOW = "Database"
    }
}

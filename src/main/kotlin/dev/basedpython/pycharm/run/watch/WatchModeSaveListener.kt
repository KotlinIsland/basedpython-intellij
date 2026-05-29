package dev.basedpython.pycharm.run.watch

import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.Alarm
import java.nio.file.Paths

/**
 * Application-level [FileDocumentManagerListener] that, when watch mode is enabled
 * for a project, triggers a debounced background `by build` after a `.by` file is saved.
 *
 * Registered via `<applicationListeners>` on the
 * [com.intellij.AppTopics#FILE_DOCUMENT_SYNC] topic. Debounce is per-project so rapid
 * saves coalesce into a single build.
 */
internal class WatchModeSaveListener : FileDocumentManagerListener {

    // One pooled-thread alarm per project; coalesces rapid saves.
    private val alarms = HashMap<Project, Alarm>()

    @Synchronized
    private fun alarmFor(project: Project): Alarm =
        alarms.getOrPut(project) { Alarm(Alarm.ThreadToUse.POOLED_THREAD, project) }

    override fun beforeDocumentSaving(document: Document) {
        if (!isBasedPythonFile(document)) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            if (!WatchModeState.isEnabled(project)) continue
            scheduleBuild(project)
        }
    }

    private fun isBasedPythonFile(document: Document): Boolean {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return false
        return file.fileType == BasedPythonFileType.INSTANCE
    }

    private fun scheduleBuild(project: Project) {
        val alarm = alarmFor(project)
        alarm.cancelAllRequests()
        alarm.addRequest({ runBuild(project) }, DEBOUNCE_MS)
    }

    private fun runBuild(project: Project) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val cwd = project.basePath?.let { Paths.get(it) }
            ByCli.run(project, "build", cwd = cwd, title = "Watch: by build")
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 500
    }
}

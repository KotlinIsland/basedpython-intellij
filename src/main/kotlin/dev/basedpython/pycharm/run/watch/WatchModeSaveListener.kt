package dev.basedpython.pycharm.run.watch

import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.run.ByBuildService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile

/**
 * Application-level [FileDocumentManagerListener] that, when watch mode is enabled for the project
 * a saved `.by` file belongs to, asks that project for a `by build`.
 *
 * Holds no state of its own: the debounce, the one-build-at-a-time rule and the lifetime of both
 * are [ByBuildService]'s, which is a project service and so goes away with its project.
 *
 * Registered via `<applicationListeners>` on the [com.intellij.AppTopics#FILE_DOCUMENT_SYNC] topic.
 */
internal class WatchModeSaveListener : FileDocumentManagerListener {

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        for (project in projectsToBuild(file)) {
            ByBuildService.getInstance(project).requestWatchBuild()
        }
    }
}

/**
 * The projects a save of [file] should rebuild: those that hold it and have watch mode on.
 *
 * Only the file's own projects. A save used to start `by build` in every open project with watch
 * mode enabled, whether or not the file had anything to do with it.
 */
internal fun projectsToBuild(file: VirtualFile): List<Project> {
    if (file.fileType != BasedPythonFileType.INSTANCE) return emptyList()
    return ProjectLocator.getInstance().getProjectsForFile(file)
        .filter { !it.isDisposed && WatchModeState.isEnabled(it) }
}

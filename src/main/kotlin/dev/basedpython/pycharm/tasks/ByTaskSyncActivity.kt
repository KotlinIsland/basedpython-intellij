package dev.basedpython.pycharm.tasks

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.PathUtil

/**
 * Keeps the task view in step with the files it reads.
 *
 * The whole point of scanning being cheap is spent here: a hook added to `.pre-commit-config.yaml`
 * appears in the view a moment after the file is saved, with no Refresh and no process started. The
 * test view cannot do that — its data costs a `by run` — and has a 2.5-second debounce and an
 * explicit Refresh for exactly that reason.
 *
 * Subscribed for every project, including ones with no configuration at all, because that is the
 * case that has to keep working: `pre-commit sample-config > .pre-commit-config.yaml` in a terminal
 * should end with a tool window appearing, not with a restart.
 */
internal class ByTaskSyncActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        val service = ByTaskService.getInstance(project)
        service.refreshIfNeeded()

        project.messageBus.connect(service).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val base = project.basePath ?: return
                    if (touchesTaskConfig(events.flatMap(::eventPaths), base)) service.scheduleSync()
                }
            },
        )
    }
}

/**
 * The paths [event] names, before and after: a rename or a move *to* one of the configuration names
 * adds tasks as surely as a rename away from one removes them.
 */
internal fun eventPaths(event: VFileEvent): List<String> = when (event) {
    is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent, is VFileCopyEvent -> listOf(event.path)
    is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
    is VFilePropertyChangeEvent ->
        if (event.propertyName == VirtualFile.PROP_NAME) listOf(event.oldPath, event.newPath) else emptyList()
    else -> emptyList()
}

/**
 * True when one of [paths] is a file a scan of the project at [basePath] reads.
 *
 * The VFS topic is application-wide — every project's listener hears every project's changes — and
 * a scan reads a fixed list of names *at the project root*, so that is what has to match: the name
 * and the directory. Matching the name alone rescanned every open project whenever any of them
 * saved a `pyproject.toml`, and whenever one in a subdirectory changed.
 */
internal fun touchesTaskConfig(paths: List<String>, basePath: String): Boolean =
    paths.any { path ->
        ByTaskScan.isConfigFile(PathUtil.getFileName(path)) &&
            FileUtil.pathsEqual(PathUtil.getParentPath(path), FileUtil.toSystemIndependentName(basePath))
    }

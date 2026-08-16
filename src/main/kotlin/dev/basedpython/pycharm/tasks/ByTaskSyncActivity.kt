package dev.basedpython.pycharm.tasks

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
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
                    if (events.any(::isConfigChange)) service.scheduleSync()
                }
            },
        )
    }

    /**
     * True when [event] touched a file a scan reads.
     *
     * Matched on the name alone, so a `pyproject.toml` in a subdirectory triggers a scan of the
     * root one. That costs a handful of file reads and keeps this from having to reason about
     * content roots; the alternative — comparing full paths — would also have to handle a project
     * base that is a symlink, which is where such comparisons quietly stop matching.
     */
    private fun isConfigChange(event: VFileEvent): Boolean {
        val path = when (event) {
            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent,
            is VFileMoveEvent, is VFileCopyEvent,
            -> event.path
            // A rename arrives as a property change, and renaming a file *to* one of these names is
            // exactly the kind of change that adds tasks.
            is VFilePropertyChangeEvent -> event.path.takeIf { event.propertyName == VirtualFile.PROP_NAME }
            else -> null
        } ?: return false
        return ByTaskScan.isConfigFile(path.substringAfterLast('/'))
    }
}

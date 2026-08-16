package dev.basedpython.pycharm.env.manager

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
 * Keeps the environment view in step with the files that decide what it says.
 *
 * Two kinds of change matter, and they are watched together because the answer is the same either
 * way: the manifests (`pyproject.toml`, `uv.lock`, and whatever a future backend adds), which decide
 * what the environment *should* contain, and the environment's own marker file, which decides
 * whether there is one at all. A `uv sync` in a terminal touches the second; editing a dependency
 * touches the first; both should leave the tool window telling the truth without a Refresh.
 *
 * Subscribed for every project, including ones with no manifest at all, because that is the case
 * that has to keep working: `uv init` in a terminal should end with a tool window appearing rather
 * than with a restart.
 */
internal class EnvSyncActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        val service = EnvService.getInstance(project)
        service.refreshIfNeeded()

        project.messageBus.connect(service).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isRelevant)) service.scheduleRefresh()
                }
            },
        )
    }

    /**
     * True when [event] touched a file the scan reads.
     *
     * Matched on the file name alone, as the task view's watcher is: a manifest in a subdirectory
     * triggers a re-read of the root one, which costs a debounced scan and keeps this from having to
     * reason about content roots or about a project base that is a symlink — where path comparisons
     * quietly stop matching.
     */
    private fun isRelevant(event: VFileEvent): Boolean {
        val path = when (event) {
            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent,
            is VFileMoveEvent, is VFileCopyEvent,
            -> event.path
            // A rename arrives as a property change, and renaming a file *to* one of these names is
            // exactly the change that turns an unmanaged project into a managed one.
            is VFilePropertyChangeEvent -> event.path.takeIf { event.propertyName == VirtualFile.PROP_NAME }
            else -> null
        } ?: return false
        return isWatchedName(path.substringAfterLast('/'))
    }

    private fun isWatchedName(name: String): Boolean = name in WATCHED

    private companion object {
        /**
         * The manifests every backend declares, plus the file that marks a virtual environment.
         *
         * `pyvenv.cfg` is what makes "someone ran `uv sync` in a terminal" visible: creating an
         * environment writes it, and deleting the environment takes it away, so watching it covers
         * both directions of the state the banner leads with.
         */
        val WATCHED: Set<String> = EnvBackends.ALL_MARKERS + "pyvenv.cfg"
    }
}

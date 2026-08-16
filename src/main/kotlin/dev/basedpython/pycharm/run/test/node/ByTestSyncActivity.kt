package dev.basedpython.pycharm.run.test.node

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
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Keeps the test view in step with the project: collects once at startup, and again whenever the
 * files that decide what pytest collects have changed.
 *
 * Collecting on open is what makes the view — and the gutter icons that share its data — right
 * before anyone asks. It costs one `by run` per project open, which is why it is gated on the
 * project actually being basedpython with `by` switched on.
 *
 * The re-collection is deliberately coarse. Any `.by` or `.py` changing counts, not just a file
 * named like a test: a `conftest.by`, a helper a test imports, or a type error anywhere at all can
 * change what `by run pytest` collects — `by run` refuses to run while the project has diagnostics,
 * so an unrelated file can empty the view and fixing it must fill the view back in. What keeps that
 * affordable is the debounce in [ByTestNodeService.scheduleSync], not a narrow filter.
 */
internal class ByTestSyncActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        // Never spawn `by` for a project that is not basedpython, or has switched it off: the same
        // rule the tool window and the version check are offered under.
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return
        if (!BasedPythonSettings.getInstance(project).byEnabled) return

        val service = ByTestNodeService.getInstance(project)
        service.refreshIfNeeded()

        project.messageBus.connect(service).subscribe(
                VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::isSourceChange)) service.scheduleSync()
                }
            },
        )
    }

    /** True when [event] is a change to a project source file that could move what pytest collects. */
    private fun isSourceChange(event: VFileEvent): Boolean {
        val path = when (event) {
            is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent,
            is VFileMoveEvent, is VFileCopyEvent,
            -> event.path
            // A rename arrives as a property change, and renaming `helper.by` to `test_helper.by`
            // is exactly the kind of change that adds tests.
            is VFilePropertyChangeEvent -> event.path.takeIf { event.propertyName == VirtualFile.PROP_NAME }
            else -> null
        } ?: return false
        return isSource(path)
    }

    /**
     * True for a `.by` or `.py` file that belongs to the project rather than to its output or its
     * environment.
     *
     * `out/` is what `by build` wrote and `.venv` is somebody else's code; collecting again because
     * either changed would mean collecting again because *we* collected, since a run writes into
     * both.
     */
    private fun isSource(path: String): Boolean {
        if (!path.endsWith(BY) && !path.endsWith(PY)) return false
        val segments = path.split('/')
        return segments.none { it in EXCLUDED || (it.startsWith(".") && it.length > 1) }
    }

    private companion object {
        const val BY = ".by"
        const val PY = ".py"
        val EXCLUDED = setOf("out", "build", "dist", "node_modules", "__pycache__", "venv", "site-packages")
    }
}

package dev.basedpython.pycharm.run.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
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
 * Keeps [ByProgramModel]'s project-wide answers in step with the project's files.
 *
 * Asking again is cheap — two requests to a server holding the project warm, no process started —
 * so any change to a source file or to the configuration that decides module roots and `run.main`
 * counts. What it must not count is a change outside this project: the VFS topic is
 * application-wide, so every open project hears every other project's saves, and a file under
 * the build directory or `.venv` is `by`'s output or somebody else's code. Both are settled by asking the project
 * whether the file is in its content, which is also what honours the directories a user excluded.
 */
internal class ByProgramModelSyncActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (project.isDisposed) return
        // Never ask `by` about a project that is not basedpython, or has switched it off.
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return
        if (!BasedPythonSettings.getInstance(project).byEnabled) return

        val model = ByProgramModel.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        project.messageBus.connect(model).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                // A deleted file can only be asked about while it still exists.
                override fun before(events: List<VFileEvent>) {
                    if (events.any { it is VFileDeleteEvent && touchesProgram(it, index::isInContent) }) {
                        model.requestProjectRefresh()
                    }
                }

                override fun after(events: List<VFileEvent>) {
                    if (events.any { it !is VFileDeleteEvent && touchesProgram(it, index::isInContent) }) {
                        model.requestProjectRefresh()
                    }
                }
            },
        )
    }
}

/**
 * True when [event] changes a file of this project that decides how its modules run or which tests
 * it holds: a `.by`/`.py` source, or the configuration naming its roots and `run.main`.
 *
 * [inContent] is asked of the file the event is about, so a file of another project, or one under
 * a directory this project excludes, does not count.
 */
internal fun touchesProgram(event: VFileEvent, inContent: (VirtualFile) -> Boolean): Boolean {
    val file = when (event) {
        is VFileContentChangeEvent, is VFileCreateEvent, is VFileDeleteEvent, is VFileMoveEvent -> event.file
        is VFileCopyEvent -> event.findCreatedFile()
        // A rename arrives as a property change, and renaming `helper.by` to `test_helper.by` is
        // exactly the kind of change that adds tests.
        is VFilePropertyChangeEvent -> event.file.takeIf { event.propertyName == VirtualFile.PROP_NAME }
        else -> null
    } ?: return false
    val relevant = if (file.isDirectory) {
        // A package moved or renamed renames every module in it; the interpreter's bytecode cache
        // appearing beside the sources renames nothing.
        file.name != BYTECODE_CACHE
    } else {
        file.extension in SOURCE_EXTENSIONS || file.name in CONFIGURATION_FILES
    }
    return relevant && inContent(file)
}

private const val BYTECODE_CACHE = "__pycache__"

private val SOURCE_EXTENSIONS = setOf("by", "py")

/** Where a project's module roots and `run.main` are configured. */
private val CONFIGURATION_FILES = setOf("pyproject.toml", "ty.toml", "basedpython.toml")

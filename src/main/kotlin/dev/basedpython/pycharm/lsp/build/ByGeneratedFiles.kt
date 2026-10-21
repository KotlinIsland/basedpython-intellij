package dev.basedpython.pycharm.lsp.build

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * What `by build` wrote: every file under a build directory `by` reported for this project.
 *
 * All of it, not only the transpiled modules. The build also copies every hand-written file in
 * beside them and writes its sourcemap, its runtime and its record there, and deletes whatever the
 * previous build wrote and it did not — so an edit to any file in there is one the next build takes
 * back.
 *
 * Which directories is `by`'s answer ([ByBuildOutputs.buildDirectories]), never a guess at `build/`,
 * so a project `by` has never answered for has nothing generated; and the answer is kept, so asking
 * never blocks.
 *
 * Also keeps which generated files the user chose to edit anyway — here, a project service, rather
 * than on the files, so that nothing of this plugin's outlives it on a platform object.
 */
@Service(Service.Level.PROJECT)
internal class ByGeneratedFiles(private val project: Project) {

    private val editable = ConcurrentHashMap.newKeySet<String>()

    fun isGenerated(file: VirtualFile): Boolean {
        if (!file.isInLocalFileSystem || file.isDirectory) return false
        val path = file.path
        return ByBuildOutputs.getInstance(project).buildDirectories.any { FileUtil.isAncestor(it, path, true) }
    }

    /** Whether the user already said to edit [file] anyway. */
    fun isEditable(file: VirtualFile): Boolean = file.path in editable

    fun allowEditing(files: Collection<VirtualFile>) {
        files.mapTo(editable) { it.path }
    }

    /** Forgets every *Edit Anyway*, which otherwise lasts as long as the project: for a test. */
    @TestOnly
    fun forgetEditing() = editable.clear()

    /**
     * The `.by` [file] was transpiled from, when `by` has said: [ByBuildOutputs.warm] asks when the
     * file is opened, and nothing here waits for the answer.
     */
    fun source(file: VirtualFile): VirtualFile? =
        ByBuildOutputs.getInstance(project).cached(file)?.source?.let { LocalFileSystem.getInstance().findFileByPath(it) }

    /** [source] as the banner names it: relative to the project, where it is in the project. */
    fun describe(source: VirtualFile): String {
        val base = project.basePath ?: return source.path
        return FileUtil.getRelativePath(base, source.path, '/')?.takeUnless { it.startsWith("..") } ?: source.path
    }

    companion object {
        fun getInstance(project: Project): ByGeneratedFiles = project.service()
    }
}

/**
 * Tells the platform the build's output is generated: Find Usages groups and can hide what it finds
 * there, Reformat Code and the commit checks leave it alone, the *Generated Files* scope holds it —
 * which counts once the build directory is indexed (Settings | basedpython | index generated `.py`),
 * as it is excluded otherwise.
 *
 * It is also what puts the platform's warning banner on a generated file, worded by
 * [getNotificationText]. Going back to the source is [ByGeneratedFileBanner]'s.
 */
class ByGeneratedSourcesFilter : GeneratedSourcesFilter() {

    override fun isGeneratedSource(file: VirtualFile, project: Project): Boolean =
        ByGeneratedFiles.getInstance(project).isGenerated(file)

    override fun getNotificationText(file: VirtualFile, project: Project): String =
        BasedPythonBundle.message("generated.banner.warning")
}

/**
 * The banner that names the `.by` a transpiled file came from, with a link that opens it.
 *
 * Separate from the platform's banner because that one can only say something: its links open web
 * pages. Shown once `by` has said which source it is, which it is asked when the file is opened.
 */
class ByGeneratedFileBanner : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val generated = ByGeneratedFiles.getInstance(project)
        if (!generated.isGenerated(file)) return null
        val source = generated.source(file) ?: return null
        val name = generated.describe(source)
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text = BasedPythonBundle.message("generated.banner.source", name)
                createActionLabel(BasedPythonBundle.message("generated.banner.open", source.name)) {
                    if (source.isValid) OpenFileDescriptor(project, source).navigate(true)
                }
            }
        }
    }
}

/**
 * Makes the build's output read-only unless the user insists: the first edit of a generated file
 * asks, and *Edit Anyway* lets that file be edited for the rest of the session.
 *
 * Only edits made in the IDE ask. The build itself writes the tree from outside, which the IDE takes
 * in by refreshing, and refreshing asks no one.
 */
class ByGeneratedWritingAccessProvider(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> {
        val generated = ByGeneratedFiles.getInstance(project)
        val locked = files.filter { generated.isGenerated(it) && !generated.isEditable(it) }
        if (locked.isEmpty()) return emptyList()
        if (!confirm(project, locked)) return locked
        generated.allowEditing(locked)
        return emptyList()
    }

    override fun getReadOnlyMessage(): String = BasedPythonBundle.message("generated.readOnly")

    companion object {
        /** Asks whether to edit [files] anyway. Replaced in tests, where no dialog can be answered. */
        @Volatile
        internal var confirm: (Project, List<VirtualFile>) -> Boolean = ::ask

        private fun ask(project: Project, files: List<VirtualFile>): Boolean {
            val generated = ByGeneratedFiles.getInstance(project)
            val first = files.first()
            val message = generated.source(first)
                ?.let { BasedPythonBundle.message("generated.edit.message.source", first.name, generated.describe(it)) }
                ?: BasedPythonBundle.message("generated.edit.message", first.name)
            return MessageDialogBuilder.yesNo(BasedPythonBundle.message("generated.edit.title"), message)
                .yesText(BasedPythonBundle.message("generated.edit.anyway"))
                .noText(BasedPythonBundle.message("generated.edit.cancel"))
                .ask(project)
        }

        @TestOnly
        internal fun answering(answer: Boolean, block: () -> Unit) {
            val previous = confirm
            confirm = { _, _ -> answer }
            try {
                block()
            } finally {
                confirm = previous
            }
        }
    }
}

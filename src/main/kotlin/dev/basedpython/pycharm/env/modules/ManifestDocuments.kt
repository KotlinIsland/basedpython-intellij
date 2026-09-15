package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Reading and writing a manifest through the IDE rather than underneath it.
 *
 * The plugin's own manifest edits — un-listing a module, its description, a rename's new name —
 * used to go straight to disk with `java.nio`. An editor with that file open then held a document
 * that disagreed with the disk until something refreshed it, and an unsaved edit in it would be
 * saved over the plugin's. Going through the VFS fires the change events: an open editor shows the
 * new text at once, and the indices and the manifest watcher hear about it.
 *
 * The file is written, not its document. A document is the one place a save can be vetoed without
 * a word — the platform keeps the disk when it thinks the two conflict, and a file uv rewrote a
 * moment earlier is exactly that — so an edit made through it could silently go nowhere. A file
 * whose document holds unsaved edits is refused instead: the gesture saved every manifest before its
 * first command ran, so unsaved text now is something the user typed while the operation was
 * running, and neither overwriting it nor merging with it is this code's call.
 *
 * Blocking, since it refreshes the file from disk first; call off the EDT. The write itself is
 * marshalled onto the EDT in a write action.
 */
internal object ManifestDocuments {

    /** What [file] says on disk, or null when there is no such file. */
    fun read(file: Path): String? {
        val virtualFile = current(file) ?: return null
        if (virtualFile.isDirectory) return null
        return VfsUtilCore.loadText(virtualFile)
    }

    /**
     * [file] as the VFS knows it after re-reading it from disk, or null when it is not there.
     *
     * Re-read every time, because the step before this one was usually `uv`, which rewrote the file
     * behind the IDE's back.
     */
    private fun current(file: Path): VirtualFile? {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return null
        VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
        return virtualFile.takeIf { it.isValid }
    }

    /**
     * Makes [file] say [text] — creating it if needed — or deletes it when [text] is null.
     *
     * Throws what the platform threw, and refuses a file with unsaved edits in an editor; the callers
     * each have their own way of saying it failed.
     */
    fun write(project: Project, file: Path, text: String?) {
        val fs = LocalFileSystem.getInstance()
        val existing = current(file)
        if (text == null && existing == null) return
        val parent = if (existing == null) {
            fs.refreshAndFindFileByNioFile(requireNotNull(file.parent) { "$file has no parent" })
                ?: error("${file.parent} does not exist")
        } else {
            null
        }

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            try {
                existing?.let { virtualFile ->
                    val documents = FileDocumentManager.getInstance()
                    val document = documents.getCachedDocument(virtualFile)
                    check(document == null || !documents.isDocumentUnsaved(document)) {
                        "$file has unsaved changes in the editor"
                    }
                }
                WriteAction.run<Throwable> {
                    when {
                        text == null -> existing?.delete(this)
                        existing == null ->
                            VfsUtil.saveText(requireNotNull(parent).createChildData(this, file.fileName.toString()), text)
                        else -> VfsUtil.saveText(existing, text)
                    }
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        failure?.let { throw it }
    }
}

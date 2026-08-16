package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Keeping the IDE and the disk in agreement across an operation that edits the project's manifests.
 *
 * `uv add` does not edit `pyproject.toml` through the IDE — it is a separate process that reads the
 * file from disk, rewrites it, and knows nothing about the editor showing it. That leaves a window
 * on both sides of the command where the two disagree, and both sides bite:
 *
 * - **Before.** Unsaved edits in the editor are not on disk, so uv reads the old file, rewrites it
 *   from that, and the user's edits are gone the moment the IDE re-reads. Silent data loss.
 * - **After.** The IDE has no idea the file changed. It keeps showing the old content until
 *   something happens to trigger a refresh — regaining window focus, usually — so the dependency
 *   that was just added is missing from a `pyproject.toml` sitting open on screen.
 *
 * Both are closed here, around every operation that can write.
 */
internal object EnvFiles {

    /**
     * Flushes unsaved editor changes to the manifests before a command reads them.
     *
     * Targeted rather than [FileDocumentManager.saveAllDocuments]: an *Add Package* that silently
     * saves every open file in the project is doing something the user did not ask for. These two
     * files are saved because the command is about to read and rewrite them, which is a reason that
     * does not extend to anything else.
     *
     * Safe from any thread — the save itself needs the EDT.
     */
    fun saveBeforeOperation(project: Project, backend: EnvBackend, projectRoot: Path) {
        val documents = FileDocumentManager.getInstance()
        val unsaved = managedVirtualFiles(backend, projectRoot, refresh = false)
            // A cached document only exists for a file something has actually opened; asking for a
            // document any other way would load every manifest just to find nothing to save.
            .mapNotNull { documents.getCachedDocument(it) }
            .filter { documents.isDocumentUnsaved(it) }
        if (unsaved.isEmpty()) return

        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            unsaved.forEach { documents.saveDocument(it) }
        }
    }

    /**
     * Re-reads the manifests after a command has rewritten them.
     *
     * Synchronous, and therefore to be called off the EDT — the convention the rest of this plugin
     * follows for the same job (see [dev.basedpython.pycharm.actions.GenerateApiFileAction]).
     * [LocalFileSystem.refreshAndFindFileByNioFile] finds a file that did not exist before, which is
     * the `uv.lock` a first sync creates; [VfsUtil.markDirtyAndRefresh] is what forces the content
     * of one that did to be read again rather than trusted from cache.
     *
     * The environment directory is deliberately *not* refreshed. It is thousands of files after a
     * sync, nothing in this plugin reads it through the VFS — the environment's own `pyvenv.cfg` is
     * read with `java.nio` precisely so that a scan does not depend on VFS state — and walking it
     * would cost far more than everything else this feature does put together.
     */
    fun refreshAfterOperation(backend: EnvBackend, projectRoot: Path) {
        val files = managedVirtualFiles(backend, projectRoot, refresh = true)
        if (files.isEmpty()) return
        VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
    }

    /**
     * The manifests as [VirtualFile]s.
     *
     * [refresh] distinguishes the two callers: afterwards a file may have just been created and has
     * to be looked for on disk, while beforehand only files the IDE already knows about can have
     * unsaved edits — and a refresh on that side would be a synchronous disk walk to discover files
     * that could not possibly matter yet.
     */
    private fun managedVirtualFiles(
        backend: EnvBackend,
        projectRoot: Path,
        refresh: Boolean,
    ): List<VirtualFile> {
        val fs = LocalFileSystem.getInstance()
        return backend.managedFiles.mapNotNull { name ->
            val path = runCatching { projectRoot.resolve(name) }.getOrNull() ?: return@mapNotNull null
            runCatching {
                if (refresh) fs.refreshAndFindFileByNioFile(path) else fs.findFileByNioFile(path)
            }.getOrNull()
        }
    }
}

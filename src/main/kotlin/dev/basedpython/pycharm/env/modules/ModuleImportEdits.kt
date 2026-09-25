package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.lsp.refactor.ByFileMove
import dev.basedpython.pycharm.lsp.refactor.ByImportRewrites
import dev.basedpython.pycharm.lsp.refactor.ByModuleRenames
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * The `import` statements a module rename leaves pointing at a name that is gone.
 *
 * This is the half of a rename that only the language server can do. Finding every file that
 * imports `alpha.util` means resolving every import in the project against the same search paths
 * the type checker uses, and then telling a *use* of the module apart from a local variable that
 * happens to be spelled like it — neither of which is a question about text, and neither of which
 * this plugin has any business answering. `by` answers both, through the protocol's own
 * `workspace/willRenameFiles`.
 *
 * ### Why it is asked before anything moves
 *
 * That is what the request is for, and it is also the only moment the question is answerable: the
 * old path still holds the file, so the server can resolve which module it is, while the new path is
 * a path to derive a name from. Afterwards, neither is true.
 *
 * ### When the server cannot answer
 *
 * [whyUnsupported] names a reason for a `by` that is not running or does not advertise the
 * capability, and the rename is not offered at all rather than offered and half-done. A rename that
 * moves a directory and leaves every `import` in the project naming the old one is worse than no
 * rename: it is a broken project, made by a button that looked like it worked.
 */
internal object ModuleImportEdits {

    /**
     * Why the project's `by` cannot answer for a rename, or `null` when it can.
     *
     * Read from what the server said at startup rather than by trying it: a server that does not
     * know the request answers with an error, and an error is indistinguishable from a rename that
     * needed no edits. Suspends, cancellably, while a `by` that is starting initializes.
     */
    suspend fun whyUnsupported(project: Project): String? = ByModuleRenames.whyUnsupported(project)

    /**
     * Asks the server what [moves] cost, without applying anything yet.
     *
     * Returns the edits, ready to [ModuleRename.ImportEdits.apply] and to take back, or null when the
     * server could not be asked at all — which the caller treats as a reason not to start, not as "no
     * edits were needed". Asking changes nothing, which is what lets a rename ask before it has done
     * anything it would have to undo.
     *
     * Must be called from a background thread.
     */
    fun prepare(project: Project, moves: List<ModuleRenamePlan.Move>): ModuleRename.ImportEdits? {
        if (moves.isEmpty()) {
            return Prepared(project, emptyMap()).takeIf { runBlockingCancellable { whyUnsupported(project) } == null }
        }
        return when (val answer = ByModuleRenames.ask(project, moves.map { ByFileMove(it.from, it.to) })) {
            is ByImportRewrites.Edits -> Prepared(project, editsByFile(answer.edit))
            // The server answered "nothing to change", which is an ordinary answer.
            ByImportRewrites.NoneNeeded -> Prepared(project, emptyMap())
            is ByImportRewrites.NoServer, ByImportRewrites.NotSupported, ByImportRewrites.Failed -> null
        }
    }

    /** The edits per file, dropping files the IDE cannot find and files with nothing to change. */
    private fun editsByFile(edit: WorkspaceEdit): Map<VirtualFile, List<org.eclipse.lsp4j.TextEdit>> =
        ByWorkspaceEditFiles.uris(edit).mapNotNull { uri ->
            val file = ByWorkspaceEditFiles.fileOf(uri) ?: return@mapNotNull null
            val edits = ByCleanup.editsFor(edit, uri).edits.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            file to edits
        }.toMap()

    /**
     * The server's answer, held until the rename is ready for it.
     *
     * The documents are edited rather than the files on disk, so an importer the user has open
     * changes on screen and takes part in undo. Everything is then saved, because the very next
     * thing that happens is uv reading these files from disk. What each document said before is
     * kept, so a rename that fails after this step can put every importer back.
     */
    private class Prepared(
        private val project: Project,
        private val byFile: Map<VirtualFile, List<org.eclipse.lsp4j.TextEdit>>,
    ) : ModuleRename.ImportEdits {

        private val before = LinkedHashMap<VirtualFile, String>()

        override fun apply(): Boolean {
            if (byFile.isEmpty()) return true
            val documents = FileDocumentManager.getInstance()
            var applied = false
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                CommandProcessor.getInstance().executeCommand(
                    project,
                    {
                        WriteAction.run<RuntimeException> {
                            for ((file, edits) in byFile) {
                                val document = documents.getDocument(file) ?: continue
                                before[file] = document.text
                                ByCleanup.applyEditsTo(document, edits)
                                documents.saveDocument(document)
                            }
                        }
                    },
                    BasedPythonBundle.message("modules.rename.command"),
                    null,
                )
                applied = true
            }
            return applied
        }

        override fun revert() {
            if (before.isEmpty()) return
            val documents = FileDocumentManager.getInstance()
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                CommandProcessor.getInstance().executeCommand(
                    project,
                    {
                        WriteAction.run<RuntimeException> {
                            for ((file, text) in before) {
                                if (!file.isValid) continue
                                val document = documents.getDocument(file) ?: continue
                                document.setText(text)
                                documents.saveDocument(document)
                            }
                        }
                    },
                    BasedPythonBundle.message("modules.rename.command"),
                    null,
                )
            }
            before.clear()
        }
    }
}

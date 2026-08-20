package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.FileRename
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

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
 * [isSupported] is false for a `by` that does not advertise the capability, and the rename is not
 * offered at all rather than offered and half-done. A rename that moves a directory and leaves every
 * `import` in the project naming the old one is worse than no rename: it is a broken project, made
 * by a button that looked like it worked.
 */
internal object ModuleImportEdits {

    /**
     * True when a `by` server is running for this project and can answer for a rename.
     *
     * Read from what the server said at startup rather than by trying it: a server that does not
     * know the request answers with an error, and an error is indistinguishable from a rename that
     * needed no edits.
     */
    fun isSupported(project: Project): Boolean = server(project)?.let(::advertises) == true

    /** The server's own word on whether it handles `workspace/willRenameFiles`. */
    private fun advertises(server: LspServer): Boolean =
        server.initializeResult?.capabilities?.workspace?.fileOperations?.willRename != null

    /**
     * Asks the server what [moves] cost, and applies the answer.
     *
     * Returns the number of files edited, or null when the server could not be asked at all — which
     * the caller treats as a reason to stop, not as "no edits were needed".
     *
     * Must be called from a background thread; the edits themselves are applied on the EDT in a
     * write action, as a single undoable command, so that one Ctrl+Z takes the whole rename back.
     */
    fun applyFor(project: Project, moves: List<ModuleRenamePlan.Move>): Int? {
        val server = server(project) ?: return null
        if (!advertises(server)) return null
        if (moves.isEmpty()) return 0

        val params = RenameFilesParams(
            moves.map { FileRename(uriOf(it.from), uriOf(it.to)) },
        )

        val answer = server.askBy("workspace/willRenameFiles", REQUEST_TIMEOUT_MS) {
            it.workspaceService.willRenameFiles(params)
        }
        val edit = when (answer) {
            is ByAnswer.Answer -> answer.value
            // The server answered "nothing to change", which is an ordinary answer.
            ByAnswer.None -> return 0
            ByAnswer.Failed -> return null
        }

        return apply(project, edit)
    }

    /**
     * Applies [edit] to the files it names, and returns how many were touched.
     *
     * The documents are edited rather than the files on disk, so an importer the user has open
     * changes on screen and takes part in undo. Everything is then saved, because the very next
     * thing that happens is uv reading these files from disk.
     */
    private fun apply(project: Project, edit: WorkspaceEdit): Int {
        val documents = FileDocumentManager.getInstance()
        val byFile: Map<VirtualFile, List<org.eclipse.lsp4j.TextEdit>> =
            uris(edit).mapNotNull { uri ->
                val file = fileOf(uri) ?: return@mapNotNull null
                val edits = ByCleanup.editsFor(edit, uri).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                file to edits
            }.toMap()

        if (byFile.isEmpty()) return 0

        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    WriteAction.run<RuntimeException> {
                        for ((file, edits) in byFile) {
                            val document = documents.getDocument(file) ?: continue
                            ByCleanup.applyEditsTo(document, edits)
                            documents.saveDocument(document)
                        }
                    }
                },
                BasedPythonBundle.message("modules.rename.command"),
                null,
            )
        }
        return byFile.size
    }

    /** Every document URI the edit names, in either of the two shapes a workspace edit can take. */
    private fun uris(edit: WorkspaceEdit): Set<String> =
        edit.changes?.keys.orEmpty() +
            edit.documentChanges.orEmpty()
                .mapNotNull { change -> change.takeIf { it.isLeft }?.left?.textDocument?.uri }

    private fun fileOf(uri: String): VirtualFile? = runCatching {
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(java.net.URI.create(uri)))
    }.getOrNull()

    /**
     * The URI form a path goes out as.
     *
     * Built from the `java.nio` path rather than by string concatenation so that spaces, non-ASCII
     * names and Windows drive letters are encoded the one way both ends already agree on.
     */
    private fun uriOf(path: Path): String = path.toUri().toString()

    /** The `by` server for this project, if one is running. */
    private fun server(project: Project): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(ByLspServerSupportProvider::class.java)
            .firstOrNull()

    /**
     * How long the server gets.
     *
     * Longer than an editor request, because this one reads every file in the project rather than
     * one document — and shorter than forever, because a rename dialog waiting on a server that has
     * stopped answering has to end in something the user can act on.
     */
    private const val REQUEST_TIMEOUT_MS = 30_000
}

package dev.basedpython.pycharm.lsp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import java.util.Collections
import java.util.WeakHashMap

private val LOG = Logger.getInstance(ByServerDocuments::class.java)

/**
 * Tells `by` about the files the IDE's own LSP client will not.
 *
 * `by` answers no document request — hover, semantic tokens, symbols — for a file it has not been
 * sent `textDocument/didOpen` for; the reply is *"Document … is not open in the session"*. The
 * platform normally handles that, but it declines for a whole class of files:
 *
 * ```java
 * // LspClientImpl.isSupportedFile
 * if (!ProjectFileIndex.getInstance(project).isInContent(file)) return false;
 * ```
 *
 * *Content*, not *project*. A stdlib stub is a library file — `isInLibrary` is true, which is what
 * Reader Mode asks — but it is not under a module content root, so the client never syncs it and
 * every request about it comes back empty. That is why goto-definition lands you in a typeshed stub
 * where nothing else works, and no amount of registering that root as a library changes it: library
 * and content are different questions, and the client asks the other one.
 *
 * The protocol has no such restriction, and neither does `by`. So for a file the client has ruled
 * out, this sends the `didOpen` itself, once, and from then on the server answers about it like any
 * other file.
 *
 * ## What it costs
 *
 * The document stays open on the server for the life of the client — deliberately. Closing it after
 * each request would make every feature pay the parse again, and the point is that a stub behaves
 * like an ordinary file. The snapshot is the file as it was read; a stub is read-only and changes
 * only when `by` itself is rebuilt, which replaces the server and so the snapshot with it.
 *
 * Which files were opened is tracked per client, weakly, so a restarted server starts from nothing
 * rather than believing it was told about files the new process has never heard of.
 */
internal object ByServerDocuments {

    private val opened: MutableMap<LspClient, MutableSet<String>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * Makes sure [file] is open on [client], if the platform is not going to do it.
     *
     * Call inside a read action — [ProjectFileIndex] requires one — and before any document request
     * about a file that may sit outside the project's content roots.
     */
    fun ensureOpen(client: LspClient, project: Project, file: VirtualFile) {
        // Under a content root the platform syncs the file, including edits; nothing to do, and
        // opening it a second time is an error on the server.
        if (ProjectFileIndex.getInstance(project).isInContent(file)) return

        val paths = opened.getOrPut(client) { Collections.synchronizedSet(mutableSetOf()) }
        if (!paths.add(file.path)) return

        val text = try {
            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            LOG.warn("could not read ${file.path} to open it with `by`", e)
            paths.remove(file.path)
            return
        }

        val item = TextDocumentItem(
            client.descriptor.getFileUri(file),
            client.descriptor.getLanguageId(file),
            1,
            text,
        )
        client.sendNotification { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
    }
}

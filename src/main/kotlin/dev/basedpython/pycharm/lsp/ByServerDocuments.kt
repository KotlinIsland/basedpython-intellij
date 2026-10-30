package dev.basedpython.pycharm.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.util.WeakHashMap

/**
 * Keeps `by` told about the files the IDE's own LSP client will not.
 *
 * `by` answers no document request — hover, semantic tokens, symbols — for a file it has not been
 * sent `textDocument/didOpen` for; the reply is *"Document … is not open in the session"*. The
 * platform's client opens a file only while it is open in an editor, and never one under an
 * excluded directory (`LspOpenedFilesService`, `LspDocumentSyncManager.getFilesToClose`). A
 * feature asks about others too: a file a rename or an override search reaches, one a debugger
 * evaluates in, an excluded file being edited.
 *
 * Each file has one owner on a server, or `by` is told twice: a second `didOpen` replaces the
 * first, and the second `didClose` is an error (*"document not found for key"*). The platform owns
 * a file it has open — [LspClient.getDocumentVersion] is above zero from its `didOpen` until its
 * `didClose` — or is about to open: one in an editor and not excluded, whose `didOpen` it sends
 * later, from the EDT. A file under a content root is left to the platform as well, as it always has
 * been. Everything else is this one's, and this does the client's job for it, the whole of it rather
 * than the first step:
 *
 *  - **open** once, the first time a feature asks about the file ([ensureOpen]);
 *  - **change** on every edit to the file's document, with the version counting up, so the server
 *    reads the text the offsets in the next request are counted in — a snapshot that stopped at the
 *    open would put every answer about an edited scratch file in the wrong place;
 *  - **close** when the file stops being open in any editor; when an editor opens it, before the
 *    platform's `didOpen` for it goes out, which is the platform taking it over; when it is moved,
 *    renamed or deleted (its URI is about to stop meaning anything); and *before* the project's roots
 *    change, because a file that becomes content or stops being excluded is the platform's.
 *
 * A closed file is opened again the next time something asks about it, so a close is never a
 * feature switched off, only a snapshot let go.
 *
 * Which files were opened is tracked per client, weakly, so a restarted server starts from nothing
 * rather than believing it was told about files the new process has never heard of.
 *
 * A project service, so the listeners that do this go with the project or with the plugin.
 */
@Service(Service.Level.PROJECT)
internal class ByServerDocuments(private val project: Project) : Disposable {

    /** What one server was told about one file: the URI it was told under, and the last version. */
    private class Opened(val uri: String, var version: Int)

    /** Guards [opened] and keeps each file's notifications in the order their versions say. */
    private val lock = Any()

    private val opened = WeakHashMap<LspClient, MutableMap<VirtualFile, Opened>>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = changed(event.document)
            },
            this,
        )
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (!isExcluded(file)) close(file)
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!source.isFileOpen(file)) close(file)
                }
            },
        )
        connection.subscribe(
            ModuleRootListener.TOPIC,
            object : ModuleRootListener {
                override fun beforeRootsChange(event: ModuleRootEvent) = closeAll()
            },
        )
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    for (event in events) {
                        val file = event.file ?: continue
                        // Each of these ends the URI the file was opened under.
                        val endsUri = event is VFileDeleteEvent || event is VFileMoveEvent ||
                            (event is VFilePropertyChangeEvent && event.isRename)
                        if (endsUri) closeUnder(file)
                    }
                }
            },
        )
    }

    /**
     * Makes sure [file] is open on [client], if the platform is not going to do it.
     *
     * Call inside a read action — [ProjectFileIndex] requires one — and before any document request
     * about a file that may sit outside the project's content roots.
     */
    fun ensureOpen(client: LspClient, file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return

        synchronized(lock) {
            val files = opened.getOrPut(client) { HashMap() }
            if (file in files) return
            // Asked under the lock that [close] takes when an editor opens the file, so either this
            // sees the editor, or its open is recorded in time for that close.
            if (isPlatforms(client, file, document)) return
            val uri = client.descriptor.getFileUri(file)
            val item = TextDocumentItem(uri, client.descriptor.getLanguageId(file), 1, document.text)
            files[file] = Opened(uri, 1)
            client.sendNotification { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
        }
    }

    /** Whether the platform's client has [file] open on [client], or is about to (see the class's comment). */
    private fun isPlatforms(client: LspClient, file: VirtualFile, document: Document): Boolean =
        ProjectFileIndex.getInstance(project).isInContent(file) ||
            client.getDocumentVersion(document) > 0 ||
            (FileEditorManager.getInstance(project).isFileOpen(file) && !isExcluded(file))

    /** A file the platform never opens, as `LspDocumentSyncManager.getFilesToClose` says. */
    private fun isExcluded(file: VirtualFile): Boolean =
        file.isInLocalFileSystem && ProjectFileIndex.getInstance(project).isExcluded(file)

    /** Sends [document]'s whole new text to every server this opened its file on. */
    private fun changed(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        synchronized(lock) {
            for ((client, files) in opened) {
                val open = files[file] ?: continue
                if (client.state != LspServerState.Running) continue
                open.version++
                val params = DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(open.uri, open.version),
                    listOf(TextDocumentContentChangeEvent(document.text)),
                )
                client.sendNotification { it.textDocumentService.didChange(params) }
            }
        }
    }

    /** Closes [file] wherever this opened it. */
    private fun close(file: VirtualFile) = closeMatching { it == file }

    /** Closes [file] and, for a directory, everything this opened inside it. */
    private fun closeUnder(file: VirtualFile) =
        closeMatching { it == file || (file.isDirectory && VfsUtilCore.isAncestor(file, it, true)) }

    private fun closeAll() = closeMatching { true }

    private fun closeMatching(predicate: (VirtualFile) -> Boolean) {
        synchronized(lock) {
            for ((client, files) in opened) {
                val closing = files.keys.filter(predicate)
                for (file in closing) {
                    val open = files.remove(file) ?: continue
                    if (client.state != LspServerState.Running) continue
                    val params = DidCloseTextDocumentParams(TextDocumentIdentifier(open.uri))
                    client.sendNotification { it.textDocumentService.didClose(params) }
                }
            }
        }
    }

    override fun dispose() {
        synchronized(lock) { opened.clear() }
    }

    companion object {
        /** [ensureOpen] on [project]'s instance. */
        fun ensureOpen(client: LspClient, project: Project, file: VirtualFile) {
            project.service<ByServerDocuments>().ensureOpen(client, file)
        }
    }
}

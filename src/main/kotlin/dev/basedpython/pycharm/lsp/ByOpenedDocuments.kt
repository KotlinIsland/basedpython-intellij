package dev.basedpython.pycharm.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.messages.Topic
import java.util.Collections

/**
 * Which documents each `by` server has been sent `textDocument/didOpen` for, and when one is.
 *
 * `by` refuses a document request — `by/syntaxOutline`, `by/injections`, an inlay hint — for a
 * document it has not been opened on: *"Document … is not open in the session"*. The platform sends
 * that `didOpen` itself, but late, and on its own schedule: an editor opening a file queues it
 * behind a non-blocking read action and a write action on the EDT, a first edit to a file no editor
 * shows queues it the same way, and a server that has just started has every open file queued at
 * once. Everything that reacts to the same events — a highlighting pass over the new editor, the
 * injector, a listener on the edit — gets there first, and is refused. Measured in PyCharm
 * 263.5153.49 over one run that opened six files, edited two and restarted the server: 12 refusals,
 * `by/syntaxOutline` 3, `by/injections` 4 and the docstring pass's `semanticTokens/full` 5.
 *
 * ## The signal
 *
 * The platform has a per-file "opened" callback, `LspClientManagerListener.fileOpened`, but it is
 * `@ApiStatus.Internal` (see docs/internal-api.md), and the public `LspServerListener` has none. What
 * the public API *does* give is [LspClientDescriptor.getLanguageId], which the platform documents as
 * the `languageId` of the `TextDocumentItem` in `textDocument/didOpen`, and calls to build exactly
 * that: in its document adapter's `sendDidOpen`, right before handing the notification to the
 * client's request queue. Its only other callers build the `didOpen` of a decompiled library file,
 * which is no `.by` document, and match dynamically registered capabilities by language, which `by`
 * registers none of. [ByServerDocuments] builds its own `didOpen` through it too. So
 * [ByLspServerDescriptor] reports each call here, and that call *is* the event: the document is
 * being opened on this server, now.
 *
 * ## Why a request asked after it is sent after the `didOpen`
 *
 * The platform opens a document inside a write action and queues the notification before that
 * action ends. A request is asked inside a read action, which cannot start while a write action is
 * running — so an asker that sees the document open here saw it after the notification was queued,
 * and the client sends everything from one queue, one at a time, in order. Asking needs no delay.
 *
 * ## Closing
 *
 * There is no public signal for `didClose` at all, so this follows the platform's own rules for
 * sending one: a file whose last editor closes while its document is saved, a file no editor shows
 * as its document is saved, and a file deleted, moved or renamed (whose URI is gone; the platform
 * opens a moved file again, which reports it here again). A server that stops forgets everything.
 * Getting one of these wrong costs little and is visible: a file kept here after the platform
 * closed it draws one refused request, and a file dropped while still open is asked about again
 * the next time the platform opens it.
 */
@Service(Service.Level.PROJECT)
internal class ByOpenedDocuments(private val project: Project) : Disposable {

    /** Notified when a `by` server has been sent `didOpen` for a file. */
    fun interface Listener {
        /**
         * [file] is being opened on a `by` server. Called inside the platform's write action, before
         * its notification is on the wire: react by *scheduling* a request, which will then go after
         * it, never by asking here.
         */
        fun opened(file: VirtualFile)

        companion object {
            @Topic.ProjectLevel
            val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)
        }
    }

    /**
     * Per server, weakly: a restarted server is a new descriptor, and the one that stopped takes its
     * files with it. The files are weak too — a file nobody holds any more is not one to ask about.
     */
    private val opened: MutableMap<LspClientDescriptor, MutableSet<VirtualFile>> =
        ContainerUtil.createConcurrentWeakMap()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                // The platform's `LspFileEditorManagerListener.fileClosed`, to the letter: the last
                // editor gone, and nothing unsaved to keep the server's copy for.
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (source.isFileOpen(file)) return
                    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
                    if (!FileDocumentManager.getInstance().isDocumentUnsaved(document)) forget { it == file }
                }
            },
        )
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun before(events: List<VFileEvent>) {
                    for (event in events) {
                        val file = event.file ?: continue
                        val endsUri = event is VFileDeleteEvent || event is VFileMoveEvent ||
                            (event is VFilePropertyChangeEvent && event.isRename)
                        if (endsUri) forget { it == file || (file.isDirectory && VfsUtilCore.isAncestor(file, it, true)) }
                    }
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            object : FileDocumentManagerListener {
                // The platform's `LspFileDocumentManagerListener.beforeDocumentSaving`: a file no
                // editor shows is closed once it is saved.
                override fun beforeDocumentSaving(document: Document) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: return
                    if (!FileEditorManager.getInstance(project).isFileOpen(file)) forget { it == file }
                }
            },
        )
        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                    if (serverName == BY_SERVER) opened.clear()
                }
            },
        )
    }

    /** [descriptor]'s server is being sent `didOpen` for [file]. */
    fun opening(descriptor: LspClientDescriptor, file: VirtualFile) {
        val files = opened.computeIfAbsent(descriptor) { Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap()) }
        if (files.add(file) && !project.isDisposed) project.messageBus.syncPublisher(Listener.TOPIC).opened(file)
    }

    /** [descriptor]'s server is starting from nothing. */
    fun starting(descriptor: LspClientDescriptor) {
        opened.remove(descriptor)
    }

    /**
     * Whether [client] has been sent `didOpen` for [file]. Ask inside the read action the request
     * is made in, which is what orders the request after the notification — see the class docs.
     */
    fun has(client: LspClient, file: VirtualFile): Boolean = opened[client.descriptor]?.contains(file) == true

    private fun forget(predicate: (VirtualFile) -> Boolean) {
        for (files in opened.values) files.removeIf(predicate)
    }

    override fun dispose() {
        opened.clear()
    }

    companion object {
        /** The name [ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        fun getInstance(project: Project): ByOpenedDocuments = project.service()
    }
}

/**
 * Whether this server holds [file], so that a request about it will be answered rather than refused.
 *
 * Only a `by` server this plugin started is tracked; any other client — a test's fake — is taken to
 * hold whatever it is asked about. Call after [ByServerDocuments.ensureOpen], which opens a file the
 * platform will not, and inside the read action the request is made in.
 */
internal fun LspClient.hasDocument(file: VirtualFile): Boolean =
    descriptor !is ByLspServerDescriptor || ByOpenedDocuments.getInstance(project).has(this, file)

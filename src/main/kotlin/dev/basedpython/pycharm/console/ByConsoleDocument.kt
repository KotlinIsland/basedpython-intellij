package dev.basedpython.pycharm.console

import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.lang.ref.WeakReference

/**
 * The document `by` is asked about for one console: the session's source followed by the input being
 * typed, under an `untitled:` URI of its own.
 *
 * `by repl` checks each input as the continuation of the module the inputs that ran make, and keeps
 * that module's source in its session file for an editor; completion in the console is `by`'s ordinary
 * completion asked of this module with the input at its end, so what is offered is what the session
 * has defined (`docs/basedpython/features/repl.md`, *completion*). The console's own input file is a
 * light file `by` never hears of, and its text alone would know nothing of the session.
 *
 * The document is opened the first time a server is asked about it and changed whenever the text to
 * ask about differs from what the server has; a server it was not opened on — a restarted `by` — is
 * told about it from the start. Closed with the console.
 */
internal class ByConsoleDocument(val uri: String) {

    private val lock = Any()

    /** The server the document is open on, held weakly so a stopped one is not kept alive by a console. */
    private var client: WeakReference<LspClient>? = null
    private var version = 0
    private var text: String? = null

    /** Makes [client] hold [text] as this document; call before each request about it. */
    fun sync(client: LspClient, text: String) {
        synchronized(lock) {
            if (this.client?.get() !== client) {
                version = 1
                val item = TextDocumentItem(uri, LANGUAGE_ID, version, text)
                client.sendNotification { it.textDocumentService.didOpen(DidOpenTextDocumentParams(item)) }
            } else {
                if (this.text == text) return
                version++
                val params = DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, version),
                    listOf(TextDocumentContentChangeEvent(text)),
                )
                client.sendNotification { it.textDocumentService.didChange(params) }
            }
            this.client = WeakReference(client)
            this.text = text
        }
    }

    /** Lets the server forget the document. */
    fun close() {
        synchronized(lock) {
            val open = client?.get()
            client = null
            text = null
            if (open == null || open.state != LspServerState.Running) return
            open.sendNotification { it.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri))) }
        }
    }

    private companion object {
        /** One of the ids `by` reads as basedpython. */
        const val LANGUAGE_ID = "basedpython"
    }
}

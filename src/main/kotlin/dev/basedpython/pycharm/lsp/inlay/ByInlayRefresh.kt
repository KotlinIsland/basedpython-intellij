package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.TextDocumentContentRefreshParams
import java.util.concurrent.CompletableFuture

/**
 * Draws basedpython's inlay hints again when `by` says its answer has changed — the
 * `workspace/inlayHint/refresh` request.
 *
 * `by` sends it when what it answers from changed without an edit in the editor: a file changed on
 * disk under its watcher (a `pip install` into the venv, a branch switch, a stub written by another
 * tool), its settings re-read, its environment synchronised. The platform's handler clears only the
 * platform's own LSP inlay cache and re-asks for the files it draws hints for — and it draws none
 * for `by`, since [ByInlayHintsProvider] draws them instead. So the request arrived and changed
 * nothing on screen: the inlay pass sits out a daemon restart unless the PSI changed since it last
 * ran, and here the PSI had not.
 *
 * A burst of refreshes — `by` sends one per batch of watched changes — is one redraw: the channel
 * is conflated, so a request that arrives while one is queued replaces it.
 */
@Service(Service.Level.PROJECT)
internal class ByInlayRefresh(private val project: Project, scope: CoroutineScope) {

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) redraw()
        }
    }

    /** Asks for every open basedpython editor's hints to be asked for again. Any thread. */
    fun request() {
        requests.trySend(Unit)
    }

    /**
     * Forgets for each editor of the project that its hints are up to date, then restarts the
     * daemon, in one EDT turn so that no pass can run in between and stamp the editor again.
     */
    private suspend fun redraw() = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        if (project.isDisposed) return@withContext
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            InlayHintsPassFactoryInternal.clearModificationStamp(editor)
        }
        DaemonCodeAnalyzer.getInstance(project).restart(REASON)
    }

    /**
     * What hears `by`'s requests to the client, answering `workspace/inlayHint/refresh` as the
     * platform does and then with [request]. Answered at once either way: the request carries
     * nothing back, and `by` does not wait on it.
     *
     * Through the handler rather than by overriding the client: `Lsp4jClient`'s methods are final,
     * and each only hands its request to the platform's handler, so wrapping that handler is the
     * one place left to hear it.
     */
    class Handler(
        private val project: Project,
        private val platform: LspServerNotificationsHandler,
    ) : LspServerNotificationsHandler by platform {
        override fun refreshInlayHints(): CompletableFuture<Void> {
            if (!project.isDisposed) getInstance(project).request()
            return platform.refreshInlayHints()
        }

        override fun refreshTextDocumentContent(params: TextDocumentContentRefreshParams): CompletableFuture<Void> =
            platform.refreshTextDocumentContent(params)
    }

    /**
     * The lsp4j client the platform builds for `by`, around [Handler]. A subclass rather than an
     * `Lsp4jClient` constructed outright: its constructor is `@ApiStatus.OverrideOnly`, and the
     * plugin verifier reports invoking it from anywhere but a subclass.
     */
    class Client(project: Project, platform: LspServerNotificationsHandler) : Lsp4jClient(Handler(project, platform))

    companion object {
        const val REASON = "by asked for its inlay hints again (workspace/inlayHint/refresh)"

        fun getInstance(project: Project): ByInlayRefresh = project.service()
    }
}

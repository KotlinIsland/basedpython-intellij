package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterpartsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isByServerFile
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.lsp.vision.SharedAsks
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly

/**
 * What `by/documentCounterparts` says about a document now: whether it is a stub with a runtime
 * module or a runtime module with a stub, and where each of its declarations is on the other side.
 * What the gutter's stub icons ([ByCounterpartMarkers]) and Go to Related Symbol
 * ([ByCounterpartsRelatedProvider]) are drawn from.
 *
 * Which module is a stub of which, and which declaration of one is which of the other, is `by`'s:
 * it is the module resolution the checker does, `sys.version_info` branches and overloads included,
 * for any of `.by`, `.byi`, `.py` and `.pyi`. Nothing here matches names.
 *
 * ## Asked once per pass
 *
 * An answer is about two files, so it is kept against what it was asked about — the text of this
 * document and the project's PSI modification count, which an edit of the other side moves — and
 * handed to anyone who asks about the same: the gutter pass, the inside and outside halves of it, and
 * Go to Related Symbol. Two asking at once wait on the one request ([SharedAsks]). A server that starts
 * or stops forgets them all.
 *
 * ## Which thread asks
 *
 * A background one inside a read action, waiting on `by` under the caller's indicator, so that a
 * keystroke cancels the wait rather than queueing behind it.
 *
 * ## A `by` without the request
 *
 * Answers `MethodNotFound`, and gets no icons and no word about it: they are ambient. That server is
 * not asked again; a restarted server may be a newer `by`, and is.
 */
@Service(Service.Level.PROJECT)
internal class ByCounterparts(private val project: Project, scope: CoroutineScope) : Disposable {

    /** What asking came to. */
    sealed interface Asked {
        /** `by` answered; [counterparts] is `null` for a document that is neither a stub nor stubbed. */
        data class Answered(val counterparts: ByDocumentCounterparts?) : Asked

        /** A `by` from before `by/documentCounterparts`. */
        data object Unknown : Asked

        /** Not answered: refused, timed out, or nobody running. Not remembered. */
        data object Failed : Asked
    }

    /** Everything an answer is about: an ask about the same is answered by it. */
    private data class About(val textHash: String, val projectModificationCount: Long)

    private val asks = SharedAsks<VirtualFile, Asked>(scope) { it is Asked.Answered }

    /** Answers a test put in, by file, kept for as long as the document's text is what it was. */
    private val remembered = HashMap<VirtualFile, Pair<String, ByDocumentCounterparts?>>()

    /** Whether the running `by` has said it does not know the request. */
    @Volatile
    private var unknownRequest = false

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /** A restarted server may be a different `by`, which may know the request. */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    unknownRequest = false
                    asks.clear()
                }

                /** What a stopped server said is no longer anyone's answer. */
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                    if (serverName == BY_SERVER) asks.clear()
                }
            },
        )
    }

    /**
     * The counterparts of [file] as `by` says for the document as it is now, or `null` when there is
     * nothing to draw from: a document that is neither a stub nor stubbed, no server, one that does
     * not know the request, or one that did not answer.
     *
     * Background only, inside a read action, so the text cannot move under the answer; cancelled with
     * the caller.
     */
    fun forFile(file: PsiFile): ByDocumentCounterparts? {
        check(!ApplicationManager.getApplication().isDispatchThread) { "asks by; not on the EDT" }
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        if (!virtualFile.isByServerFile()) return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        val textHash = ByTextHash.of(document.immutableCharSequence)
        synchronized(remembered) {
            remembered[virtualFile]?.let { (hash, answer) -> if (hash == textHash) return answer?.takeIf { it.isUsable } }
        }
        if (unknownRequest) return null

        val server = runningByServer(project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val about = About(textHash, PsiModificationTracker.getInstance(project).modificationCount)
        return when (val asked = runBlockingCancellable { asks.answer(virtualFile, about) { ask(server, virtualFile, textHash) } }) {
            is Asked.Answered -> asked.counterparts?.takeIf { it.isUsable }
            Asked.Unknown -> {
                unknownRequest = true
                null
            }
            Asked.Failed -> null
        }
    }

    /** Puts in an answer for [document] as it is now, as though `by` had just given it. */
    @TestOnly
    fun remember(document: Document, file: VirtualFile, counterparts: ByDocumentCounterparts?) {
        synchronized(remembered) { remembered[file] = ByTextHash.of(document.immutableCharSequence) to counterparts }
    }

    override fun dispose() {
        asks.clear()
        synchronized(remembered) { remembered.clear() }
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /**
         * How long a caller waits: as long as `by` holds a request for text it has not been sent yet,
         * which is how long the platform's `didOpen` may take to go out. The wait is the caller's, off
         * the EDT and cancelled by the next keystroke.
         */
        private const val TIMEOUT_MS = 10_000L

        fun getInstance(project: Project): ByCounterparts = project.service()

        /** An answer the contract allows: one naming the other side. A `role` this plugin does not know is still drawn. */
        private val ByDocumentCounterparts.isUsable: Boolean get() = !counterparts.isNullOrEmpty()

        /**
         * Asks [server] for the counterparts of [file], whose text hashes to [textHash].
         *
         * `null` from `by` — neither a stub nor stubbed, or language services off — is an answer.
         * `MethodNotFound` is [Asked.Unknown]; anything else that is not an answer is [Asked.Failed].
         */
        suspend fun ask(server: LspClient, file: VirtualFile, textHash: String): Asked {
            val params = ByDocumentCounterpartsParams(TextDocumentIdentifier(server.getDocumentIdentifier(file).uri), textHash)
            var unknown = false
            val answer = awaitingAgain<ByDocumentCounterparts>("by/documentCounterparts", TIMEOUT_MS) { sent ->
                try {
                    server.sendRequest { (it as ByServerExtensions).documentCounterparts(params).also(sent) }
                } catch (e: Exception) {
                    if (isMethodNotFound(e)) unknown = true
                    throw e
                }
            }
            return when (answer) {
                is ByAnswer.Answer -> Asked.Answered(answer.value)
                ByAnswer.None -> Asked.Answered(null)
                ByAnswer.Failed -> if (unknown) Asked.Unknown else Asked.Failed
            }
        }
    }
}

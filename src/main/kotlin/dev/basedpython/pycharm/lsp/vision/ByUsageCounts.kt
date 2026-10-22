package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByDeclarationUsages
import dev.basedpython.pycharm.lsp.ext.ByDocumentUsageCountsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.lsp.runningByServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.Collections

/**
 * What `by/documentUsageCounts` says about a `.by` document now: its declarations with their counts
 * — what the code vision above each is drawn from ([ByUsagesCodeVisionProvider],
 * [ByInheritorsCodeVisionProvider]).
 *
 * ## Asked once per pass, for both counts
 *
 * A count depends on every file of the workspace, not only this one: a call added in another file is
 * one more usage. The platform runs the passes that draw these again whenever any PSI in the project
 * changes, and each asks afresh, for the text the document has now ([ByTextHash]).
 *
 * Each count is a search of the whole workspace, so one pass asks once: for every count whose
 * provider is switched on, in one request, and the two providers draw from the one answer. The
 * answer is kept against what it was asked about — the document's text, the project's PSI
 * modification count and the counts — and handed to any provider that asks about the same; anything
 * that changes one of those is a new ask, and a server that starts forgets them all. Two providers
 * asking at once wait on the one request.
 *
 * ## Which thread asks
 *
 * The highlighting pass's own: a background thread in a read action, waiting on `by` under the
 * pass's indicator. The request itself is this service's, so that a provider that stops waiting does
 * not take the answer from the other; when every provider waiting on it has stopped — the pass was
 * cancelled, by a keystroke anywhere in the project — the request is cancelled, and a `by` that
 * stops a request it is told is cancelled stops the search. A keystroke also reaches `by` as a
 * change, which stops the counting it was doing for the old text.
 *
 * ## A `by` without the request
 *
 * Answers `MethodNotFound`, and gets no code vision and no word about it: the counts are ambient, not
 * something the user asked for. That server is not asked again; a restarted server may be a newer
 * `by`, and is, and so are the files a pass was given nothing for, since the platform does not run
 * the pass again on its own until something changes.
 */
@Service(Service.Level.PROJECT)
internal class ByUsageCounts(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** A count `by/documentUsageCounts` works out, under its wire name. */
    enum class Count(val wire: String) {
        /** How many places use each declaration. */
        REFERENCES("references"),

        /** How many classes subclass each class, and how many members override each method. */
        IMPLEMENTATIONS("implementations"),
    }

    /** What asking came to. */
    sealed interface Asked {
        data class Answered(val declarations: List<ByDeclarationUsages>) : Asked

        /** A `by` from before `by/documentUsageCounts`. */
        data object Unknown : Asked

        /** Not answered: refused, timed out, or nobody running. */
        data object Failed : Asked
    }

    /** Everything an answer is about: an ask about the same is answered by it. */
    private data class About(
        val textHash: String,
        val projectModificationCount: Long,
        val counts: Set<Count>,
    )

    /** The last request for each file, finished or not. */
    private val asks = SharedAsks<VirtualFile, Asked>(scope) { it is Asked.Answered }

    /**
     * Files a pass was given nothing for because of the server: none was running, or it did not
     * know the request, or did not answer. Weakly held: a file closed and collected leaves.
     */
    private val servedNothing: MutableSet<VirtualFile> =
        Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap())

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
                    val files = servedNothing.toList()
                    servedNothing.clear()
                    for (file in files) recount(file)
                }
            },
        )
    }

    /**
     * The declarations of [file] with [count] counted — and the other count, when its provider is
     * on — as `by` says for the document as it is now, or `null` when there is nothing to draw from:
     * no server, one that does not know the request, or one that did not answer.
     *
     * Background only, inside a read action — a highlighting pass — so the text cannot move under
     * the answer; cancelled with the pass.
     */
    fun forFile(file: PsiFile, count: Count): List<ByDeclarationUsages>? {
        check(!ApplicationManager.getApplication().isDispatchThread) { "asks by; not on the EDT" }
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        if (unknownRequest) return servedNothing(virtualFile)

        val server = runningByServer(project, virtualFile) ?: return servedNothing(virtualFile)
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val params = params(server, virtualFile, document, countsDrawn(count))
        val about = About(
            params.textHash,
            PsiModificationTracker.getInstance(project).modificationCount,
            countsDrawn(count),
        )
        return when (val asked = runBlockingCancellable { asks.answer(virtualFile, about) { ask(server, params) } }) {
            is Asked.Answered -> asked.declarations
            Asked.Unknown -> {
                unknownRequest = true
                servedNothing(virtualFile)
            }
            Asked.Failed -> servedNothing(virtualFile)
        }
    }

    private fun servedNothing(file: VirtualFile): List<ByDeclarationUsages>? {
        servedNothing.add(file)
        return null
    }

    /**
     * Runs the passes over [file]'s editors again. The platform runs code vision's pass only when the
     * PSI has changed since its last run, so that is forgotten for each editor first.
     */
    private fun recount(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!file.isValid) return@invokeLater
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
                val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@invokeLater
                for (editor in EditorFactory.getInstance().getEditors(document, project)) {
                    ModificationStampUtil.clearModificationStamp(editor)
                }
                DaemonCodeAnalyzer.getInstance(project).restart(psi, "a by server started that may count its declarations")
            },
            project.disposed,
        )
    }

    override fun dispose() {
        servedNothing.clear()
        asks.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /**
         * How long a pass waits: as long as `by` holds a request for text it has not been sent yet,
         * which is how long the platform's `didOpen` may take to go out. The counting itself is a
         * search of the workspace; the wait is the pass's, off the EDT, and cancelled by the next
         * keystroke — and when it runs out, the request is cancelled, which stops the counting in a
         * `by` that stops a request it is told is cancelled.
         */
        private const val TIMEOUT_MS = 10_000L

        fun getInstance(project: Project): ByUsageCounts = project.service()

        /** [count], and the other count when its provider is switched on: what one pass draws. */
        private fun countsDrawn(count: Count): Set<Count> {
            val settings = CodeVisionSettings.getInstance()
            return buildSet {
                add(count)
                if (settings.isProviderEnabled(PlatformCodeVisionIds.USAGES.key)) add(Count.REFERENCES)
                if (settings.isProviderEnabled(PlatformCodeVisionIds.INHERITORS.key)) add(Count.IMPLEMENTATIONS)
            }
        }

        /** The request for [counts] of [file], whose text is [document]'s now. */
        fun params(server: LspClient, file: VirtualFile, document: Document, counts: Set<Count>): ByDocumentUsageCountsParams =
            ByDocumentUsageCountsParams(
                TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
                ByTextHash.of(document.immutableCharSequence),
                Count.entries.filter { it in counts }.map { it.wire },
            )

        /**
         * Asks [server] the request [params].
         *
         * `null` from `by` — language services are off — is an answer with nothing in it.
         * `MethodNotFound` is [Asked.Unknown]; anything else that is not an answer is [Asked.Failed].
         */
        suspend fun ask(server: LspClient, params: ByDocumentUsageCountsParams): Asked {
            var unknown = false
            val answer = awaitingAgain<List<ByDeclarationUsages>>("by/documentUsageCounts", TIMEOUT_MS) { sent ->
                try {
                    server.sendRequest { (it as ByServerExtensions).documentUsageCounts(params).also(sent) }
                } catch (e: Exception) {
                    if (isMethodNotFound(e)) unknown = true
                    throw e
                }
            }
            return when (answer) {
                is ByAnswer.Answer -> Asked.Answered(answer.value)
                ByAnswer.None -> Asked.Answered(emptyList())
                ByAnswer.Failed -> if (unknown) Asked.Unknown else Asked.Failed
            }
        }
    }
}

/**
 * One request at a time per [K], shared by everyone who asks about the same thing while it runs or
 * after it has answered.
 *
 * Each [K] keeps its last request, finished or not, with what it was about. An ask about the same
 * waits on that request; an ask about anything else replaces it with a new one. A finished request
 * is handed out again only if [keep] says its answer may be: one that failed is asked again.
 *
 * The request runs in [scope], not in whoever asked, and an asker only waits on it, so an asker that
 * stops waiting does not take the answer from the others. When every asker has stopped waiting
 * before it finished, it is cancelled. Keys are held weakly.
 */
internal class SharedAsks<K : Any, A : Any>(private val scope: CoroutineScope, private val keep: (A) -> Boolean) {

    private class Asking<A : Any>(val about: Any) {
        lateinit var answer: Deferred<A>

        /** What [answer] came to, once it has. */
        @Volatile
        var answered: A? = null

        /** How many askers are waiting on [answer]. */
        var waiting = 0
    }

    /** Read and written under its own lock. */
    private val asking: MutableMap<K, Asking<A>> = ContainerUtil.createConcurrentWeakMap()

    /** What [ask] answers about [about] for [key], or what the request already made about it does. */
    suspend fun answer(key: K, about: Any, ask: suspend () -> A): A {
        val joined = synchronized(asking) {
            val current = asking[key]
            val joined = if (current != null && current.about == about && !current.answer.isCancelled &&
                current.answered.let { it == null || keep(it) }
            ) {
                current
            } else {
                Asking<A>(about).also { new ->
                    new.answer = scope.async { ask().also { new.answered = it } }
                    asking[key] = new
                }
            }
            joined.waiting++
            joined
        }
        try {
            return joined.answer.await()
        } finally {
            synchronized(asking) {
                joined.waiting--
                if (joined.waiting == 0 && !joined.answer.isCompleted) {
                    joined.answer.cancel()
                    if (asking[key] === joined) asking.remove(key)
                }
            }
        }
    }

    fun clear() {
        synchronized(asking) { asking.clear() }
    }
}

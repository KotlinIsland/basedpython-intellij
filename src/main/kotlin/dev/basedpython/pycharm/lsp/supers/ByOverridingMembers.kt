package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
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
import com.intellij.psi.PsiManager
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByDocumentSuperMembersParams
import dev.basedpython.pycharm.lsp.ext.ByOverridingMember
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.Collections

/**
 * What `by` last said about which class members of each `.by` document override something, per
 * revision — what the gutter's *overrides* and *implements* icons are drawn from
 * ([ByOverridingMarkers]).
 *
 * One `by/documentSuperMembers` per revision of a document, whatever it holds: the icons are drawn
 * on every highlighting pass, and a request per member per pass is what the platform's own
 * *overridden* icons cost (see `ByInheritanceMarkers` in `LspServers.kt`).
 *
 * ## Revisions
 *
 * An answer is kept against the `Document.modificationStamp` it was asked at, and handed out only
 * for that stamp: its ranges mean nothing against other text. The request names that text
 * ([ByTextHash]), so `by` answers about exactly it — from its buffer, from the file on disk before
 * the platform's `didOpen`, or once the `didOpen` or `didChange` that brings it arrives — and the
 * pass asking holds a read action, so the document cannot move on while the answer is on its way.
 *
 * ## Which thread asks
 *
 * The highlighting pass's own: a background thread, waiting on `by` under the pass's indicator, so
 * that a keystroke cancels the wait rather than queueing behind it. Nothing here asks on the EDT,
 * which is also where the platform sends the `didOpen` a request may be waiting for.
 *
 * ## A `by` without the request
 *
 * Answers `MethodNotFound`, and gets no icons and no word about it: the icons are an ambient
 * feature, not something the user asked for. That server is not asked again; a restarted server may
 * be a newer `by`, and is.
 */
@Service(Service.Level.PROJECT)
internal class ByOverridingMembers(private val project: Project) : Disposable {

    /** What `by` said about a document at [stamp]: its members that override something. */
    class Answer(val stamp: Long, val members: List<ByOverridingMember>)

    /** What asking came to. */
    sealed interface Asked {
        data class Answered(val members: List<ByOverridingMember>) : Asked

        /** A `by` from before `by/documentSuperMembers`. */
        data object Unknown : Asked

        /** Not answered: refused, timed out, or nobody running. Not remembered. */
        data object Failed : Asked
    }

    /** Weakly keyed by file: a file closed and collected takes its answer with it. */
    private val answers: MutableMap<VirtualFile, Answer> = ContainerUtil.createConcurrentWeakMap()

    /**
     * Files a pass was given nothing for because of the server: none was running, or it did not
     * know the request, or did not answer. The pass does not run again on its own until the file is
     * edited, so when a server next starts — maybe a newer `by` — their highlighting is run again.
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
                    answers.clear()
                    unknownRequest = false
                    val files = servedNothing.toList()
                    servedNothing.clear()
                    for (file in files) rehighlight(file)
                }
            },
        )
    }

    /**
     * The members of [file] that override something, as `by` says for the document as it is now, or
     * `null` when there is nothing to draw from: no server, one that does not know the request, or
     * one that did not answer.
     *
     * Asks and waits when there is no answer for this revision yet. Background only, inside a read
     * action — a highlighting pass — so the text cannot move under the answer; cancelled with the
     * pass.
     */
    fun forFile(file: PsiFile): Answer? {
        check(!ApplicationManager.getApplication().isDispatchThread) { "asks by; not on the EDT" }
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        answers[virtualFile]?.takeIf { it.stamp == document.modificationStamp }?.let { return it }
        if (unknownRequest) return servedNothing(virtualFile)

        val server = runningByServer(project, virtualFile) ?: return servedNothing(virtualFile)
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        return when (val asked = runBlockingCancellable { ask(server, virtualFile, document) }) {
            is Asked.Answered -> Answer(document.modificationStamp, asked.members).also { answers[virtualFile] = it }
            Asked.Unknown -> {
                unknownRequest = true
                servedNothing(virtualFile)
            }
            Asked.Failed -> servedNothing(virtualFile)
        }
    }

    private fun servedNothing(file: VirtualFile): Answer? {
        servedNothing.add(file)
        return null
    }

    /** Puts in an answer for [document] as it is now, as though `by` had just given it. */
    @TestOnly
    fun remember(document: Document, file: VirtualFile, members: List<ByOverridingMember>) {
        answers[file] = Answer(document.modificationStamp, members)
    }

    private fun rehighlight(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!file.isValid) return@invokeLater
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
                DaemonCodeAnalyzer.getInstance(project).restart(psi, "a by server started that may say which members override")
            },
            project.disposed,
        )
    }

    override fun dispose() {
        answers.clear()
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /**
         * How long a pass waits: as long as `by` holds a request for text it has not been sent yet,
         * which is how long the platform's `didOpen` may take to go out. The answer itself takes
         * about 2ms once the edit is checked, on a file of 182 methods, and the wait is the pass's,
         * off the EDT and cancelled by the next keystroke.
         */
        private const val TIMEOUT_MS = 10_000L

        fun getInstance(project: Project): ByOverridingMembers = project.service()

        /**
         * Asks [server] for the overriding members of [file], whose text is [document]'s now.
         *
         * `null` from `by` — language services are off — is an answer with nothing in it, and is
         * remembered like one. `MethodNotFound` is [Asked.Unknown]; anything else that is not an
         * answer is [Asked.Failed].
         */
        suspend fun ask(server: LspClient, file: VirtualFile, document: Document): Asked {
            val params = ByDocumentSuperMembersParams(
                TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
                ByTextHash.of(document.immutableCharSequence),
            )
            var unknown = false
            val answer = awaitingAgain<List<ByOverridingMember>>("by/documentSuperMembers", TIMEOUT_MS) { sent ->
                try {
                    server.sendRequest { (it as ByServerExtensions).documentSuperMembers(params).also(sent) }
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

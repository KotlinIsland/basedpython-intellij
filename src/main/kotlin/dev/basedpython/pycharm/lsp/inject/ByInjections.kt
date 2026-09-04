package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.FileContentUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByInjectionsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly

/**
 * What `by` last said about where a document's fragments of other languages are.
 *
 * [BasedPythonLanguageInjector] is asked about one string literal at a time, dozens of times per
 * pass; `by/injections` answers about a whole document at once. So the answer is held here, against
 * the document revision it was taken at, and one request serves every literal in the file.
 *
 * ## Which thread asks
 *
 * A request to the server is background-only — `LspServer.sendRequestSync` asserts on the EDT — and
 * the pass that drives injection (`InjectedGeneralHighlightingPass`) is a background read action,
 * so the ordinary path asks and waits, exactly as the inlay hints collector does. On the EDT there
 * is no asking: the answer already taken for this revision is served, and if there is none the
 * literal is left alone and a request is started behind it.
 *
 * That leaves one case to repair. The platform caches an injection result per element against
 * `PsiModificationTracker.MODIFICATION_COUNT`, so an EDT caller that asks first and is told
 * *nothing* pins that answer until the file next changes — the file would sit there un-injected
 * with the answer already in hand. [reinject] is what unpins it, and it runs only when a request
 * started from the EDT comes back with something after the empty answer went out.
 */
@Service(Service.Level.PROJECT)
internal class ByInjections(private val project: Project) : Disposable {

    /** What the server said about one document, and the revision it was said about. */
    private class Answer(val stamp: Long, val injections: List<ByInjection>)

    /**
     * Weak keys: a file that is closed and collected takes its answer with it. A strong map here
     * would hold every `.by` file ever looked at for the life of the project.
     */
    private val answers: MutableMap<VirtualFile, Answer> = ContainerUtil.createConcurrentWeakMap()

    /** Files a background request is already out for, so a stalled server is asked once, not once per pass. */
    private val asking: MutableSet<VirtualFile> = ContainerUtil.newConcurrentSet()

    /** Files that were served nothing while the real answer was still being fetched. */
    private val servedNothing: MutableSet<VirtualFile> = ContainerUtil.newConcurrentSet()

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /**
                 * A restarted server is a new project database, and the answers here were the old
                 * one's. Dropping them is not just hygiene: the usual reason for a restart is that
                 * the binary or the configuration changed, which is exactly when the answer differs.
                 */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    answers.clear()
                    servedNothing.clear()
                }
            },
        )
    }

    /**
     * Where [file] holds fragments of another language, as far as this knows right now.
     *
     * Empty is an ordinary answer and means "no fragments", including while the first request for a
     * revision is still out.
     */
    fun forFile(file: PsiFile): List<ByInjection> {
        if (!BasedPythonSettings.getInstance(project).byLanguageInjection) return emptyList()
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return emptyList()
        val stamp = document.modificationStamp

        answers[virtualFile]?.let { if (it.stamp == stamp) return it.injections }

        if (ApplicationManager.getApplication().isDispatchThread) {
            askInBackground(original)
            return emptyList()
        }
        return ask(virtualFile, document, stamp)
    }

    /**
     * Puts an answer in as though the server had just given it, for [file] as it is right now.
     *
     * The seam a test needs, and the smallest one there is: everything downstream of the request —
     * matching a fragment to a literal, resolving the language, placing the injection — is what
     * there is to get wrong, and none of it should need a `by` process to exercise.
     */
    @TestOnly
    fun remember(file: PsiFile, injections: List<ByInjection>) {
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return
        answers[virtualFile] = Answer(document.modificationStamp, injections)
    }

    /**
     * Asks the server about [virtualFile] and remembers the answer.
     *
     * A failure — a timeout, a server that is not running — is deliberately not remembered. An
     * empty answer that came from nobody answering is indistinguishable from a file with no
     * fragments in it, and caching it would leave the file un-injected until it was next edited.
     */
    private fun ask(virtualFile: VirtualFile, document: Document, stamp: Long): List<ByInjection> {
        val server = runningByServer(project, virtualFile) ?: return emptyList()
        ByServerDocuments.ensureOpen(server, project, virtualFile)

        val params = ByInjectionsParams(
            TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri),
        )
        val answer = server.askBy("by/injections", INJECTIONS_TIMEOUT_MS) {
            (it as ByServerExtensions).injections(params)
        }
        val found = when (answer) {
            is ByAnswer.Answer -> ByInjectionReplies.read(answer.value, document)
            // The server declined — language services are off, or this is not a document it serves.
            // An ordinary answer, and one worth remembering so the next pass does not ask again.
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return emptyList()
        }
        answers[virtualFile] = Answer(stamp, found)
        return found
    }

    /**
     * Asks about [file] off the EDT, and re-injects if the answer turns out not to be the nothing
     * that was served in the meantime.
     *
     * The read action is a *non-blocking* one, and that is not a detail. The request inside it waits
     * on the server for up to [INJECTIONS_TIMEOUT_MS], and a plain `ReadAction.compute` holds the
     * read lock for all of it with no indicator for `sendRequestSync` to notice a cancellation on —
     * so a write action starting in that window, which is to say a keystroke, would freeze the EDT
     * until the server answered. A non-blocking read action gives way to the pending write and is
     * retried instead.
     */
    private fun askInBackground(file: PsiFile) {
        val virtualFile = file.virtualFile ?: return
        // Nothing to ask: no thread is worth starting, and in a test there is never a server.
        if (byServerFor(project, virtualFile) == null) return
        if (!asking.add(virtualFile)) return
        servedNothing.add(virtualFile)
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val found = ReadAction.nonBlocking<List<ByInjection>> {
                    if (!file.isValid) return@nonBlocking emptyList()
                    val document = PsiDocumentManager.getInstance(project).getDocument(file)
                        ?: return@nonBlocking emptyList()
                    ask(virtualFile, document, document.modificationStamp)
                }.expireWith(this).executeSynchronously()
                if (found.isNotEmpty() && servedNothing.remove(virtualFile)) reinject(virtualFile)
            } catch (_: ProcessCanceledException) {
                // The project closed, or the file changed under the request. Either way the next
                // pass asks again against whatever the document says then.
                servedNothing.remove(virtualFile)
            } finally {
                asking.remove(virtualFile)
            }
        }
    }

    /**
     * Throws away the platform's cached "nothing is injected here" for [virtualFile].
     *
     * There is no narrower public way to say it. Injection results are cached per element behind
     * `PsiModificationTracker.MODIFICATION_COUNT`, which nothing but a change to the PSI moves, so
     * re-running the daemon alone would keep handing back the same cached nothing. Re-parsing the
     * one file drops its PSI and everything cached on it, which is the smallest hammer that lands.
     *
     * Rare by construction: at most once per file, and only when the EDT got in ahead of the
     * daemon's own background pass on a revision nothing had asked about yet.
     */
    private fun reinject(virtualFile: VirtualFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (!project.isDisposed && virtualFile.isValid) {
                    FileContentUtilCore.reparseFiles(listOf(virtualFile))
                }
            },
            project.disposed,
        )
    }

    override fun dispose() {
        answers.clear()
        asking.clear()
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /**
         * How long to wait for the fragments in one document.
         *
         * Generous next to a hover and tight next to a check: the request walks call sites and can
         * reach into another module, and it is being waited on by a highlighting pass that a
         * keystroke will cancel anyway.
         */
        private const val INJECTIONS_TIMEOUT_MS = 2_000

        fun getInstance(project: Project): ByInjections = project.service()
    }
}

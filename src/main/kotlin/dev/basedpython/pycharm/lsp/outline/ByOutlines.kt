package dev.basedpython.pycharm.lsp.outline

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.Collections

/**
 * What `by` last said about each `.by` document's block structure and strings, per revision.
 *
 * The one model behind every feature that needs to know what the parser knows: Enter indenting a
 * suite, Move Statement moving one, the clause keywords Move Caret to Matching Brace jumps between,
 * a string's escapes and interpolations, the margin basedpython strips a string to, and which
 * `print` a log point can stand in for. It replaced a scanner apiece, which disagreed with each
 * other and with the compiler.
 *
 * ## Revisions
 *
 * An answer is kept against the `Document.modificationStamp` it was asked at, and handed out only
 * for that stamp. Its offsets mean nothing against any other text, and an outline a keystroke old
 * reads a header that is no longer there.
 *
 * ## Which thread asks
 *
 * A request is background-only. Off the EDT, [forFile] asks and waits — inside a daemon pass that
 * is what the pass is for, and a keystroke cancels it. On the EDT, nothing waits: [current]
 * serves the answer for this revision if there is one, and otherwise null, which each caller
 * treats as "the platform's own behaviour" rather than guessing.
 *
 * That makes how fresh the answer is when a key is pressed the thing that matters, so every edit
 * of a `.by` document asks again in the background at once, rather than when the daemon next gets
 * round to it. The parse behind the answer is the server's cheapest request.
 *
 * ## Which text is asked about
 *
 * Each request names the text it is asked at by its [ByTextHash], and `by` answers about that text
 * and no other — from its buffer, from the file on disk for a document the platform has not opened
 * on it yet, or once the platform's `didOpen` or `didChange` brings it. That is what makes keeping
 * the answer against the stamp it was asked at correct, and it is why nothing here needs to know
 * when the platform opens a document on the server: the events that make this want an outline — a
 * pass over a newly opened editor, an edit to a file no editor shows, a server starting with files
 * on screen — all come before the platform's `didOpen`, and asking then is fine. This used to wait
 * for the `didOpen`, heard through the descriptor's `getLanguageId`, because `by` refused a document
 * it did not hold; before that it asked straight away and again 700ms later.
 */
@Service(Service.Level.PROJECT)
internal class ByOutlines(private val project: Project) : Disposable {

    /**
     * Weak keys: a file that is closed and collected takes its answer with it. Keyed by the file
     * rather than the `PsiFile`, which the platform rebuilds freely.
     */
    private val answers: MutableMap<VirtualFile, ByOutline> = ContainerUtil.createConcurrentWeakMap()

    /** Files a background request is already out for. */
    private val asking: MutableSet<VirtualFile> = ContainerUtil.newConcurrentSet()

    /** Files asked about while a request for them was out — see [askInBackground]. */
    private val askAgain: MutableSet<VirtualFile> = ContainerUtil.newConcurrentSet()

    /**
     * Files a daemon pass was given no outline for. The pass will not run again on its own until
     * the file is edited, so when an answer does come in for one of these its highlighting is
     * restarted — see [askInBackground]. Weakly held, like [answers].
     */
    private val servedNothing: MutableSet<VirtualFile> =
        Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap())

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /**
                 * A restarted server may be a different `by`, whose parse is the one that counts, so
                 * every file the old one answered for is asked again — an edited file no editor
                 * shows included. And every `.by` file on screen was highlighted while there was
                 * nobody to ask, so each is asked too, and its highlighting run again once it is
                 * answered.
                 */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    val files = (answers.keys + FileEditorManager.getInstance(project).openFiles).toSet()
                    answers.clear()
                    for (file in files) {
                        if (!file.isValid) continue
                        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: continue
                        // not the cached PSI: a tab not on screen may have none, and is still a file to ask about
                        val isBy = ReadAction.computeBlocking<Boolean, RuntimeException> {
                            file.isValid && PsiManager.getInstance(project).findFile(file) is BasedPythonFile
                        }
                        if (isBy) askInBackground(file, document)
                    }
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    val psi = PsiDocumentManager.getInstance(project).getCachedPsiFile(event.document)
                    if (psi !is BasedPythonFile) return
                    askInBackground(file, event.document)
                }
            },
            this,
        )
    }

    /**
     * The outline of [document] as it is now, if `by` has already said. Never waits, so it is what
     * a typing handler on the EDT reads; null asks in the background for next time.
     */
    fun current(document: Document): ByOutline? {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        answers[file]?.takeIf { it.stamp == document.modificationStamp }?.let { return it }
        askInBackground(file, document)
        return null
    }

    /**
     * The outline of [file] as it is now. Off the EDT this asks and waits when there is no answer
     * for this revision yet; on it, it is [current]. Null when no server answered.
     */
    fun forFile(file: PsiFile): ByOutline? {
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        if (ApplicationManager.getApplication().isDispatchThread) return current(document)
        answers[virtualFile]?.takeIf { it.stamp == document.modificationStamp }?.let { return it }
        return ask(virtualFile, document) ?: null.also { servedNothing.add(virtualFile) }
    }

    /**
     * Puts an outline in as though the server had just given it, for [document] as it is now.
     *
     * Everything downstream of the request — which line a header is on, what a statement move
     * swaps, where a margin is drawn — is what there is to get wrong, and none of it should need a
     * `by` process to exercise.
     */
    @TestOnly
    fun remember(document: Document, outline: ByOutline) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        answers[file] = outline
    }

    /**
     * Asks the server about [document] and remembers the answer. Call inside a read action, so the
     * text the server is asked about is the text its positions are read against.
     *
     * A failure is not remembered: nobody answering is not a file with no statements.
     */
    private fun ask(virtualFile: VirtualFile, document: Document): ByOutline? {
        val server = runningByServer(project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, project, virtualFile)

        val params = BySyntaxOutlineParams(
            TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri),
            // the text the answer's stamp will say it is about
            ByTextHash.of(document.immutableCharSequence),
        )
        val answer = server.askBy("by/syntaxOutline", TIMEOUT_MS) {
            (it as ByServerExtensions).syntaxOutline(params)
        }
        val outline = when (answer) {
            is ByAnswer.Answer -> ByOutlineReplies.read(answer.value, document) ?: return null
            // Declined: language services are off. An ordinary answer, with nothing in it — and
            // marked as such, since "nothing said about strings" is not "no strings".
            ByAnswer.None -> ByOutline(document.modificationStamp, emptyList(), emptyList(), declined = true)
            ByAnswer.Failed -> return null
        }
        answers[virtualFile] = outline
        return outline
    }

    /**
     * Asks about [document] off the EDT, once at a time per file.
     *
     * A non-blocking read action, so a keystroke arriving while the server is being waited on
     * cancels the wait and the read is retried against the new text, instead of the keystroke
     * queueing behind the server. An edit that lands after the answer but before the next ask can
     * be let in is caught by comparing stamps once this one is done.
     *
     * A call while a request is already out is not dropped but noted in [askAgain], and the one out
     * asks once more when it is done. An edit is caught by the stamps either way; what this catches
     * is the ask a new server makes of every file on screen arriving while a request to the old one
     * is still out, which comes back with nothing and would otherwise be the last word.
     */
    private fun askInBackground(virtualFile: VirtualFile, document: Document) {
        // Nothing to ask: no thread is worth starting, and in a test there is never a server.
        if (byServerFor(project, virtualFile) == null) return
        askAgain.add(virtualFile)
        if (!asking.add(virtualFile)) return
        AppExecutorUtil.getAppExecutorService().execute {
            var answered: Long? = null
            try {
                while (askAgain.remove(virtualFile) && !project.isDisposed) {
                    answered = ReadAction.nonBlocking<Long?> {
                        ask(virtualFile, document)?.stamp
                    }.expireWith(this).executeSynchronously()
                }
            } catch (_: ProcessCanceledException) {
                // The project closed. Nothing to ask for any more.
            } finally {
                asking.remove(virtualFile)
            }
            if (project.isDisposed) return@execute
            // asked for between the last look at [askAgain] and the request no longer counting as out
            if (virtualFile in askAgain) return@execute askInBackground(virtualFile, document)
            if (answered == null) return@execute
            if (answered != document.modificationStamp) {
                askInBackground(virtualFile, document)
            } else if (servedNothing.remove(virtualFile)) {
                rehighlight(virtualFile)
            }
        }
    }

    /** Runs the daemon over [file] again, now that there is an outline for the passes to read. */
    private fun rehighlight(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
                DaemonCodeAnalyzer.getInstance(project).restart(psi, "basedpython syntax outline arrived")
            },
            project.disposed,
        )
    }

    override fun dispose() {
        answers.clear()
        asking.clear()
        askAgain.clear()
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /**
         * How long a daemon pass waits for an outline. A parse, so an answer that has not come in
         * this long is a server that is busy with something else, and the pass is better off
         * without.
         */
        private const val TIMEOUT_MS = 2_000

        fun getInstance(project: Project): ByOutlines = project.service()
    }
}

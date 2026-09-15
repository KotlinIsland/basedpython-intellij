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
import com.intellij.openapi.fileEditor.FileEditorManagerListener
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
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.concurrent.TimeUnit

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
                 * A restarted server may be a different `by`, whose parse is the one that counts.
                 * And every `.by` file already on screen was highlighted while there was nobody to
                 * ask, so each is asked again once the client has had time to open it on the server.
                 */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    answers.clear()
                    FileEditorManager.getInstance(project).openFiles.forEach(::askSoon)
                }
            },
        )
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                /**
                 * The first pass over a file opened while the server runs usually asks before the
                 * client's `didOpen` has reached the server, and is told the document is not open.
                 * Completion of that is not observable from here, so it is looked at again shortly.
                 */
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (byServerFor(project, file) != null) askSoon(file)
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
        )
        val answer = server.askBy("by/syntaxOutline", TIMEOUT_MS) {
            (it as ByServerExtensions).syntaxOutline(params)
        }
        val outline = when (answer) {
            is ByAnswer.Answer -> ByOutlineReplies.read(answer.value, document) ?: return null
            // Declined: language services are off. An ordinary answer, with nothing in it.
            ByAnswer.None -> ByOutline(document.modificationStamp, emptyList(), emptyList())
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
     */
    private fun askInBackground(virtualFile: VirtualFile, document: Document) {
        // Nothing to ask: no thread is worth starting, and in a test there is never a server.
        if (byServerFor(project, virtualFile) == null) return
        if (!asking.add(virtualFile)) return
        AppExecutorUtil.getAppExecutorService().execute {
            var answered: Long? = null
            try {
                answered = ReadAction.nonBlocking<Long?> {
                    ask(virtualFile, document)?.stamp
                }.expireWith(this).executeSynchronously()
            } catch (_: ProcessCanceledException) {
                // The project closed. Nothing to ask for any more.
            } finally {
                asking.remove(virtualFile)
            }
            if (answered == null || project.isDisposed) return@execute
            if (answered != document.modificationStamp) {
                askInBackground(virtualFile, document)
            } else if (servedNothing.remove(virtualFile)) {
                rehighlight(virtualFile)
            }
        }
    }

    /** [askInBackground] for [file], once the client has had [RECHECK_MS] to open it on the server. */
    private fun askSoon(file: VirtualFile) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                if (project.isDisposed || !file.isValid) return@schedule
                val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@schedule
                val psi = ReadAction.computeBlocking<PsiFile?, RuntimeException> {
                    PsiDocumentManager.getInstance(project).getCachedPsiFile(document)
                }
                if (psi is BasedPythonFile) askInBackground(file, document)
            },
            RECHECK_MS,
            TimeUnit.MILLISECONDS,
        )
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

        /**
         * How long after a server starts, or a file opens, to ask about the file again: long enough
         * for the client's `didOpen` to reach the server, short enough that highlighting does not
         * visibly arrive late.
         */
        private const val RECHECK_MS = 700L

        fun getInstance(project: Project): ByOutlines = project.service()
    }
}

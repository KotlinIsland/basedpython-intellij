package dev.basedpython.pycharm.lsp.folding

import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedFoldingRangeParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.Position
import org.jetbrains.annotations.TestOnly
import java.util.Collections

/**
 * What `by` last said about each `.by` document's folding ranges, per revision: the answer to
 * `textDocument/foldingRange`, with basedpython's own kinds and the text to fold to.
 *
 * Asked by the folding builder rather than by the platform's LSP folding, which asks only once a
 * file is open in an editor and then reads its answer into folding passes that come after the one
 * that opens the editor — the only pass that folds by default, so *Fold by default: Imports* never
 * folded anything. [forFile] asks and waits, off the EDT, as that first pass is.
 *
 * The first pass can still come before the server is up, the server being started by the file it
 * opens. That pass is noted, and once the server is ready each of its editors is folded again as
 * though it had just opened, so what folds by default still does.
 */
@Service(Service.Level.PROJECT)
internal class ByFoldingRanges(private val project: Project) : Disposable {

    /** One range, as `by` sent it. */
    data class Fold(val range: TextRange, val kind: String?, val collapsedText: String?)

    private class Answer(val stamp: Long, val folds: List<Fold>)

    /** Weak keys: a closed and collected file takes its answer with it. */
    private val answers: MutableMap<VirtualFile, Answer> = ContainerUtil.createConcurrentWeakMap()

    /** Files a folding pass was given nothing for; weakly held, like [answers]. */
    private val servedNothing: MutableSet<VirtualFile> =
        Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap())

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    answers.clear()
                    refoldServedNothing()
                }
            },
        )
    }

    /** The ranges of [document] as it is now, if `by` has already said; never waits. */
    fun current(document: Document): List<Fold>? {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        return answers[file]?.takeIf { it.stamp == document.modificationStamp }?.folds
    }

    /**
     * The ranges of [file] as it is now. Off the EDT this asks and waits when there is no answer
     * for this revision yet; on it, it is [current]. Null when no server answered.
     */
    fun forFile(file: PsiFile): List<Fold>? {
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        current(document)?.let { return it }
        if (ApplicationManager.getApplication().isDispatchThread) return null
        return ask(virtualFile, document) ?: null.also { servedNothing.add(virtualFile) }
    }

    @TestOnly
    fun remember(document: Document, folds: List<Fold>) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        answers[file] = Answer(document.modificationStamp, folds)
    }

    /** Asks the server about [document]; call inside a read action, so the answer is about this text. */
    private fun ask(virtualFile: VirtualFile, document: Document): List<Fold>? {
        val server = runningByServer(project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val params = ByNamedFoldingRangeParams(
            server.getDocumentIdentifier(virtualFile),
            ByTextHash.of(document.immutableCharSequence),
        )
        val answer = server.askBy("textDocument/foldingRange", TIMEOUT_MS) { it.textDocumentService.foldingRange(params) }
        val folds = when (answer) {
            is ByAnswer.Answer -> answer.value.mapNotNull { fold(it, document) }
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return null
        }
        answers[virtualFile] = Answer(document.modificationStamp, folds)
        return folds
    }

    /** [range] in [document]'s offsets; a missing character is the end of its line. */
    private fun fold(range: FoldingRange, document: Document): Fold? {
        fun offset(line: Int, character: Int?): Int? {
            if (line < 0 || line >= document.lineCount) return null
            return if (character == null) document.getLineEndOffset(line)
            else getOffsetInDocument(document, Position(line, character))
        }
        val start = offset(range.startLine, range.startCharacter) ?: return null
        val end = offset(range.endLine, range.endCharacter) ?: return null
        if (end <= start) return null
        return Fold(TextRange(start, end), range.kind, range.collapsedText)
    }

    /**
     * Folds every editor of a file a pass was given nothing for again, as though it had just opened:
     * the regions are all new to it, so each takes the folded state *Fold by default* gives it.
     */
    private fun refoldServedNothing() {
        val files = servedNothing.toList()
        servedNothing.clear()
        for (file in files) {
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: continue
            for (editor in EditorFactory.getInstance().getEditors(document, project)) refold(editor)
        }
    }

    private fun refold(editor: Editor) {
        ReadAction.nonBlocking<Runnable?> {
            val psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
            if (psi !is BasedPythonFile || forFile(psi) == null) return@nonBlocking null
            CodeFoldingManager.getInstance(project).updateFoldRegionsAsync(editor, true)
        }
            .withDocumentsCommitted(project)
            .expireWith(this)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.nonModal()) { it?.run() }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun dispose() {
        answers.clear()
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /** How long a folding pass waits: a parse's worth, and the pass is better off without after it. */
        private const val TIMEOUT_MS = 2_000

        fun getInstance(project: Project): ByFoldingRanges = project.service()
    }
}

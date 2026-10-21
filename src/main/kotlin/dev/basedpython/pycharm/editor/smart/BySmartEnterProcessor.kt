package dev.basedpython.pycharm.editor.smart

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByCompleteStatementParams
import dev.basedpython.pycharm.lsp.ext.ByCompletedStatement
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.FormattingOptions
import org.jetbrains.annotations.VisibleForTesting

/**
 * Complete Current Statement (Ctrl+Shift+Enter) in a `.by` file: the statement at the caret
 * finished off and the line after it started, both as `by/completeStatement` answers.
 *
 * What the statement is missing — a closing quote, the brackets left open on the line, a header's
 * `:` (and a `def`'s `()`), a clause moved out to the statement it continues — and where the next
 * line starts and how deep, are all `by`'s: they are what its parser says the line is. This only
 * asks, and applies the answer.
 *
 * ## Asking without holding the EDT
 *
 * The action runs on the EDT in a write action, where a request to `by` cannot be waited on. So
 * this claims the keystroke, asks in the background, and applies the answer as a command of its own
 * once it comes — unless the text or the caret has moved meanwhile, since the answer is about a
 * line the user has left. The answer is a parse, so it is back before a person can type again.
 *
 * With no `by` running this does not claim the keystroke, and the platform starts a new line, which
 * is what the action does in a language with nothing to complete. A `by` that cannot answer, or is
 * too old to know the request, is said in a hint, as Go to Super says it.
 */
class BySmartEnterProcessor : SmartEnterProcessor() {

    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        if (psiFile !is BasedPythonFile) return false
        val file = psiFile.originalFile.virtualFile ?: return false
        val server = byServerFor(project, file) ?: return false
        val indentOptions = CodeStyle.getIndentOptions(psiFile)
        val options = FormattingOptions(indentOptions.INDENT_SIZE, !indentOptions.USE_TAB_CHARACTER)
        project.service<ByStatementCompletion>().complete(editor) { document, offset ->
            ask(server, file, document, offset, options)
        }
        return true
    }

    /**
     * What `by` says about the statement at [offset] in [document], [file]'s, told whether it
     * declined, could not answer, or does not know the request at all.
     */
    @VisibleForTesting
    internal suspend fun ask(
        server: LspClient,
        file: VirtualFile,
        document: Document,
        offset: Int,
        options: FormattingOptions,
    ): ByStatementAnswer {
        val params = readAction {
            ByServerDocuments.ensureOpen(server, server.project, file)
            // the caret and the text it is in, read together
            ByCompleteStatementParams(
                server.getDocumentIdentifier(file),
                getLsp4jPosition(document, offset),
                options,
                ByTextHash.of(document.immutableCharSequence),
            )
        }
        var unknownRequest = false
        val answer = awaitingAgain<ByCompletedStatement>("by/completeStatement", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                server.sendRequest { (it as ByServerExtensions).completeStatement(params).also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknownRequest = true
                throw e
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> ByStatementAnswer.Completed(answer.value)
            // declined: language services are off
            ByAnswer.None -> ByStatementAnswer.Unanswered(BasedPythonBundle.message("smart.enter.declined"))
            ByAnswer.Failed -> ByStatementAnswer.Unanswered(
                BasedPythonBundle.message(if (unknownRequest) "smart.enter.unsupported" else "smart.enter.noAnswer"),
            )
        }
    }
}

/** What `by` said about the statement at the caret. */
internal sealed interface ByStatementAnswer {

    /** The statement completed. */
    data class Completed(val statement: ByCompletedStatement) : ByStatementAnswer

    /** Nothing to apply, and what to tell the user about why. */
    data class Unanswered(val message: String) : ByStatementAnswer
}

/**
 * Where Complete Current Statement's asking runs, and where its answer is applied: cancelled with
 * the project, and with the plugin when it unloads.
 */
@Service(Service.Level.PROJECT)
internal class ByStatementCompletion(private val project: Project, private val scope: CoroutineScope) {

    /**
     * Asks [find] how the statement at [editor]'s caret is completed, off the EDT, and applies the
     * answer on it — unless by then the editor is gone, or its caret or text has moved.
     */
    @VisibleForTesting
    internal fun complete(editor: Editor, find: suspend (Document, Int) -> ByStatementAnswer): Job {
        val document = editor.document
        val offset = editor.caretModel.offset
        val stamp = document.modificationStamp
        return scope.launch {
            val answer = find(document, offset)
            withContext(Dispatchers.EDT) {
                val stillThere = !editor.isDisposed &&
                    document.modificationStamp == stamp &&
                    editor.caretModel.offset == offset
                if (stillThere) apply(editor, answer)
            }
        }
    }

    private fun apply(editor: Editor, answer: ByStatementAnswer) {
        when (answer) {
            is ByStatementAnswer.Unanswered -> HintManager.getInstance().showInformationHint(editor, answer.message)
            is ByStatementAnswer.Completed -> {
                val document = editor.document
                WriteCommandAction.writeCommandAction(project)
                    .withName(BasedPythonBundle.message("smart.enter.command"))
                    .run<RuntimeException> {
                        if (!applyTextEdits(document, answer.statement.edits)) return@run
                        val caret = answer.statement.caret?.let { getOffsetInDocument(document, it) } ?: return@run
                        editor.caretModel.moveToOffset(caret)
                        editor.selectionModel.removeSelection()
                        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
                    }
            }
        }
    }
}

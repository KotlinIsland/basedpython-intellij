package dev.basedpython.pycharm.editor.unwrap

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.unwrap.UnwrapDescriptor
import com.intellij.codeInsight.unwrap.Unwrapper
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByUnwrapOption
import dev.basedpython.pycharm.lsp.ext.ByUnwrapParams
import dev.basedpython.pycharm.lsp.ext.ByUnwrapResponse
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Code | Unwrap/Remove (Ctrl+Shift+Delete) in a `.by` file: unwrap the `if`, loop, `try` or `with`
 * around the caret into one of its suites, or remove one of its clauses.
 *
 * What there is to unwrap, and every edit, is `by`'s answer to `by/unwrap`; this hands it to the
 * platform's own popup, which lists the options innermost first and, on the one selected, marks
 * what goes apart from what stays. Asked once, when the action is invoked: the popup is shown from
 * inside the call that asks, so the asking is a modal progress, as the refactorings' is.
 *
 * Without this the action is not even enabled in a `.by` file: the platform offers it only for a
 * language with a descriptor.
 */
class ByUnwrapDescriptor : UnwrapDescriptor {

    override fun collectUnwrappers(project: Project, editor: Editor, file: PsiFile): List<Pair<PsiElement, Unwrapper>> {
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val server = byServerFor(project, virtualFile) ?: return nothing(editor, BasedPythonBundle.message("unwrap.by.noServer"))

        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val start = caret.selectionStart
        val end = caret.selectionEnd
        val stamp = document.modificationStamp

        val answer = runWithModalProgressBlocking(project, BasedPythonBundle.message("unwrap.by.progress")) {
            ByUnwrap.ask(server, virtualFile, document, start, end)
        }
        if (document.modificationStamp != stamp) return nothing(editor, BasedPythonBundle.message("unwrap.by.changed"))

        val options = when (answer) {
            is ByUnwrap.Answer.Options -> answer.response
            ByUnwrap.Answer.Unsupported -> return nothing(editor, BasedPythonBundle.message("unwrap.by.unsupported"))
            ByUnwrap.Answer.Declined -> return nothing(editor, BasedPythonBundle.message("unwrap.by.declined"))
            ByUnwrap.Answer.Failed -> return nothing(editor, BasedPythonBundle.message("unwrap.by.noAnswer"))
        }
        options.refused?.let { return nothing(editor, BasedPythonBundle.message("unwrap.by.refusedAll", it)) }
        val unwrappers = options.options.mapNotNull { ByUnwrapper.of(it, file, document) }
        if (unwrappers.isEmpty()) {
            // every option there was is refused: the innermost one's reason is the one about the caret
            val refusal = options.options.firstNotNullOfOrNull { option -> option.refused?.let { option.title to it } }
            return nothing(
                editor,
                refusal?.let { (title, reason) -> BasedPythonBundle.message("unwrap.by.refused", title ?: "", reason) }
                    ?: BasedPythonBundle.message("unwrap.by.nothingHere"),
            )
        }
        return unwrappers.map { Pair.create(file, it) }
    }

    override fun showOptionsDialog(): Boolean = true

    /**
     * The platform's restoring puts the caret back on the token it was on, by a mark it leaves in
     * the tree; the edits here are to the document, which the tree has not caught up with when the
     * caret is restored, so the token is found where it used to be. `by`'s edits are small ones
     * around the suite kept, and leave the caret in place by themselves.
     */
    override fun shouldTryToRestoreCaretPosition(): Boolean = false

    private fun nothing(editor: Editor, message: String): List<Pair<PsiElement, Unwrapper>> {
        HintManager.getInstance().showInformationHint(editor, message)
        return emptyList()
    }
}

/**
 * One of `by`'s options, as the platform's popup lists and applies it.
 *
 * Holds the edits as offsets into the document revision [stamp] — the one they were computed for —
 * and applies nothing to any other.
 */
internal class ByUnwrapper private constructor(
    private val title: String,
    private val file: PsiFile,
    private val document: Document,
    private val stamp: Long,
    private val range: TextRange,
    private val kept: List<TextRange>,
    private val edits: List<kotlin.Pair<TextRange, String>>,
) : Unwrapper {

    override fun isApplicableTo(e: PsiElement): Boolean = true

    override fun collectElementsToIgnore(element: PsiElement, result: MutableSet<PsiElement>) = Unit

    override fun getDescription(e: PsiElement): String = title

    override fun collectAffectedElements(e: PsiElement, toExtract: MutableList<in PsiElement>): PsiElement {
        kept.mapTo(toExtract) { ByRangeElement(file, it) }
        return ByRangeElement(file, range)
    }

    /** Runs in the platform's command and write action. */
    override fun unwrap(editor: Editor, element: PsiElement): List<PsiElement> {
        if (document.modificationStamp != stamp) {
            HintManager.getInstance().showErrorHint(editor, BasedPythonBundle.message("unwrap.by.changed"))
            return emptyList()
        }
        // last first, so each range still means the text it was worked out against
        for ((range, text) in edits.sortedByDescending { it.first.startOffset }) {
            document.replaceString(range.startOffset, range.endOffset, text)
        }
        return emptyList()
    }

    companion object {
        /**
         * [option] for [document] as it is now, or `null` when it cannot be applied: `by` refused
         * it, or a range of it does not fit the document, which is a reply about some other text.
         */
        fun of(option: ByUnwrapOption, file: PsiFile, document: Document): ByUnwrapper? {
            val title = option.title ?: return null
            val edits = option.edits ?: return null
            val range = option.range?.let { getRangeInDocument(document, it) } ?: return null
            val kept = option.kept.map { getRangeInDocument(document, it) ?: return null }
            val offsets = edits.map { edit ->
                val editRange = edit.range?.let { getRangeInDocument(document, it) } ?: return null
                editRange to (edit.newText ?: return null)
            }
            return ByUnwrapper(title, file, document, document.modificationStamp, range, kept, offsets)
        }
    }
}

/** Asking `by` what to unwrap. */
internal object ByUnwrap {

    sealed interface Answer {
        data class Options(val response: ByUnwrapResponse) : Answer

        /** The running `by` is from before `by/unwrap`. */
        data object Unsupported : Answer

        /** `by` answered that it does not rewrite this document: language services are off. */
        data object Declined : Answer

        /** No answer: the request failed or timed out. */
        data object Failed : Answer
    }

    /**
     * What `by` offers for the text between [start] and [end] of [document], asked about the text
     * as it is now (see [ByTextHash]). Suspends on the server, and stops waiting the moment the
     * caller is cancelled.
     */
    suspend fun ask(server: LspClient, file: VirtualFile, document: Document, start: Int, end: Int): Answer {
        val params = readAction {
            ByServerDocuments.ensureOpen(server, server.project, file)
            ByUnwrapParams(
                TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
                getLsp4jRange(document, start, end - start),
                ByTextHash.of(document.immutableCharSequence),
            )
        }
        var unknownRequest = false
        val answer = awaitingAgain<ByUnwrapResponse>("by/unwrap", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                server.sendRequest { (it as ByServerExtensions).unwrap(params).also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknownRequest = true
                throw e
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> Answer.Options(answer.value)
            ByAnswer.None -> Answer.Declined
            ByAnswer.Failed -> if (unknownRequest) Answer.Unsupported else Answer.Failed
        }
    }
}

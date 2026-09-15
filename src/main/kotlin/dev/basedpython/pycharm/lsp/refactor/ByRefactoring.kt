package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.askBy
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * The refactorings `by` performs, each named by the code action kind it offers it under.
 *
 * The refactoring itself — what it reads, what it refuses, the edit — is the server's. These are
 * only the names the editor's menu entries ask for.
 */
enum class ByRefactoring(val kind: String) {
    InlineVariable("refactor.inline.variable"),
    ExtractVariable("refactor.extract.variable"),
    IntroduceConstant("refactor.extract.constant"),
    ExtractFunction("refactor.extract.function"),
}

/** What `by` said about a refactoring at a range. */
sealed interface ByRefactoringOffer {

    /** The refactoring applies; [action] is the code action to resolve and apply. */
    data class Available(val action: CodeAction) : ByRefactoringOffer

    /** The refactoring is about what is at the range, but would change what the program means. */
    data class Refused(val title: String, val reason: String) : ByRefactoringOffer

    /** Nothing at the range is what the refactoring rewrites. */
    data object NotOffered : ByRefactoringOffer

    /** The request could not be made or failed. */
    data object Failed : ByRefactoringOffer
}

object ByRefactoringRequest {

    /**
     * The request for [refactoring] at [range], made the way an explicit invocation is: only that
     * kind, and `Invoked`, which is what makes the server say *why* a refactoring does not apply
     * instead of leaving it out.
     */
    fun params(document: TextDocumentIdentifier, range: Range, refactoring: ByRefactoring): CodeActionParams =
        CodeActionParams(
            document,
            range,
            CodeActionContext(emptyList(), listOf(refactoring.kind)).apply {
                triggerKind = CodeActionTriggerKind.Invoked
            },
        )

    /** Reads the server's reply for [refactoring]. */
    fun offer(reply: List<Either<Command, CodeAction>>, refactoring: ByRefactoring): ByRefactoringOffer {
        val action = reply
            .mapNotNull { if (it.isRight) it.right else null }
            .firstOrNull { it.kind == refactoring.kind }
            ?: return ByRefactoringOffer.NotOffered
        val disabled = action.disabled ?: return ByRefactoringOffer.Available(action)
        return ByRefactoringOffer.Refused(action.title, disabled.reason)
    }

    /**
     * Asks [server] about [refactoring] for the text between [start] and [end] of [file].
     *
     * Background only, inside a read action for the offsets to mean the document they were taken
     * from; the wait polls cancellation, so a cancelled caller unwinds instead of hanging on `by`.
     */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun ask(
        server: LspClient,
        file: VirtualFile,
        document: Document,
        start: Int,
        end: Int,
        refactoring: ByRefactoring,
    ): ByRefactoringOffer {
        val params = params(server.getDocumentIdentifier(file), getLsp4jRange(document, start, end - start), refactoring)
        return when (val answer = server.askBy("textDocument/codeAction") { it.textDocumentService.codeAction(params) }) {
            is ByAnswer.Answer -> offer(answer.value, refactoring)
            ByAnswer.None -> ByRefactoringOffer.NotOffered
            ByAnswer.Failed -> ByRefactoringOffer.Failed
        }
    }
}

package dev.basedpython.pycharm.lsp.refactor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * The refactorings `by` performs, each named by the code action kind it offers it under.
 *
 * The refactoring itself — what it reads, what it refuses, the edit — is the server's. These are
 * only the names the platform's refactoring actions ask for, and the name each is shown under.
 *
 * [replacesExpression] is a refactoring that binds an expression to a new name and reads the name
 * where the expression was — `by`'s contract for each of them. With nothing selected, `by` offers
 * one for each expression around the caret, and the user picks one; once it is made, the new name
 * is the end of the text that replaced the expression, and that is where its renaming starts.
 */
enum class ByRefactoring(val kind: String, private val titleKey: String, val replacesExpression: Boolean) {
    InlineVariable("refactor.inline.variable", "refactoring.by.title.inlineVariable", false),
    ExtractVariable("refactor.extract.variable", "refactoring.by.title.extractVariable", true),
    IntroduceConstant("refactor.extract.constant", "refactoring.by.title.introduceConstant", true),
    IntroduceParameter("refactor.extract.parameter", "refactoring.by.title.introduceParameter", true),
    IntroduceField("refactor.extract.field", "refactoring.by.title.introduceField", true),
    ExtractFunction("refactor.extract.function", "refactoring.by.title.extractFunction", false),
    ;

    val title: String get() = BasedPythonBundle.message(titleKey)
}

/** What `by` said about a refactoring at a range. */
sealed interface ByRefactoringOffer {

    /**
     * The refactoring applies; each of [actions] is a code action to resolve and apply, one per
     * expression around a caret, innermost first, or the one for a selection.
     */
    data class Available(val actions: List<CodeAction>) : ByRefactoringOffer

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

    /**
     * Reads the server's reply for [refactoring]: every action of the kind it would perform, in the
     * order it gave them; when it would perform none, its refusal of the first.
     */
    fun offer(reply: List<Either<Command, CodeAction>>, refactoring: ByRefactoring): ByRefactoringOffer {
        val actions = reply
            .mapNotNull { if (it.isRight) it.right else null }
            .filter { it.kind == refactoring.kind }
        val available = actions.filter { it.disabled == null }
        if (available.isNotEmpty()) return ByRefactoringOffer.Available(available)
        val refused = actions.firstOrNull() ?: return ByRefactoringOffer.NotOffered
        return ByRefactoringOffer.Refused(refused.title, refused.disabled.reason)
    }

    /**
     * The range of the expression [action] rewrites, which `by` names in its `data` for each of the
     * actions it offers around a caret; `null` when it does not say.
     */
    fun expressionRange(action: CodeAction): Range? {
        val range = (action.data as? JsonObject)?.get("range") as? JsonObject ?: return null
        return try {
            Gson().fromJson(range, Range::class.java)?.takeIf { it.start != null && it.end != null }
        } catch (_: JsonParseException) {
            null
        }
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

/**
 * Where the new name of a [ByRefactoring.replacesExpression] refactoring ends once its edit is made.
 *
 * `by` replaces the expression with a read of the new name — `value`, `NAME`, or `self.value` for an
 * attribute — so the edit whose range is the expression's ends with the name. This is only the
 * arithmetic of LSP text edits, whose ranges are all in the document before any of them is made:
 * that edit's start moves by what each edit wholly before it adds or removes.
 */
object ByExtractedName {

    /**
     * The offset, in [document] once [edits] are made to it, of the end of the text replacing
     * [replaced]; `null` when no edit replaces exactly that range.
     */
    fun endAfter(document: Document, edits: List<TextEdit>, replaced: Range): Int? {
        val replacement = edits.singleOrNull { it.range == replaced } ?: return null
        val start = getOffsetInDocument(document, replaced.start) ?: return null
        var shift = 0
        for (edit in edits) {
            if (edit === replacement) continue
            val editStart = getOffsetInDocument(document, edit.range.start) ?: return null
            val editEnd = getOffsetInDocument(document, edit.range.end) ?: return null
            if (editEnd <= start) shift += edit.newText.length - (editEnd - editStart)
        }
        return start + shift + replacement.newText.length
    }
}

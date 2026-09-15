package dev.basedpython.pycharm.lsp.refactor

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The menu entries ask `by` for one refactoring and read back what it said. Both halves are stated
 * here, because a wrong kind or trigger is not an error anywhere: the server just answers a
 * different question, and the entry silently finds nothing.
 */
class ByRefactoringRequestTest {

    private val document = TextDocumentIdentifier("file:///project/main.by")
    private val range = Range(Position(1, 4), Position(1, 9))

    @Test
    fun `the request asks for exactly the refactoring, as an explicit invocation`() {
        val params = ByRefactoringRequest.params(document, range, ByRefactoring.ExtractFunction)

        assertEquals(document, params.textDocument)
        assertEquals(range, params.range)
        assertEquals(listOf("refactor.extract.function"), params.context.only)
        assertEquals(CodeActionTriggerKind.Invoked, params.context.triggerKind)
        assertEquals(emptyList<Any>(), params.context.diagnostics)
    }

    @Test
    fun `the kinds are the ones by offers its refactorings under`() {
        assertEquals(
            listOf(
                "refactor.inline.variable",
                "refactor.extract.variable",
                "refactor.extract.constant",
                "refactor.extract.function",
            ),
            ByRefactoring.entries.map { it.kind },
        )
    }

    @Test
    fun `an action of the kind is available`() {
        val action = action("Inline variable `x`", "refactor.inline.variable")

        val offer = ByRefactoringRequest.offer(listOf(Either.forRight(action)), ByRefactoring.InlineVariable)

        assertEquals(ByRefactoringOffer.Available(action), offer)
    }

    @Test
    fun `a disabled action is a refusal with the server's reason`() {
        val action = action("Inline variable `x`", "refactor.inline.variable").apply {
            disabled = CodeActionDisabled("`x` is assigned more than once")
        }

        val offer = ByRefactoringRequest.offer(listOf(Either.forRight(action)), ByRefactoring.InlineVariable)

        assertEquals(ByRefactoringOffer.Refused("Inline variable `x`", "`x` is assigned more than once"), offer)
    }

    @Test
    fun `actions of other kinds and bare commands are not the refactoring`() {
        val reply = listOf<Either<Command, CodeAction>>(
            Either.forLeft(Command("run", "ty.printDebugInformation")),
            Either.forRight(action("Extract variable `value`", "refactor.extract.variable")),
            Either.forRight(action("Ignore 'unresolved-reference' for this line", "quickfix")),
        )

        assertEquals(ByRefactoringOffer.NotOffered, ByRefactoringRequest.offer(reply, ByRefactoring.ExtractFunction))
    }

    private fun action(title: String, kind: String) = CodeAction(title).apply { this.kind = kind }
}

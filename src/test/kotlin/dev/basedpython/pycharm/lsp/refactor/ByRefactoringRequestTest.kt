package dev.basedpython.pycharm.lsp.refactor

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
                "refactor.extract.parameter",
                "refactor.extract.field",
                "refactor.extract.function",
            ),
            ByRefactoring.entries.map { it.kind },
        )
    }

    @Test
    fun `an action of the kind is available`() {
        val action = action("Inline variable `x`", "refactor.inline.variable")

        val offer = ByRefactoringRequest.offer(listOf(Either.forRight(action)), ByRefactoring.InlineVariable)

        assertEquals(ByRefactoringOffer.Available(listOf(action)), offer)
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

    @Test
    fun `every expression around a caret is offered, innermost first, without the ones by refuses`() {
        val inner = action("Extract variable `value` from `radius ** 2`", "refactor.extract.variable")
        val refused = action("Extract variable `value` from `f(radius ** 2)`", "refactor.extract.variable").apply {
            disabled = CodeActionDisabled("it runs every iteration")
        }
        val outer = action("Extract variable `value` from `scale * f(radius ** 2)`", "refactor.extract.variable")

        val offer = ByRefactoringRequest.offer(listOf(inner, refused, outer).map { Either.forRight(it) }, ByRefactoring.ExtractVariable)

        assertEquals(ByRefactoringOffer.Available(listOf(inner, outer)), offer)
    }

    @Test
    fun `when by refuses every expression, the innermost refusal is the answer`() {
        val inner = action("Extract variable `value` from `q.pending()`", "refactor.extract.variable").apply {
            disabled = CodeActionDisabled("it runs every iteration")
        }
        val outer = action("Extract variable `value` from `not q.pending()`", "refactor.extract.variable").apply {
            disabled = CodeActionDisabled("it is a loop condition")
        }

        val offer = ByRefactoringRequest.offer(listOf(inner, outer).map { Either.forRight(it) }, ByRefactoring.ExtractVariable)

        assertEquals(ByRefactoringOffer.Refused(inner.title, "it runs every iteration"), offer)
    }

    @Test
    fun `the expression an action rewrites is the range in its data`() {
        val action = action("Extract variable `value` from `radius ** 2`", "refactor.extract.variable").apply {
            data = JsonParser.parseString(
                """{"range":{"start":{"line":4,"character":30},"end":{"line":4,"character":41}},"refactor":"extract-variable","version":0}""",
            )
        }

        assertEquals(Range(Position(4, 30), Position(4, 41)), ByRefactoringRequest.expressionRange(action))
        assertNull(ByRefactoringRequest.expressionRange(action("Extract variable `value`", "refactor.extract.variable")))
    }

    private fun action(title: String, kind: String) = CodeAction(title).apply { this.kind = kind }
}

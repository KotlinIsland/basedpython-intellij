package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByCompletedStatement
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Complete Current Statement: what is done with `by`'s answer, and when it is not done.
 *
 * What the answer is — which brackets close, where the `:` goes — is `by`'s and is tested there;
 * these put an answer in by hand.
 */
@TestFixtures
class BySmartEnterProcessorTest {

    private val fixture by codeInsightFixture()

    /** Runs the EDT until [job] is done, as the IDE would while the answer is out. */
    private fun waitFor(job: Job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(job.isCompleted, "Complete Current Statement never finished asking")
    }

    private fun edit(line: Int, from: Int, to: Int, text: String) =
        TextEdit(Range(Position(line, from), Position(line, to)), text)

    @Test
    fun `the answer's edits are made and the caret goes where it says`() = onEdt {
        fixture.configureByText("a.by", "if x:\n    a()\n    else<caret>\n")
        val answer = ByCompletedStatement(
            edits = listOf(edit(2, 0, 4, ""), edit(2, 8, 8, ":\n    ")),
            caret = Position(3, 4),
        )
        val job = fixture.project.service<ByStatementCompletion>()
            .complete(fixture.editor) { _, _ -> ByStatementAnswer.Completed(answer) }
        waitFor(job)
        fixture.checkResult("if x:\n    a()\nelse:\n    <caret>\n")
    }

    @Test
    fun `an answer for text the user has since changed is not applied`() = onEdt {
        fixture.configureByText("a.by", "if x<caret>\n")
        val document = fixture.editor.document
        val answer = ByCompletedStatement(listOf(edit(0, 4, 4, ":\n    ")), Position(1, 4))
        val job = fixture.project.service<ByStatementCompletion>().complete(fixture.editor) { _, _ ->
            val edited = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater({
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "z = 1\n") }
                edited.complete(Unit)
            }, ModalityState.nonModal())
            withTimeoutOrNull(3_000) { edited.await() }
            ByStatementAnswer.Completed(answer)
        }
        waitFor(job)
        fixture.checkResult("z = 1\nif x<caret>\n")
    }

    @Test
    fun `a by that does not know the request is told apart from one that did not answer`() = onEdt {
        fixture.configureByText("a.by", "if x<caret>\n")
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        val options = FormattingOptions(4, true)
        val old = AnsweringClient(fixture.project) { AnsweringClient.failed(ResponseErrorCode.MethodNotFound) }
        val broken = AnsweringClient(fixture.project) { AnsweringClient.failed(ResponseErrorCode.InternalError) }
        val current = AnsweringClient(fixture.project) {
            AnsweringClient.answered(ByCompletedStatement(listOf(edit(0, 4, 4, ":\n    ")), Position(1, 4)))
        }
        val processor = BySmartEnterProcessor()
        assertEquals(
            ByStatementAnswer.Unanswered(BasedPythonBundle.message("smart.enter.unsupported")),
            processor.ask(old, file, document, 4, options),
        )
        assertEquals(
            ByStatementAnswer.Unanswered(BasedPythonBundle.message("smart.enter.noAnswer")),
            processor.ask(broken, file, document, 4, options),
        )
        val completed = processor.ask(current, file, document, 4, options) as ByStatementAnswer.Completed
        assertEquals(Position(1, 4), completed.statement.caret)
    }

    /** With no `by` to ask, the keystroke is the platform's: a new line. */
    @Test
    fun `without a server the platform starts a new line`() = onEdt {
        fixture.configureByText("a.by", "x = f(<caret>1)\n")
        fixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
        fixture.checkResult("x = f(1)\n<caret>\n")
    }
}

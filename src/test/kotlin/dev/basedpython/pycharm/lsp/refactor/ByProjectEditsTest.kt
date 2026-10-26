package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByPositionParams
import dev.basedpython.pycharm.lsp.ext.ByProjectEditAnswer
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture

/**
 * What `by`'s project-wide refactorings — moving a definition, moving members, extracting a
 * superclass, changing a signature — have in common: how their answers are read, and how their edits
 * are made.
 */
@TestFixtures
class ByProjectEditsTest {

    private val fixture by codeInsightFixture()

    private fun ask(answer: CompletableFuture<*>): ByAsked<ByProjectEditAnswer> {
        val client = AnsweringClient(fixture.project) { answer }
        val params = ByPositionParams(TextDocumentIdentifier("file:///a.by"), Position(0, 4))
        @Suppress("UNCHECKED_CAST")
        return runBlocking { client.awaitByExtension("by/moveSymbol") { it.prepareMoveSymbol(params) as CompletableFuture<ByProjectEditAnswer?> } }
    }

    @Test
    fun `a by without the request is told apart from one that failed and from one with nothing to say`() = onEdt {
        assertEquals(ByAsked.Unsupported, ask(failed(ResponseErrorCode.MethodNotFound)))
        assertEquals(ByAsked.Failed, ask(failed(ResponseErrorCode.InternalError)))
        assertEquals(ByAsked.None, ask(answered(null)))
    }

    @Test
    fun `a refusal is the reason and what it would have left, and an answer with neither is not an edit`() {
        val refused = ByProjectEdit.of(ByAsked.Answer(ByProjectEditAnswer(refused = "it reads _SECRET", warnings = listOf("a.by:3"))), "by/moveSymbol")
        assertEquals(ByProjectEdit.Refused("it reads _SECRET", listOf("a.by:3")), refused)

        val edit = WorkspaceEdit(mapOf())
        assertEquals(ByProjectEdit.Edit(edit, emptyList()), ByProjectEdit.of(ByAsked.Answer(ByProjectEditAnswer(edit = edit)), "by/moveSymbol"))
        assertEquals(
            ByProjectEdit.Failed(BasedPythonBundle.message("refactoring.by.project.noEdit", "by/moveSymbol")),
            ByProjectEdit.of(ByAsked.Answer(ByProjectEditAnswer()), "by/moveSymbol"),
        )
        assertEquals(
            ByProjectEdit.Failed(BasedPythonBundle.message("refactoring.by.project.unsupported", "by/moveSymbol")),
            ByProjectEdit.of(ByAsked.Unsupported, "by/moveSymbol"),
        )
    }

    @Test
    fun `two inserts at one position are written in the order by listed them, and are two usages`() = onEdt {
        val file = fixture.configureByText("geometry.by", "import math\n")
        val at = TextRange.from(0, 0)
        val first = ByImportEdit(file, at, file, "from a import b\n", ordinal = 0)
        val second = ByImportEdit(file, at, file, "\n", ordinal = 1)
        assertNotEquals(first, second)

        WriteCommandAction.runWriteCommandAction(fixture.project) { ByImportEdit.apply(fixture.project, listOf(first, second)) }

        assertEquals("from a import b\n\nimport math\n", fixture.editor.document.text)
    }

    @Test
    fun `F6 on a name in a by file asks by for what to move rather than moving the file`() = onEdt {
        fixture.configureByText("refac.by", "def <caret>area(r: float) -> float:\n    return r\n")

        val error = assertThrows<RefactoringErrorHintException> { fixture.performEditorAction("Move") }
        assertEquals(BasedPythonBundle.message("refactoring.by.noServer"), error.message)
    }

    @Test
    fun `the symbol mover leaves a file, and anything outside an editor, to the file movers`() = onEdt {
        val file = fixture.configureByText("refac.by", "def <caret>area(r: float) -> float:\n    return r\n")
        val handler = MoveHandlerDelegate.EP_NAME.findExtensionOrFail(ByMoveSymbolHandler::class.java)

        assertFalse(handler.tryToMove(file, fixture.project, null, null, fixture.editor))
        assertFalse(handler.tryToMove(file.findElementAt(fixture.caretOffset)!!, fixture.project, null, null, null))
        assertFalse(handler.canMove(arrayOf(file), null, null))
    }
}

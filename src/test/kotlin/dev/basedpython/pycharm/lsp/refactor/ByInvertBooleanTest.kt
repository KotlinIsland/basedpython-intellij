package dev.basedpython.pycharm.lsp.refactor

import com.intellij.refactoring.invertBoolean.InvertBooleanDelegate
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByInvertBooleanParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.experimental
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Refactor | Invert Boolean in a `.by` file: offered through the platform's own action by the
 * language's delegate, which runs `by`'s refactoring and leaves the platform's PSI walk nothing to do.
 */
@TestFixtures
class ByInvertBooleanTest {

    private val fixture by codeInsightFixture()

    private val source = "def ready():\n    return True\n\nif ready():\n    pass\n"

    @Test
    fun `the platform's action takes basedpython's delegate, shown on the text of a by file and nothing else`() = onEdt {
        val delegate = InvertBooleanDelegate.EP_NAME.forLanguage(BasedPythonLanguage)
        assertInstanceOf(ByInvertBooleanDelegate::class.java, delegate)
        val file = fixture.configureByText("main.by", source)
        val name = checkNotNull(file.findElementAt(source.indexOf("ready")))

        assertTrue(delegate.isVisibleOnElement(name))
        assertFalse(delegate.isVisibleOnElement(file), "a file from the Project view is not a boolean")
        // no by runs in a test, and with none there is nothing to ask
        assertFalse(delegate.isAvailableOnElement(name))
        assertNull(delegate.adjustElement(name, fixture.project, null), "nothing for the platform's processor to invert, ever")

        val text = fixture.configureByText("notes.txt", "ready")
        assertFalse(delegate.isVisibleOnElement(checkNotNull(text.findElementAt(0))))
    }

    @Test
    fun `the edit is by's for the new name, in the text on screen, and its refusal is its reason`() = onEdt {
        fixture.configureByText("main.by", source)
        val inverted = WorkspaceEdit(mapOf("file:///a.by" to listOf(TextEdit(Range(Position(0, 4), Position(0, 9)), "not_ready"))))
        val client = AnsweringClient(fixture.project, initializeResult = experimental(ByExperimental.INVERT_BOOLEAN)) { answered(inverted) }
        val refusing = AnsweringClient(fixture.project, initializeResult = experimental(ByExperimental.INVERT_BOOLEAN)) {
            failed(ResponseErrorCode.RequestFailed, "`ready` returns a value that is not a bool")
        }

        val edited = ByInvertBoolean.edit(site(client), "not_ready")
        val refused = ByInvertBoolean.edit(site(refusing), "not_ready")

        assertEquals(ByProjectEdit.Edit(inverted, emptyList()), edited)
        val sent = client.sentParams.single() as ByInvertBooleanParams
        assertEquals("not_ready", sent.newName)
        assertEquals(Position(0, 4), sent.position)
        assertEquals(ByTextHash.of(source), sent.textHash)
        assertEquals(ByProjectEdit.Refused("`ready` returns a value that is not a bool", emptyList()), refused)
    }

    private fun site(client: AnsweringClient) = ByRefactoringSite(
        client,
        fixture.project,
        fixture.file.virtualFile,
        fixture.editor.document,
        TextDocumentIdentifier("file:///a.by"),
        Position(0, 4),
    )
}

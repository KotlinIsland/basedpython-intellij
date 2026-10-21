package dev.basedpython.pycharm.editor.unwrap

import com.intellij.codeInsight.unwrap.LanguageUnwrappers
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByUnwrapOption
import dev.basedpython.pycharm.lsp.ext.ByUnwrapResponse
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Unwrap/Remove decides nothing itself: the options and their edits are `by`'s. What is left here is
 * handing them to the platform's popup — the title, what is marked deleted and what kept — applying
 * an option's edits to the text they were worked out for and no other, and telling the answers of a
 * `by` apart.
 */
@TestFixtures
class ByUnwrapDescriptorTest {

    private val fixture by codeInsightFixture()

    private val source = "if x:\n    a()\nb()\n"

    /** `Unwrap 'if...'` on [source], as `by` answers it: the header deleted, the suite dedented. */
    private val unwrapIf = ByUnwrapOption(
        title = "Unwrap 'if...'",
        range = Range(Position(0, 0), Position(1, 7)),
        kept = listOf(Range(Position(1, 4), Position(1, 7))),
        edits = listOf(TextEdit(Range(Position(0, 0), Position(1, 4)), "")),
    )

    @Test
    fun `the descriptor is registered for basedpython, so the action is enabled there`() = onEdt {
        assertInstanceOf(ByUnwrapDescriptor::class.java, LanguageUnwrappers.INSTANCE.forLanguage(BasedPythonLanguage))
    }

    @Test
    fun `an option is listed by its title and marks the statement, keeping its suite`() = onEdt {
        fixture.configureByText("a.by", source)
        val unwrapper = checkNotNull(ByUnwrapper.of(unwrapIf, fixture.file, fixture.editor.document))

        assertEquals("Unwrap 'if...'", unwrapper.getDescription(fixture.file))
        val kept = mutableListOf<PsiElement>()
        val whole = unwrapper.collectAffectedElements(fixture.file, kept)
        assertEquals("if x:\n    a()", whole.text)
        assertEquals(listOf("a()"), kept.map { it.text })
    }

    @Test
    fun `choosing an option applies its edits`() = onEdt {
        fixture.configureByText("a.by", source)
        val unwrapper = checkNotNull(ByUnwrapper.of(unwrapIf, fixture.file, fixture.editor.document))
        WriteCommandAction.runWriteCommandAction(fixture.project) { unwrapper.unwrap(fixture.editor, fixture.file) }
        assertEquals("a()\nb()\n", fixture.editor.document.text)
    }

    /** The edits are offsets into the text they were worked out for, and mean nothing in another. */
    @Test
    fun `an option is not applied to a document edited since`() = onEdt {
        fixture.configureByText("a.by", source)
        val unwrapper = checkNotNull(ByUnwrapper.of(unwrapIf, fixture.file, fixture.editor.document))
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            fixture.editor.document.insertString(0, "# edited\n")
            unwrapper.unwrap(fixture.editor, fixture.file)
        }
        assertEquals("# edited\n$source", fixture.editor.document.text)
    }

    @Test
    fun `an option by refused is not offered`() = onEdt {
        fixture.configureByText("a.by", source)
        val refused = unwrapIf.copy(edits = null, refused = "`break` in the loop's body would no longer leave this loop")
        assertNull(ByUnwrapper.of(refused, fixture.file, fixture.editor.document))
    }

    /** A range on a line the document does not have is a reply about some other text. */
    @Test
    fun `an option that does not fit the document is not offered`() = onEdt {
        fixture.configureByText("a.by", "x")
        assertNull(ByUnwrapper.of(unwrapIf, fixture.file, fixture.editor.document))
    }

    private fun ask(answer: (String) -> CompletableFuture<*>): ByUnwrap.Answer {
        var result: ByUnwrap.Answer? = null
        onEdt {
            fixture.configureByText("a.by", source)
            val client = AnsweringClient(fixture.project) { answer(it) }
            result = ByUnwrap.ask(client, fixture.file.virtualFile, fixture.editor.document, 8, 8)
        }
        return checkNotNull(result)
    }

    @Test
    fun `by's options are handed on`() {
        val response = ByUnwrapResponse(listOf(unwrapIf))
        assertEquals(ByUnwrap.Answer.Options(response), ask { answered(response) })
    }

    /** A `by` from before the request is said to be one, rather than read as nothing to unwrap. */
    @Test
    fun `a by that does not know the request is told apart`() {
        assertEquals(ByUnwrap.Answer.Unsupported, ask { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/unwrap") })
    }

    @Test
    fun `a by that declines the document is told apart from one that failed`() {
        assertEquals(ByUnwrap.Answer.Declined, ask { answered(null) })
        assertEquals(ByUnwrap.Answer.Failed, ask { failed(ResponseErrorCode.InternalError) })
    }
}

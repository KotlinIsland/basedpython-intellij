package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ext.ByCheckNameParams
import dev.basedpython.pycharm.lsp.ext.ByCheckNameResult
import dev.basedpython.pycharm.lsp.ext.ByNameSuggestionsParams
import dev.basedpython.pycharm.lsp.ext.ByNameSuggestionsResult
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.experimental
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A rename's new name as `by` judges and suggests it (`by/checkName`, `by/nameSuggestions`), asked
 * about a position in the text the document holds, of a `by` that advertises each, and nothing to go
 * on — not a verdict — where `by` cannot say.
 */
@TestFixtures
class ByRenameNamesTest {

    private val fixture by codeInsightFixture()

    private val source = "rate = 0.2\nprint(rate)\n"

    private val checks = experimental(ByExperimental.CHECK_NAME)
    private val suggests = experimental(ByExperimental.NAME_SUGGESTIONS)

    private suspend fun verdict(client: AnsweringClient, newName: String): ByNameVerdict {
        val file = fixture.configureByText("main.by", source).virtualFile
        val document = fixture.editor.document
        return withContext(Dispatchers.Default) {
            readAction { ByRenameNames.validate(client, fixture.project, file, document, source.indexOf("rate", 5), newName) }
        }
    }

    @Test
    fun `a name by refuses is refused with by's reason, about the symbol in the text it was found in`() = onEdt {
        val client = AnsweringClient(fixture.project, initializeResult = checks) { answered(ByCheckNameResult("`class` is a keyword")) }

        assertEquals(ByNameVerdict.Invalid("`class` is a keyword"), verdict(client, "class"))
        val sent = client.sentParams.single() as ByCheckNameParams
        assertEquals("class", sent.newName)
        assertEquals(Position(1, 6), sent.position)
        assertEquals(ByTextHash.of(source), sent.textHash)
    }

    @Test
    fun `a name by takes, its null answer, is valid`() = onEdt {
        val client = AnsweringClient(fixture.project, initializeResult = checks) { answered(null) }
        assertEquals(ByNameVerdict.Valid, verdict(client, "ratio"))
    }

    @Test
    fun `a by that does not advertise the request is not asked, and one that fails says nothing either way`() = onEdt {
        val old = AnsweringClient(fixture.project) { answered(ByCheckNameResult("asked anyway")) }
        val failing = AnsweringClient(fixture.project, initializeResult = checks) { failed(ResponseErrorCode.InternalError) }

        assertEquals(ByNameVerdict.Unknown, verdict(old, "ratio"))
        assertEquals(0, old.requests.get())
        assertEquals(ByNameVerdict.Unknown, verdict(failing, "ratio"))
    }

    @Test
    fun `suggestions are by's, in its order, and none from a by without the request`() = onEdt {
        val file = fixture.configureByText("main.by", source).virtualFile
        val document = fixture.editor.document
        val client = AnsweringClient(fixture.project, initializeResult = suggests) { answered(ByNameSuggestionsResult(listOf("interest_rate", "fraction"))) }
        val old = AnsweringClient(fixture.project) { answered(ByNameSuggestionsResult(listOf("asked anyway"))) }

        val (names, none) = withContext(Dispatchers.Default) {
            readAction {
                ByRenameNames.suggestions(client, fixture.project, file, document, 0) to
                    ByRenameNames.suggestions(old, fixture.project, file, document, 0)
            }
        }

        assertEquals(listOf("interest_rate", "fraction"), names)
        assertEquals(emptyList<String>(), none)
        assertEquals(0, old.requests.get())
        val sent = client.sentParams.single() as ByNameSuggestionsParams
        assertEquals(Position(0, 0), sent.position)
        assertEquals(ByTextHash.of(source), sent.textHash)
    }
}

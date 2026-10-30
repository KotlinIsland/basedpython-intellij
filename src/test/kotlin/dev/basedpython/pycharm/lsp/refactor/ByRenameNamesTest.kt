package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.application.readAction
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByRenameNameSuggestionsParams
import dev.basedpython.pycharm.lsp.ext.ByRenameNameVerdict
import dev.basedpython.pycharm.lsp.ext.ByValidateRenameNameParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
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
 * A rename's new name as `by` judges and suggests it, asked about the symbol in the text the rename
 * found it in, and nothing to go on — not a verdict — where `by` has nothing to say.
 */
@TestFixtures
class ByRenameNamesTest {

    private val fixture by codeInsightFixture()

    private val source = "rate = 0.2\nprint(rate)\n"

    private suspend fun verdict(client: AnsweringClient, newName: String): ByNameVerdict {
        val file = fixture.configureByText("main.by", source).virtualFile
        val document = fixture.editor.document
        return withContext(Dispatchers.Default) {
            readAction { ByRenameNames.validate(client, fixture.project, file, document, source.indexOf("rate", 5), newName) }
        }
    }

    @Test
    fun `a name by refuses is refused with by's reason, about the symbol in the text it was found in`() = onEdt {
        val client = AnsweringClient(fixture.project) { answered(ByRenameNameVerdict(valid = false, message = "`class` is a keyword")) }

        assertEquals(ByNameVerdict.Invalid("`class` is a keyword"), verdict(client, "class"))
        val sent = client.sentParams.single() as ByValidateRenameNameParams
        assertEquals("class", sent.newName)
        assertEquals(Position(1, 6), sent.position)
        assertEquals(ByTextHash.of(source), sent.textHash)
    }

    @Test
    fun `a name by takes is valid`() = onEdt {
        val client = AnsweringClient(fixture.project) { answered(ByRenameNameVerdict(valid = true)) }
        assertEquals(ByNameVerdict.Valid, verdict(client, "ratio"))
    }

    @Test
    fun `a by without the request, or nothing to rename, says nothing either way`() = onEdt {
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/validateRenameName") }
        val nothing = AnsweringClient(fixture.project) { answered(null) }

        assertEquals(ByNameVerdict.Unknown, verdict(old, "ratio"))
        assertEquals(ByNameVerdict.Unknown, verdict(nothing, "ratio"))
    }

    @Test
    fun `suggestions are by's, in its order, and none from a by without the request`() = onEdt {
        val file = fixture.configureByText("main.by", source).virtualFile
        val document = fixture.editor.document
        val client = AnsweringClient(fixture.project) { answered(listOf("interest_rate", "fraction")) }
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }

        val (names, none) = withContext(Dispatchers.Default) {
            readAction {
                ByRenameNames.suggestions(client, fixture.project, file, document, 0) to
                    ByRenameNames.suggestions(old, fixture.project, file, document, 0)
            }
        }

        assertEquals(listOf("interest_rate", "fraction"), names)
        assertEquals(emptyList<String>(), none)
        assertEquals(Position(0, 0), (client.sentParams.single() as ByRenameNameSuggestionsParams).position)
    }
}

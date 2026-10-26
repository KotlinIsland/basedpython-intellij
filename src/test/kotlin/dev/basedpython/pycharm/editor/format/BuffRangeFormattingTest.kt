package dev.basedpython.pycharm.editor.format

import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reformat Selection in a `.by` file lays out only the selection, through `buff`'s
 * `textDocument/rangeFormatting`, where it used to do nothing at all.
 */
@TestFixtures
class BuffRangeFormattingTest {

    private val fixture by codeInsightFixture()

    private val text = "import os,sys\n\ndef a( x ):\n    return x+1\n\ndef b( y ):\n    return y*2\n"

    @Test
    fun `the service formats fragments, so a selection is handed to it as a range`() {
        assertTrue(FormattingService.Feature.FORMAT_FRAGMENTS in BuffFormattingService().features)
    }

    @Test
    fun `each range is asked about as lines and columns, and only its edits are applied`() = onEdt {
        val file = fixture.configureByText("fmt.by", text).virtualFile
        val b = text.indexOf("def b")
        val client = AnsweringClient(fixture.project) {
            answered(listOf(TextEdit(Range(Position(4, 0), Position(6, 14)), "\n\ndef b(y):\n    return y * 2")))
        }

        val edits = BuffRangeFormatting.edits(client, file, text, listOf(TextRange(b, text.length)), FormattingOptions(4, true))!!

        val params = client.sentParams.single() as DocumentRangeFormattingParams
        assertEquals(Range(Position(5, 0), Position(7, 0)), params.range)
        assertEquals(
            "import os,sys\n\ndef a( x ):\n    return x+1\n\n\ndef b(y):\n    return y * 2\n",
            ByCleanup.applyEditsTo(text, edits),
        )
    }

    @Test
    fun `a buff that does not answer is a failure, not a file already laid out`() = onEdt {
        val file = fixture.configureByText("fmt.by", text).virtualFile
        val client = AnsweringClient(fixture.project) { failed(ResponseErrorCode.InternalError) }

        assertNull(BuffRangeFormatting.edits(client, file, text, listOf(TextRange(0, 5)), FormattingOptions(4, true)))
    }
}

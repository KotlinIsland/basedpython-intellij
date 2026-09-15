package dev.basedpython.pycharm.lsp.inlay

import dev.basedpython.pycharm.lsp.ext.ByAlignmentGroup
import dev.basedpython.pycharm.lsp.ext.ByAlignmentGroupsParams
import dev.basedpython.pycharm.lsp.ext.ByAlignmentMember
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The plugin's half of `by/alignmentGroups` on the wire. */
class ByAlignmentGroupsWireTest {

    /** lsp4j's own Gson, so this is the json the plugin will actually put on the wire. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    @Test
    fun `the params are the shape the server accepts`() {
        // `AlignmentGroupsParams` is `deny_unknown_fields`, and refuses a request without `tabSize`.
        val params = ByAlignmentGroupsParams(
            textDocument = TextDocumentIdentifier("file:///p/main.by"),
            range = Range(Position(0, 0), Position(5, 0)),
            tabSize = 4,
        )
        assertEquals(
            """{"textDocument":{"uri":"file:///p/main.by"},"range":{"start":{"line":0,"character":0},""" +
                """"end":{"line":5,"character":0}},"tabSize":4}""",
            gson.toJson(params),
        )
    }

    /**
     * Captured from `by server` for `名前 = [1, 2]` over `abcd = 1` at a tab size of four: two UTF-16
     * units before the first gap, four display columns.
     */
    @Test
    fun `the answer reads as the server writes it`() {
        val json = """{"members":[""" +
            """{"gapEnd":{"character":3,"line":0},"gapEndColumn":5,"gapStart":{"character":2,"line":0},"gapStartColumn":4},""" +
            """{"gapEnd":{"character":5,"line":1},"gapEndColumn":5,"gapStart":{"character":4,"line":1},"gapStartColumn":4}]}"""
        assertEquals(
            ByAlignmentGroup(
                listOf(
                    ByAlignmentMember(Position(0, 2), Position(0, 3), gapStartColumn = 4, gapEndColumn = 5),
                    ByAlignmentMember(Position(1, 4), Position(1, 5), gapStartColumn = 4, gapEndColumn = 5),
                ),
            ),
            gson.fromJson(json, ByAlignmentGroup::class.java),
        )
    }
}

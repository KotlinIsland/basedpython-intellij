package dev.basedpython.pycharm.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentSymbolParams
import dev.basedpython.pycharm.lsp.ext.ByNamedSemanticTokensParams
import dev.basedpython.pycharm.lsp.ext.ByNamedTypeHierarchyPrepareParams
import dev.basedpython.pycharm.lsp.ext.BySuperMembersParams
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * The hash that names a request's text, held to the values `ty_server`'s `asked_text.rs` is held to,
 * so that the two ends agree by definition rather than by accident — a hash one bit out is a request
 * `by` holds until it gives up.
 */
class ByTextHashTest {

    @Test
    fun `known values`() {
        assertEquals("cbf29ce484222325", ByTextHash.of(""))
        assertEquals("089be207b544f1e4", ByTextHash.of("a"))
        // a character outside the basic plane is its two surrogates, as a Document holds it
        assertEquals("3a3901ff4b1faf17", ByTextHash.of("é𝄞\n"))
    }

    @Test
    fun `line endings hash alike`() {
        val unix = ByTextHash.of("a\nb\n")
        assertEquals(unix, ByTextHash.of("a\r\nb\r\n"))
        assertEquals(unix, ByTextHash.of("a\rb\r"))
        assertNotEquals(unix, ByTextHash.of("a\n\nb\n"))
        // `\r\n\n` is two line endings, not one
        assertEquals(ByTextHash.of("a\n\nb"), ByTextHash.of("a\r\n\nb"))
    }

    @Test
    fun `a byte order mark is part of the text`() {
        assertNotEquals(ByTextHash.of("x = 1\n"), ByTextHash.of("﻿x = 1\n"))
    }

    /**
     * The params of a request as lsp4j's own serializer puts the whole message on the wire, read
     * back as json so that the order of the fields does not matter.
     */
    private fun paramsOnTheWire(method: String, params: Any): JsonElement {
        val message = MessageJsonHandler(emptyMap()).serialize(
            RequestMessage().apply {
                id = "1"
                this.method = method
                this.params = params
            },
        )
        return JsonParser.parseString(message).asJsonObject.get("params")
    }

    private val document = TextDocumentIdentifier("file:///main.by")

    private val expected: JsonElement =
        JsonParser.parseString("""{"textDocument":{"uri":"file:///main.by"},"textHash":"cbf29ce484222325"}""")

    @Test
    fun `a standard request carries the hash beside its own params`() {
        assertEquals(
            expected,
            paramsOnTheWire("textDocument/semanticTokens/full", ByNamedSemanticTokensParams(document, "cbf29ce484222325")),
        )
        assertEquals(
            expected,
            paramsOnTheWire("textDocument/documentSymbol", ByNamedDocumentSymbolParams(document, "cbf29ce484222325")),
        )
    }

    @Test
    fun `the outline request carries it too`() {
        assertEquals(expected, paramsOnTheWire("by/syntaxOutline", BySyntaxOutlineParams(document, "cbf29ce484222325")))
    }

    /** Go to Super's requests after the outline, which read positions in the same text. */
    @Test
    fun `a request at a position carries it beside the position`() {
        val atPosition = JsonParser.parseString(
            """{"textDocument":{"uri":"file:///main.by"},"position":{"line":3,"character":8},"textHash":"cbf29ce484222325"}""",
        )
        assertEquals(
            atPosition,
            paramsOnTheWire(
                "textDocument/prepareTypeHierarchy",
                ByNamedTypeHierarchyPrepareParams(document, Position(3, 8), "cbf29ce484222325"),
            ),
        )
        assertEquals(
            atPosition,
            paramsOnTheWire("by/superMembers", BySuperMembersParams(document, Position(3, 8), "cbf29ce484222325")),
        )
    }
}

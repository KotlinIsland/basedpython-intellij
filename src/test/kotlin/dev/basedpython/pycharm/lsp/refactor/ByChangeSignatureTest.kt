package dev.basedpython.pycharm.lsp.refactor

import dev.basedpython.pycharm.lsp.ext.ByAnnotationEdit
import dev.basedpython.pycharm.lsp.ext.ByChangeSignatureParams
import dev.basedpython.pycharm.lsp.ext.ByChangedParameter
import dev.basedpython.pycharm.lsp.ext.ByParameterKind
import dev.basedpython.pycharm.lsp.ext.BySignature
import dev.basedpython.pycharm.lsp.ext.BySignatureParameter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Change Signature's table: what it starts from, what it sends `by`, and the signature it shows. */
class ByChangeSignatureTest {

    private val scale = BySignature(
        name = "scale",
        parameters = listOf(
            BySignatureParameter("width", "positionalOrKeyword", null, "float"),
            BySignatureParameter("height", "positionalOrKeyword", "1", "float"),
            BySignatureParameter("factor", "keywordOnly", "1.0", "float"),
        ),
    )

    @Test
    fun `the table starts as the parameters by lists, each the parameter it was`() {
        val rows = ByParameterRow.of(scale)
        assertEquals(listOf("width", "height", "factor"), rows.map { it.oldName })
        assertEquals(listOf("", "1", "1.0"), rows.map { it.default })
        assertEquals(listOf("float", "float", "float"), rows.map { it.annotation })
        assertEquals(
            listOf(
                ByChangedParameter("width", "width", "positionalOrKeyword"),
                ByChangedParameter("height", "height", "positionalOrKeyword", default = "1"),
                ByChangedParameter("factor", "factor", "keywordOnly", default = "1.0"),
            ),
            rows.map { it.changed() },
        )
    }

    @Test
    fun `a renamed parameter keeps the name it had, and only a new one is sent a value for calls`() {
        val rows = ByParameterRow.of(scale)
        rows[2].name = " k "
        val unit = ByParameterRow("unit", null, ByParameterKind.KeywordOnly, " ", "\"m\"", null)
        rows[0].value = "ignored"

        assertEquals(ByChangedParameter("k", "factor", "keywordOnly", default = "1.0"), rows[2].changed())
        assertEquals(ByChangedParameter("unit", null, "keywordOnly", default = null, value = "\"m\""), unit.changed())
        assertEquals(null, rows[0].changed().value)
    }

    @Test
    fun `the signature is written as a def writes it, with the markers its kinds need`() {
        assertEquals("scale(width: float, height: float = 1, *, factor: float = 1.0)", signaturePreview("scale", ByParameterRow.of(scale)))

        fun row(name: String, kind: ByParameterKind, default: String = "") = ByParameterRow(name, name, kind, default, "", null)
        assertEquals(
            "f(a, /, b, *args, c=2, **kw)",
            signaturePreview(
                "f",
                listOf(
                    row("a", ByParameterKind.PositionalOnly),
                    row("b", ByParameterKind.PositionalOrKeyword),
                    row("args", ByParameterKind.Variadic),
                    row("c", ByParameterKind.KeywordOnly, "2"),
                    row("kw", ByParameterKind.VariadicKeyword),
                ),
            ),
        )
    }

    /** What [params] is on the wire, as lsp4j writes a request's params. */
    private fun wire(params: Any): String = com.google.gson.JsonParser.parseString(
        org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(emptyMap()).serialize(
            org.eclipse.lsp4j.jsonrpc.messages.RequestMessage().apply { id = "1"; method = "by/changeSignature"; this.params = params },
        ),
    ).asJsonObject.get("params").toString()

    @Test
    fun `a name, a return annotation and an annotation are sent only where they change, a removal as null`() {
        val document = org.eclipse.lsp4j.TextDocumentIdentifier("file:///s.by")
        val position = org.eclipse.lsp4j.Position(0, 4)
        val kept = ByParameterRow("width", "width", ByParameterKind.PositionalOrKeyword, "", "", "int")
        val typed = ByParameterRow("height", "height", ByParameterKind.PositionalOrKeyword, "", "", "int").apply { annotation = "float" }
        val untyped = ByParameterRow("depth", "depth", ByParameterKind.PositionalOrKeyword, "", "", "int").apply { annotation = " " }

        assertEquals(
            """{"textDocument":{"uri":"file:///s.by"},"position":{"line":0,"character":4},"parameters":[{"name":"width","oldName":"width","kind":"positionalOrKeyword"}]}""",
            wire(ByChangeSignatureParams(document, position, listOf(kept.changed()))),
            "nothing changed but the list: what an older by takes",
        )
        assertEquals(
            """{"textDocument":{"uri":"file:///s.by"},"position":{"line":0,"character":4},"parameters":[""" +
                """{"name":"height","oldName":"height","kind":"positionalOrKeyword","annotation":"float"},""" +
                """{"name":"depth","oldName":"depth","kind":"positionalOrKeyword","annotation":null}],"name":"volume","returns":null}""",
            wire(ByChangeSignatureParams(document, position, listOf(typed.changed(), untyped.changed()), "volume", ByAnnotationEdit.of("float", ""))),
        )
    }

    @Test
    fun `the preview writes annotations and the return type`() {
        val row = ByParameterRow("width", "width", ByParameterKind.PositionalOrKeyword, "1", "", "int")
        assertEquals("area(width: int = 1) -> float", signaturePreview("area", listOf(row), "float"))
    }
}

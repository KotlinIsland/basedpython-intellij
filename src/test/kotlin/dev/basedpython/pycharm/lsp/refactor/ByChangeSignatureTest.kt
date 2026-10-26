package dev.basedpython.pycharm.lsp.refactor

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
        assertEquals("scale(width, height=1, *, factor=1.0)", signaturePreview("scale", ByParameterRow.of(scale)))

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
}

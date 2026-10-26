package dev.basedpython.pycharm.lang

import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The editor's lexer: an f-string or t-string read as its parts, as PyCharm's lexer reads a `.py`'s
 * (measured in 263.7528: the same parts, at the same offsets, for the same line), and the tree's
 * lexer reading the same literal whole, to the end PEP 701 puts it at.
 */
class BasedPythonHighlightingLexerTest {

    private fun parts(src: String): List<String> {
        val lexer = BasedPythonHighlightingLexer()
        lexer.start(src, 0, src.length, 0)
        val out = mutableListOf<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) {
                out += "${src.substring(lexer.tokenStart, lexer.tokenEnd)}:${lexer.tokenType.toString().removePrefix("BY_")}"
            }
            lexer.advance()
        }
        return out
    }

    private fun wholeTokens(src: String): List<String> {
        val lexer = BasedPythonLexer()
        lexer.start(src, 0, src.length, 0)
        val out = mutableListOf<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) out += src.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    @Test
    fun `an f-string is its prefix, text, fields, conversion, spec and quotes`() = assertEquals(
        listOf(
            "f\":FSTRING_START", "hello :FSTRING_TEXT", "{:FSTRING_LBRACE", "name:IDENTIFIER", "!r:FSTRING_CONVERSION",
            "::FSTRING_FORMAT_START", ">:FSTRING_TEXT", "{:FSTRING_LBRACE", "width:IDENTIFIER", "}:FSTRING_RBRACE",
            "}:FSTRING_RBRACE", " :FSTRING_TEXT", "{{:FSTRING_ESCAPED_BRACE", "x:FSTRING_TEXT", "}}:FSTRING_ESCAPED_BRACE",
            "\":FSTRING_END",
        ),
        parts("f\"hello {name!r:>{width}} {{x}}\""),
    )

    @Test
    fun `a quote inside a field opens a string of the field's`() {
        assertEquals(
            listOf("f\":FSTRING_START", "{:FSTRING_LBRACE", "d:IDENTIFIER", "[:LBRACKET", "\"k\":STRING", "]:RBRACKET", "}:FSTRING_RBRACE", "\":FSTRING_END"),
            parts("f\"{d[\"k\"]}\""),
        )
        assertEquals(listOf("f\"{d[\"k\"]}\"", "x"), wholeTokens("f\"{d[\"k\"]}\" x"))
    }

    @Test
    fun `an f-string nested in a field is read as its parts too`() = assertEquals(
        listOf(
            "f'''':FSTRING_START".replace("''''", "'''"), "{:FSTRING_LBRACE", "f\":FSTRING_START", "{:FSTRING_LBRACE", "a:IDENTIFIER",
            "}:FSTRING_RBRACE", "\":FSTRING_END", "}:FSTRING_RBRACE", "''':FSTRING_END",
        ),
        parts("f'''{f\"{a}\"}'''"),
    )

    @Test
    fun `a field of a single-quoted literal may run over lines`() {
        val src = "t = f\"total: {\n    sum(x)\n} done\"\ny = 1"
        assertEquals(listOf("t", "=", src.substring(4, src.indexOf("\ny")), "y", "=", "1"), wholeTokens(src))
        assertEquals("}:FSTRING_RBRACE", parts(src).first { it.startsWith("}") })
    }

    @Test
    fun `a t-string is read as one, and a plain string is left whole`() {
        assertEquals(listOf("t\":FSTRING_START", "{:FSTRING_LBRACE", "x:IDENTIFIER", "}:FSTRING_RBRACE", "\":FSTRING_END"), parts("t\"{x}\""))
        assertEquals(listOf("r\"{x}\":STRING"), parts("r\"{x}\""))
    }

    @Test
    fun `an unclosed single-quoted f-string ends at its line`() =
        assertEquals(listOf("f\":FSTRING_START", "ab:FSTRING_TEXT", "x:IDENTIFIER"), parts("f\"ab\nx"))
}

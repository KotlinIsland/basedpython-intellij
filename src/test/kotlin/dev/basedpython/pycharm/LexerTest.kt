package dev.basedpython.pycharm

import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for [BasedPythonLexer] — no IDE fixture needed because the lexer
 * extends LexerBase and has no project/application dependencies.
 */
class LexerTest {

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun tokenize(src: String): List<Pair<IElementType, String>> {
        val lexer = BasedPythonLexer()
        lexer.start(src, 0, src.length, 0)
        val tokens = mutableListOf<Pair<IElementType, String>>()
        while (lexer.tokenType != null) {
            tokens += lexer.tokenType!! to src.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    /** Return only non-whitespace tokens for assertions that care about token kinds. */
    private fun tokenTypes(src: String): List<IElementType> =
        tokenize(src).filterNot { it.first == BasedPythonTokenTypes.WHITESPACE }.map { it.first }

    private fun tokenTexts(src: String): List<String> =
        tokenize(src).filterNot { it.first == BasedPythonTokenTypes.WHITESPACE }.map { it.second }

    // -------------------------------------------------------------------------
    // keywords
    // -------------------------------------------------------------------------

    @Test
    fun `standard Python keywords are KEYWORD tokens`() {
        val src = "def class return if else for while import from as"
        val types = tokenTypes(src)
        assertEquals(10, types.size)
        types.forEach { assertEquals(BasedPythonTokenTypes.KEYWORD, it) }
    }

    @Test
    fun `basedpython extra keywords are KEYWORD tokens`() {
        val extras = listOf("final", "override", "abstract", "static", "protocol",
            "let", "newtype", "public", "private", "data", "frozen", "enum")
        val src = extras.joinToString(" ")
        val types = tokenTypes(src)
        assertEquals(extras.size, types.size)
        types.forEach { assertEquals("token should be KEYWORD", BasedPythonTokenTypes.KEYWORD, it) }
    }

    @Test
    fun `identifiers are not confused with keywords`() {
        // words that start with keyword prefixes
        val src = "define classes returning"
        val types = tokenTypes(src)
        assertEquals(3, types.size)
        types.forEach { assertEquals(BasedPythonTokenTypes.IDENTIFIER, it) }
    }

    @Test
    fun `match and case are keywords`() {
        val types = tokenTypes("match case")
        assertEquals(listOf(BasedPythonTokenTypes.KEYWORD, BasedPythonTokenTypes.KEYWORD), types)
    }

    // -------------------------------------------------------------------------
    // operators — basedpython extras
    // -------------------------------------------------------------------------

    @Test
    fun `optional chaining operator tokenises as OPERATOR`() {
        // x?.attr
        val tokens = tokenize("x?.attr")
        val ops = tokens.filter { it.second == "?." }
        assertEquals(1, ops.size)
        assertEquals(BasedPythonTokenTypes.OPERATOR, ops[0].first)
    }

    @Test
    fun `none coalesce operator tokenises as OPERATOR`() {
        val tokens = tokenize("x??y")
        val ops = tokens.filter { it.second == "??" }
        assertEquals(1, ops.size)
        assertEquals(BasedPythonTokenTypes.OPERATOR, ops[0].first)
    }

    @Test
    fun `arrow operator tokenises as OPERATOR`() {
        val tokens = tokenize("def f() -> None:")
        val arrows = tokens.filter { it.second == "->" }
        assertEquals(1, arrows.size)
        assertEquals(BasedPythonTokenTypes.OPERATOR, arrows[0].first)
    }

    @Test
    fun `walrus operator tokenises as OPERATOR`() {
        val tokens = tokenize("x:=1")
        val walrus = tokens.filter { it.second == ":=" }
        assertEquals(1, walrus.size)
        assertEquals(BasedPythonTokenTypes.OPERATOR, walrus[0].first)
    }

    // -------------------------------------------------------------------------
    // numbers
    // -------------------------------------------------------------------------

    @Test
    fun `plain integer is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("42"))
    }

    @Test
    fun `float is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("3.14"))
    }

    @Test
    fun `integer with underscores is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("1_000_000"))
        assertEquals("1_000_000", tokenTexts("1_000_000")[0])
    }

    @Test
    fun `float with underscores is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("1_0.5_0"))
    }

    @Test
    fun `hex literal is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("0xDEAD_BEEF"))
    }

    @Test
    fun `octal literal is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("0o777"))
    }

    @Test
    fun `binary literal is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("0b1010_1010"))
    }

    @Test
    fun `scientific notation float is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("1.5e-10"))
    }

    @Test
    fun `complex literal is NUMBER`() {
        assertEquals(listOf(BasedPythonTokenTypes.NUMBER), tokenTypes("3j"))
    }

    // -------------------------------------------------------------------------
    // strings
    // -------------------------------------------------------------------------

    @Test
    fun `double-quoted string is STRING`() {
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes("\"hello\""))
    }

    @Test
    fun `single-quoted string is STRING`() {
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes("'world'"))
    }

    @Test
    fun `triple double-quoted string is STRING`() {
        val src = "\"\"\"multi\nline\"\"\""
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `triple single-quoted string is STRING`() {
        val src = "'''also\nmulti'''"
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `f-string prefix is recognised`() {
        val src = "f\"hello {name}\""
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `raw string prefix is recognised`() {
        val src = "r\"raw\\n\""
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `byte string prefix is recognised`() {
        val src = "b'bytes'"
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `uppercase F prefix is recognised`() {
        val src = "F\"upper {x}\""
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    @Test
    fun `triple f-string is STRING`() {
        val src = "f\"\"\"multi {x} line\"\"\""
        assertEquals(listOf(BasedPythonTokenTypes.STRING), tokenTypes(src))
    }

    // -------------------------------------------------------------------------
    // comments
    // -------------------------------------------------------------------------

    @Test
    fun `hash comment is COMMENT token`() {
        val types = tokenTypes("# this is a comment")
        assertEquals(listOf(BasedPythonTokenTypes.COMMENT), types)
    }

    @Test
    fun `comment does not consume newline`() {
        val src = "x # note\ny"
        val tokens = tokenize(src)
        // comment text should stop before newline
        val comment = tokens.first { it.first == BasedPythonTokenTypes.COMMENT }
        assertEquals("# note", comment.second)
    }

    // -------------------------------------------------------------------------
    // punctuation / brackets
    // -------------------------------------------------------------------------

    @Test
    fun `brackets are their own token types`() {
        val src = "()[]{},"
        val relevant = tokenize(src).filterNot { it.first == BasedPythonTokenTypes.WHITESPACE }
        assertEquals(BasedPythonTokenTypes.LPAREN,   relevant[0].first)
        assertEquals(BasedPythonTokenTypes.RPAREN,   relevant[1].first)
        assertEquals(BasedPythonTokenTypes.LBRACKET, relevant[2].first)
        assertEquals(BasedPythonTokenTypes.RBRACKET, relevant[3].first)
        assertEquals(BasedPythonTokenTypes.LBRACE,   relevant[4].first)
        assertEquals(BasedPythonTokenTypes.RBRACE,   relevant[5].first)
        assertEquals(BasedPythonTokenTypes.COMMA,    relevant[6].first)
    }

    @Test
    fun `colon is COLON token`() {
        val tokens = tokenize(":")
        assertEquals(BasedPythonTokenTypes.COLON, tokens[0].first)
    }

    @Test
    fun `dot is DOT token`() {
        val tokens = tokenize(".")
        assertEquals(BasedPythonTokenTypes.DOT, tokens[0].first)
    }

    // -------------------------------------------------------------------------
    // representative snippet
    // -------------------------------------------------------------------------

    @Test
    fun `representative basedpython snippet tokenises correctly`() {
        val src = """
            final class Foo:
                let x: int = 1_000
                def greet(self, name: str = "world") -> None:
                    msg = f"hello {name}"
                    val = self?.inner ?? "default"
                    # done
        """.trimIndent()

        val tokens = tokenize(src)
        // Must not throw, and token span must cover full buffer
        val last = tokens.last()
        val reconstructed = tokens.joinToString("") { it.second }
        assertEquals(src, reconstructed)
    }

    @Test
    fun `token spans reconstruct source exactly`() {
        val src = "def add(a: int, b: int) -> int:\n    return a + b\n"
        val reconstructed = tokenize(src).joinToString("") { it.second }
        assertEquals(src, reconstructed)
    }
}

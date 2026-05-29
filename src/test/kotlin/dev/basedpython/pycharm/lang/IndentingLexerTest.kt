package dev.basedpython.pycharm.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.parser.BasedPythonIndentingLexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [BasedPythonIndentingLexer]. It extends LexerBase with no IDE
 * dependencies, so it can be driven directly. We assert exact placement of the synthetic
 * INDENT / DEDENT / STATEMENT_BREAK tokens, and that concatenating the *real* token text
 * (excluding synthetics, whose spans are zero-width or cover the newline) reproduces input.
 */
class IndentingLexerTest {

    private val INDENT = BasedPythonTokenTypes.INDENT
    private val DEDENT = BasedPythonTokenTypes.DEDENT
    private val BREAK = BasedPythonTokenTypes.STATEMENT_BREAK

    private data class Tk(val type: IElementType, val text: String)

    private fun lex(src: String): List<Tk> {
        val lexer = BasedPythonIndentingLexer()
        lexer.start(src, 0, src.length, 0)
        val out = mutableListOf<Tk>()
        var guard = 0
        while (lexer.tokenType != null) {
            out += Tk(lexer.tokenType!!, src.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
            if (guard++ > 100_000) error("lexer did not terminate")
        }
        return out
    }

    /** Sequence of just the structural tokens, in order. */
    private fun structure(src: String): List<IElementType> =
        lex(src).map { it.type }.filter { it === INDENT || it === DEDENT || it === BREAK }

    /** Count of a given structural token. */
    private fun count(src: String, t: IElementType): Int = structure(src).count { it === t }

    // ------------------------------------------------------------------
    // round-trip: real (non-synthetic) tokens reproduce the source
    // ------------------------------------------------------------------

    /** INDENT/DEDENT are zero-width; STATEMENT_BREAK covers the real newline run. */
    private fun reconstruct(src: String): String =
        lex(src).filterNot { it.type === INDENT || it.type === DEDENT }.joinToString("") { it.text }

    @Test
    fun `simple suite produces one indent and matching dedent`() {
        val src = "def f():\n    pass\n"
        val s = structure(src)
        // def line BREAK, INDENT, pass line BREAK, DEDENT
        assertEquals(listOf(BREAK, INDENT, BREAK, DEDENT), s)
    }

    @Test
    fun `nested suites nest indents`() {
        val src = "class C:\n    def m():\n        pass\n"
        assertEquals(2, count(src, INDENT))
        assertEquals(2, count(src, DEDENT))
    }

    @Test
    fun `dedent to zero closes all blocks`() {
        val src = "def f():\n    pass\nx = 1\n"
        val s = structure(src)
        // f BREAK, INDENT, pass BREAK, DEDENT, x BREAK
        assertEquals(listOf(BREAK, INDENT, BREAK, DEDENT, BREAK), s)
    }

    @Test
    fun `multiple dedents at once`() {
        val src = "class C:\n    def m():\n        pass\ny = 2\n"
        // Two blocks opened, both closed before `y`.
        assertEquals(2, count(src, INDENT))
        assertEquals(2, count(src, DEDENT))
        val s = structure(src)
        // last two structural tokens before final break: DEDENT DEDENT then BREAK
        val idxY = s.size - 1
        assertEquals(BREAK, s[idxY])
        assertEquals(DEDENT, s[idxY - 1])
        assertEquals(DEDENT, s[idxY - 2])
    }

    @Test
    fun `blank lines are ignored for indentation`() {
        val src = "def f():\n\n    pass\n\n"
        assertEquals(1, count(src, INDENT))
        assertEquals(1, count(src, DEDENT))
        // exactly two real statements → but only def + pass have content → 2 breaks
        assertEquals(2, count(src, BREAK))
    }

    @Test
    fun `full-line comments are ignored for indentation`() {
        val src = "def f():\n    # a comment\n    pass\n"
        assertEquals(1, count(src, INDENT))
        assertEquals(1, count(src, DEDENT))
    }

    @Test
    fun `implicit joining inside parens suppresses newlines`() {
        val src = "x = foo(\n    a,\n    b,\n)\n"
        // The whole call is one logical line → exactly one STATEMENT_BREAK, no indent/dedent.
        assertEquals(0, count(src, INDENT))
        assertEquals(0, count(src, DEDENT))
        assertEquals(1, count(src, BREAK))
    }

    @Test
    fun `implicit joining inside brackets suppresses newlines`() {
        val src = "x = [\n    1,\n    2,\n]\n"
        assertEquals(1, count(src, BREAK))
        assertEquals(0, count(src, INDENT))
    }

    @Test
    fun `backslash line continuation joins lines`() {
        val src = "x = 1 + \\\n    2\n"
        assertEquals(1, count(src, BREAK))
        assertEquals(0, count(src, INDENT))
    }

    @Test
    fun `trailing no newline at EOF still closes line and blocks`() {
        val src = "def f():\n    pass"
        val s = structure(src)
        // f BREAK, INDENT, pass BREAK (synthetic at EOF), DEDENT (synthetic at EOF)
        assertEquals(listOf(BREAK, INDENT, BREAK, DEDENT), s)
    }

    @Test
    fun `tabs and spaces both count as indentation`() {
        val tabbed = "def f():\n\tpass\n"
        assertEquals(1, count(tabbed, INDENT))
        assertEquals(1, count(tabbed, DEDENT))
    }

    @Test
    fun `single statement gets one break and no indents`() {
        val src = "x = 1\n"
        assertEquals(listOf(BREAK), structure(src))
    }

    @Test
    fun `empty input produces no tokens`() {
        assertTrue(lex("").isEmpty())
    }

    @Test
    fun `whitespace only input produces no structural tokens`() {
        assertTrue(structure("   \n  \n").isEmpty())
    }

    // ------------------------------------------------------------------
    // round-trip assertions
    // ------------------------------------------------------------------

    @Test
    fun `real token text reconstructs simple suite`() {
        val src = "def f():\n    pass\n"
        assertEquals(src, reconstruct(src))
    }

    @Test
    fun `real token text reconstructs nested suite`() {
        val src = "class C:\n    def m(self):\n        return 1\n"
        assertEquals(src, reconstruct(src))
    }

    @Test
    fun `real token text reconstructs implicit join`() {
        val src = "x = foo(\n    a,\n    b,\n)\n"
        assertEquals(src, reconstruct(src))
    }

    @Test
    fun `real token text reconstructs trailing-no-newline`() {
        val src = "def f():\n    pass"
        assertEquals(src, reconstruct(src))
    }

    @Test
    fun `representative file reconstructs and never throws`() {
        val src = """
            import os
            from typing import List

            @decorator
            data class Point:
                x: int
                y: int

                def dist(self) -> float:
                    return (self.x ** 2 + self.y ** 2) ** 0.5

            async def main():
                pass
        """.trimIndent() + "\n"
        // Must not throw.
        assertEquals(src, reconstruct(src))
        assertTrue("should open some blocks", count(src, INDENT) >= 2)
        assertEquals("indents and dedents balance", count(src, INDENT), count(src, DEDENT))
    }

    @Test
    fun `indent and dedent tokens are zero width`() {
        val src = "def f():\n    pass\n"
        lex(src).filter { it.type === INDENT || it.type === DEDENT }.forEach {
            assertEquals("synthetic indent/dedent must be zero-width", "", it.text)
        }
    }

    @Test
    fun `plain whitespace tokens are preserved and distinct from breaks`() {
        // WHITE_SPACE (spaces between tokens) survives in the stream so text round-trips,
        // and the newline is represented by a STATEMENT_BREAK, not a WHITE_SPACE.
        val src = "x = 1\n"
        val toks = lex(src)
        assertTrue("inter-token spaces preserved", toks.any { it.type === TokenType.WHITE_SPACE && it.text == " " })
        assertTrue("newline becomes a break", toks.any { it.type === BREAK })
        assertFalse("BREAK is never WHITE_SPACE", toks.any { it.type === BREAK && it.type === TokenType.WHITE_SPACE })
    }
}

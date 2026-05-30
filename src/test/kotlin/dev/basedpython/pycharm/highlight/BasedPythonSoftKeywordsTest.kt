package dev.basedpython.pycharm.highlight

import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the contextual soft-keyword classifier that powers PyCharm-style "only colour
 * the keyword where it's actually a keyword" highlighting. Pure: snippets are lexed and the
 * decision is asserted at the index of a chosen occurrence.
 */
class BasedPythonSoftKeywordsTest {

    private fun lex(src: String): List<BasedPythonSoftKeywords.Tok> {
        val lexer = BasedPythonLexer()
        lexer.start(src, 0, src.length, 0)
        val out = ArrayList<BasedPythonSoftKeywords.Tok>()
        var t = lexer.tokenType
        while (t != null) {
            out.add(BasedPythonSoftKeywords.Tok(t, src.substring(lexer.tokenStart, lexer.tokenEnd)))
            lexer.advance()
            t = lexer.tokenType
        }
        return out
    }

    /** Decide whether the [occurrence]-th (0-based) token whose text == [word] is a keyword. */
    private fun decide(src: String, word: String, occurrence: Int = 0): Boolean {
        val toks = lex(src)
        var seen = 0
        for (i in toks.indices) {
            if (toks[i].text == word) {
                if (seen == occurrence) return BasedPythonSoftKeywords.isKeyword(toks, i)
                seen++
            }
        }
        throw AssertionError("token '$word' #$occurrence not found in: $src")
    }

    private fun assertKeyword(src: String, word: String, occurrence: Int = 0) =
        assertTrue("`$word` should be a KEYWORD in: $src", decide(src, word, occurrence))

    private fun assertIdentifier(src: String, word: String, occurrence: Int = 0) =
        assertFalse("`$word` should be demoted to identifier in: $src", decide(src, word, occurrence))

    // --- variance markers ---

    @Test fun `out is keyword in a type-parameter bracket`() {
        assertKeyword("class A[out T]:\n", "out")
        assertKeyword("class A[in out T]:\n", "out")
        assertKeyword("x: list[out int]\n", "out")
        assertKeyword("def f(d: dict[str, out int]) -> int: ...\n", "out")
    }

    @Test fun `out is an identifier outside variance position`() {
        assertIdentifier("x = out\n", "out")
        assertIdentifier("y = a[out + 1]\n", "out") // subscript expression, not variance
        assertIdentifier("z = a[out]\n", "out")
        assertIdentifier("out = 5\n", "out")
    }

    // --- modifiers ---

    @Test fun `modifiers are keywords before an introducer or declaration`() {
        assertKeyword("final def foo(): ...\n", "final")
        assertKeyword("abstract class A:\n", "abstract")
        assertKeyword("open class Foo:\n", "open")
        assertKeyword("export def f(): ...\n", "export")
        assertKeyword("data class Point:\n", "data")
        assertKeyword("frozen data class P:\n", "frozen")
        assertKeyword("frozen data class P:\n", "data")
        assertKeyword("abstract value: int\n", "abstract")   // abstract-field annotation form
        assertKeyword("static x = 1\n", "static")            // modifier before declared name
    }

    @Test fun `modifiers are identifiers in expression position`() {
        assertIdentifier("data = 5\n", "data")
        assertIdentifier("print(data)\n", "data")
        assertIdentifier("x = final\n", "final")
        assertIdentifier("obj.static\n", "static")
        assertIdentifier("enum + 1\n", "enum")
    }

    @Test fun `open used as the builtin is not a keyword`() {
        assertIdentifier("f = open(path)\n", "open")
        assertIdentifier("open('x')\n", "open")
    }

    // --- introducers ---

    @Test fun `introducers are keywords before a name`() {
        assertKeyword("protocol Drawable:\n", "protocol")
        assertKeyword("newtype UserId = int\n", "newtype")
        assertKeyword("let x = 5\n", "let")
    }

    @Test fun `introducers are identifiers otherwise`() {
        assertIdentifier("let = 5\n", "let")
        assertIdentifier("x = protocol\n", "protocol")
        assertIdentifier("d.newtype\n", "newtype")
    }

    // --- statement soft keywords: match / case ---

    @Test fun `match and case are keywords as statements ending in colon`() {
        assertKeyword("match x:\n", "match")
        assertKeyword("match (point):\n", "match")
        assertKeyword("    case 1:\n", "case")
        assertKeyword("    case _:\n", "case")
    }

    @Test fun `match and case are identifiers outside statement position`() {
        assertIdentifier("match = 5\n", "match")
        assertIdentifier("match(x)\n", "match")          // call, no trailing colon
        assertIdentifier("obj.match\n", "match")
        assertIdentifier("case = 5\n", "case")
        assertIdentifier("d = {match: 1}\n", "match")    // dict key, not first on line
    }

    // --- type alias soft keyword ---

    @Test fun `type is a keyword in alias position`() {
        assertKeyword("type Alias = int\n", "type")
        assertKeyword("type Vec[T] = list[T]\n", "type")
    }

    @Test fun `type is an identifier as builtin or value`() {
        assertIdentifier("type(x)\n", "type")
        assertIdentifier("type = 5\n", "type")
        assertIdentifier("t = type\n", "type")
    }

    // --- attribute / decorator guards ---

    @Test fun `soft keyword after dot or at-sign is never a keyword`() {
        assertIdentifier("x.data\n", "data")
        assertIdentifier("@final\ndef f(): ...\n", "final")
    }

    // --- hard keywords unaffected ---

    @Test fun `hard keywords are always keywords`() {
        assertTrue(BasedPythonSoftKeywords.isKeyword(lex("for x in y:\n"), indexOf("for x in y:\n", "in")))
        assertTrue(BasedPythonSoftKeywords.isKeyword(lex("def f(): ...\n"), indexOf("def f(): ...\n", "def")))
        assertFalse("`in` is not a registered soft keyword", BasedPythonSoftKeywords.isSoft("in"))
    }

    private fun indexOf(src: String, word: String): Int {
        val toks = lex(src)
        return toks.indexOfFirst { it.text == word }.also { require(it >= 0) }
    }
}

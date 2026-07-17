package dev.basedpython.pycharm.highlight

import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Contextual classification of basedpython *soft* keywords.
 *
 * The lexer is context-free: it emits [BasedPythonTokenTypes.KEYWORD] for every word in the
 * keyword set, including soft keywords that are also valid identifiers (`match`, `case`, `type`,
 * `data`, `out`, the modifiers, …). That over-colours code like `x = out` or `type(x)`.
 *
 * This object decides — from the surrounding token stream, the same information a parser would
 * use — whether a soft-keyword occurrence is actually acting as a keyword. The annotator demotes
 * the ones that aren't back to plain-identifier colour, mirroring how CPython's grammar treats
 * soft keywords (the tokenizer emits NAME; the parser promotes them in position).
 *
 * Pure and IDE-free so it can be unit-tested by lexing snippets.
 *
 * Rules (only return `false`/demote when clearly NOT a keyword; when ambiguous, keep keyword):
 *  - variance `out`: keyword only inside `[ … ]` and immediately followed by a type name
 *    (`[out T]`, `list[out int]`) — not `a[out]` or `a[out + 1]`. (`in` is a hard keyword.)
 *  - statement soft kws `match`/`case`: keyword only as the first token on a line whose logical
 *    line ends in `:`. `type`: first on a line and followed by `Name =` / `Name[` (alias form).
 *  - introducers `protocol`/`newtype`/`let`: keyword only when followed by an identifier (the name).
 *  - modifiers (`final`, `abstract`, `open`, `export`, …): keyword only when — skipping further
 *    modifiers on the same line — they precede a `def`/`class`/`protocol`/`newtype`/`let`
 *    introducer or a declared identifier.
 *  - any soft kw directly after `.` (attribute) or `@` (decorator name) is never a keyword.
 */
object BasedPythonSoftKeywords {

    /** Class/def-kind & binding introducers a modifier may precede. `def`/`class` are hard kws. */
    private val INTRODUCER_TARGETS = setOf("def", "class", "protocol", "newtype", "let")

    val MODIFIERS = setOf(
        "final", "override", "abstract", "static", "open", "export",
        "public", "private", "data", "frozen", "enum",
    )
    val INTRODUCERS = setOf("protocol", "newtype", "let")
    val VARIANCE = setOf("out") // `in` is a hard Python keyword, always coloured
    val STMT_SOFT = setOf("match", "case", "type")

    val ALL: Set<String> = MODIFIERS + INTRODUCERS + VARIANCE + STMT_SOFT

    fun isSoft(text: String): Boolean = text in ALL

    /** A view of a lexer token: its element type and source text. */
    data class Tok(val type: IElementType, val text: String)

    /**
     * @return `true` if the soft keyword at [index] is acting as a keyword (keep keyword colour),
     *   `false` if it should be demoted to identifier colour. Non-soft words always return `true`.
     */
    fun isKeyword(tokens: List<Tok>, index: Int): Boolean {
        val kw = tokens[index].text
        if (kw !in ALL) return true

        val prev = prevNonWs(tokens, index - 1)
        if (prev != null) {
            if (prev.type == BasedPythonTokenTypes.DOT) return false                 // x.match
            if (prev.type == BasedPythonTokenTypes.OPERATOR && prev.text == "@") return false // @final
        }

        return when (kw) {
            in VARIANCE -> squareDepth(tokens, index) > 0 &&
                nextNonWs(tokens, index + 1)?.type == BasedPythonTokenTypes.IDENTIFIER

            in STMT_SOFT -> when (kw) {
                "type" -> atLineStart(tokens, index) && typeAliasFollows(tokens, index)
                else -> atLineStart(tokens, index) && lineEndsWithColon(tokens, index)
            }

            in INTRODUCERS -> nextNonWs(tokens, index + 1)?.type == BasedPythonTokenTypes.IDENTIFIER

            in MODIFIERS -> modifierIntroducesDeclaration(tokens, index)

            else -> true
        }
    }

    // --- per-rule helpers ---

    /** `type Name = …` or `type Name[T] = …` (PEP 695-style alias). */
    private fun typeAliasFollows(tokens: List<Tok>, index: Int): Boolean {
        val nameIdx = nextNonWsIndex(tokens, index + 1) ?: return false
        if (tokens[nameIdx].type != BasedPythonTokenTypes.IDENTIFIER) return false
        val after = nextNonWs(tokens, nameIdx + 1) ?: return false
        return (after.type == BasedPythonTokenTypes.OPERATOR && after.text == "=") ||
            after.type == BasedPythonTokenTypes.LBRACKET
    }

    /** Skipping further modifiers on the same line, do we reach an introducer or declared name? */
    private fun modifierIntroducesDeclaration(tokens: List<Tok>, index: Int): Boolean {
        var k = index + 1
        while (true) {
            val idx = nextNonWsIndexSameLine(tokens, k) ?: return false
            val t = tokens[idx]
            if (t.type == BasedPythonTokenTypes.KEYWORD && t.text in MODIFIERS) { k = idx + 1; continue }
            return (t.type == BasedPythonTokenTypes.KEYWORD && t.text in INTRODUCER_TARGETS) ||
                t.type == BasedPythonTokenTypes.IDENTIFIER
        }
    }

    // --- token-stream primitives ---

    private fun isNewlineWs(t: Tok): Boolean =
        t.type == BasedPythonTokenTypes.WHITESPACE && (t.text.contains('\n') || t.text.contains('\r'))

    private fun prevNonWs(tokens: List<Tok>, from: Int): Tok? {
        var k = from
        while (k >= 0) {
            if (tokens[k].type != BasedPythonTokenTypes.WHITESPACE) return tokens[k]
            k--
        }
        return null
    }

    private fun nextNonWs(tokens: List<Tok>, from: Int): Tok? {
        val i = nextNonWsIndex(tokens, from) ?: return null
        return tokens[i]
    }

    private fun nextNonWsIndex(tokens: List<Tok>, from: Int): Int? {
        var k = from
        while (k < tokens.size) {
            if (tokens[k].type != BasedPythonTokenTypes.WHITESPACE) return k
            k++
        }
        return null
    }

    /** First non-whitespace token index at or after [from], or null if a newline comes first. */
    private fun nextNonWsIndexSameLine(tokens: List<Tok>, from: Int): Int? {
        var k = from
        while (k < tokens.size) {
            val t = tokens[k]
            if (isNewlineWs(t)) return null
            if (t.type != BasedPythonTokenTypes.WHITESPACE) return k
            k++
        }
        return null
    }

    private fun atLineStart(tokens: List<Tok>, index: Int): Boolean {
        var k = index - 1
        while (k >= 0) {
            val t = tokens[k]
            if (t.type == BasedPythonTokenTypes.WHITESPACE) {
                if (isNewlineWs(t)) return true
                k--; continue // leading indentation is still line start
            }
            return false
        }
        return true // start of file
    }

    private fun lineEndsWithColon(tokens: List<Tok>, index: Int): Boolean {
        var k = index + 1
        var last: Tok? = null
        while (k < tokens.size) {
            val t = tokens[k]
            if (isNewlineWs(t)) break
            if (t.type != BasedPythonTokenTypes.WHITESPACE) last = t
            k++
        }
        return last?.type == BasedPythonTokenTypes.COLON
    }

    private fun squareDepth(tokens: List<Tok>, index: Int): Int {
        var d = 0
        for (j in 0 until index) {
            when (tokens[j].type) {
                BasedPythonTokenTypes.LBRACKET -> d++
                BasedPythonTokenTypes.RBRACKET -> if (d > 0) d--
            }
        }
        return d
    }
}

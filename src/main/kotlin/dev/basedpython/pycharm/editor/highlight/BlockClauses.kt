package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.util.TextRange

/**
 * Which clause keywords belong to one compound statement, and how far that statement reaches.
 *
 * This is the model behind both halves of the platform's "code block support" feature for `.by`:
 * the sibling-keyword highlighting ([BasedPythonKeywordHighlightUsagesHandlerFactory]) and the
 * marker/block ranges the platform navigates with ([BasedPythonCodeBlockSupportHandler]).
 *
 * The `by` server cannot answer this. LSP has no request for paired keywords — the closest,
 * `textDocument/documentHighlight`, is specified for occurrences of a *symbol*, and `by` returns
 * `null` for every keyword position (probed against `by ruff/0.0.1`). So it is computed here, from
 * document text: the PSI for `.by` is flat, and indentation is all a Python superset needs.
 *
 * The recognised constructs are exactly Python's multi-clause statements:
 *
 * ```
 * if      … elif* … else?
 * try     … except* … else? … finally?
 * for     … else?
 * while   … else?
 * match   … case+          (the one family whose clauses are indented *under* the head)
 * ```
 *
 * A clause only joins a chain when it sits at the head's indentation and its line carries a `:`
 * outside brackets and strings, which is what keeps a conditional expression's `else` (`a if b
 * else c`, wrapped over lines) and a bare `match = 1` out of it. Chains are grammar-checked rather
 * than collected by keyword set, so two adjacent `if`s, or a `try` following an `if`/`else`, stay
 * separate statements.
 */
internal object BlockClauses {

    /** One clause keyword of a compound statement, e.g. the `elif` in `if`/`elif`/`else`. */
    data class Clause(val word: String, val range: TextRange)

    /**
     * One whole compound statement: every clause keyword in source order, and [blockRange] —
     * from the head keyword to the end of the last clause's body, which is what "move to the
     * matching brace" jumps between.
     */
    data class Chain(val clauses: List<Clause>, val blockRange: TextRange)

    /**
     * The compound statement whose clause keyword covers [offset], or `null` when the offset is
     * not on such a keyword, or the keyword stands alone (a plain `if` with no `elif`/`else` is
     * not a chain — there is nothing to pair it with).
     */
    fun chainAt(text: CharSequence, offset: Int): Chain? {
        if (offset < 0 || offset > text.length) return null
        val lines = scan(text)
        val index = lines.indexOfFirst { offset >= it.leadStart && offset <= it.leadEnd }
        if (index < 0) return null
        val anchor = lines[index]

        val chain = when (anchor.word) {
            "match", "case" -> matchChain(lines, index)
            in HEADS -> chainFrom(lines, index)
            in CONTINUATIONS -> headOf(lines, index)?.let { chainFrom(lines, it) }
            else -> null
        } ?: return null

        // A chain the anchor is not part of belongs to some enclosing statement, not this keyword.
        return chain.takeIf { c -> c.clauses.any { it.range.startOffset == anchor.leadStart } }
    }

    // -------------------------------------------------------------------------
    // Chain assembly
    // -------------------------------------------------------------------------

    /** Walks back from a continuation clause to the head keyword that opened it. */
    private fun headOf(lines: List<Line>, index: Int): Int? {
        val indent = lines[index].indent
        for (j in index - 1 downTo 0) {
            val line = lines[j]
            when {
                line.indent > indent -> continue          // body of an earlier clause
                line.indent < indent -> return null       // left the statement's scope
                !line.hasColon -> return null
                line.word in HEADS -> return j
                line.word in CONTINUATIONS -> continue    // an earlier clause of the same chain
                else -> return null
            }
        }
        return null
    }

    /** Collects the clauses of the head at [headIndex], stopping where the grammar runs out. */
    private fun chainFrom(lines: List<Line>, headIndex: Int): Chain? {
        val head = lines[headIndex]
        if (!head.hasColon) return null
        val followers = FOLLOWERS[head.word] ?: return null

        val clauses = mutableListOf(head.clause())
        var seen = ""
        var last = headIndex
        var j = headIndex + 1
        while (j < lines.size) {
            val line = lines[j]
            if (line.indent > head.indent) { j++; continue }  // body line
            if (line.indent < head.indent) break              // left the statement's scope
            if (line.word !in followers || !line.hasColon) break
            if (!followsInOrder(seen, line.word)) break
            clauses += line.clause()
            seen = line.word
            last = j
            if (line.word == "finally" || (line.word == "else" && head.word != "try")) break
            j++
        }
        if (clauses.size < 2) return null
        return Chain(clauses, TextRange(head.leadStart, bodyEnd(lines, last, head.indent)))
    }

    /**
     * Whether a clause may follow the previous one. Only `try` has an order worth enforcing —
     * `except`s come first, then at most one `else`, then at most one `finally`.
     */
    private fun followsInOrder(previous: String, next: String): Boolean = when (previous) {
        "" -> true
        "elif" -> next == "elif" || next == "else"
        "except" -> next == "except" || next == "else" || next == "finally"
        // Only reachable inside a `try`: everywhere else an `else` ends the chain outright.
        "else" -> next == "finally"
        else -> false
    }

    /** `match` and its `case` clauses — the one family whose clauses are indented under the head. */
    private fun matchChain(lines: List<Line>, index: Int): Chain? {
        val matchIndex =
            (if (lines[index].word == "match") index else matchOf(lines, index)) ?: return null
        val head = lines[matchIndex]
        if (!head.hasColon) return null

        val suiteIndent = lines.getOrNull(matchIndex + 1)?.takeIf { it.indent > head.indent }?.indent
            ?: return null

        val clauses = mutableListOf(head.clause())
        var last = matchIndex
        var j = matchIndex + 1
        while (j < lines.size && lines[j].indent > head.indent) {
            val line = lines[j]
            if (line.indent == suiteIndent) {
                // Every statement directly under a `match` is a `case`; anything else means this
                // was not a match statement after all.
                if (line.word != "case" || !line.hasColon) break
                clauses += line.clause()
                last = j
            }
            j++
        }
        if (clauses.size < 2) return null
        return Chain(clauses, TextRange(head.leadStart, bodyEnd(lines, last, head.indent)))
    }

    /** Walks out from a `case` clause to the `match` that heads it. */
    private fun matchOf(lines: List<Line>, index: Int): Int? {
        val indent = lines[index].indent
        for (j in index - 1 downTo 0) {
            val line = lines[j]
            when {
                line.indent > indent -> continue                        // body of an earlier case
                line.indent == indent -> if (line.word != "case") return null else continue
                line.word == "match" -> return j
                else -> return null
            }
        }
        return null
    }

    /** End of the statement: the last line indented under the clause that starts at [lastClause]. */
    private fun bodyEnd(lines: List<Line>, lastClause: Int, headIndent: Int): Int {
        var end = lines[lastClause].contentEnd
        var j = lastClause + 1
        while (j < lines.size && lines[j].indent > headIndent) {
            end = lines[j].contentEnd
            j++
        }
        return end
    }

    // -------------------------------------------------------------------------
    // Logical lines
    // -------------------------------------------------------------------------

    /**
     * One logical line: a statement line plus any lines it continues onto inside brackets or after
     * a backslash. Blank and comment-only lines are not recorded — they belong to whatever block
     * surrounds them and must never end a chain.
     */
    private class Line(
        val indent: Int,
        val leadStart: Int,
        val leadEnd: Int,
        val word: String,
        /** A `:` outside brackets and strings — i.e. this line opens a suite. */
        val hasColon: Boolean,
        /** Just past the last significant character, comments and trailing space excluded. */
        val contentEnd: Int,
    ) {
        fun clause(): Clause = Clause(word, TextRange(leadStart, leadEnd))
    }

    private const val TAB_WIDTH = 4

    private fun scan(text: CharSequence): List<Line> {
        val lines = mutableListOf<Line>()
        val length = text.length
        var i = 0
        while (i < length) {
            var indent = 0
            while (i < length && (text[i] == ' ' || text[i] == '\t')) {
                indent += if (text[i] == '\t') TAB_WIDTH else 1
                i++
            }
            if (i >= length) break
            when {
                text[i] == '\n' || text[i] == '\r' -> { i = skipLineBreak(text, i); continue }
                text[i] == '#' -> { i = skipToLineBreak(text, i); continue }
            }

            val leadStart = i
            var leadEnd = i
            while (leadEnd < length && isWordChar(text[leadEnd])) leadEnd++
            var word = text.subSequence(leadStart, leadEnd).toString()
            var wordStart = leadStart
            if (word == "async") {
                // `async for` / `async with` / `async def`: the clause keyword is the second word.
                var k = leadEnd
                while (k < length && (text[k] == ' ' || text[k] == '\t')) k++
                var e = k
                while (e < length && isWordChar(text[e])) e++
                if (e > k) {
                    wordStart = k
                    leadEnd = e
                    word = text.subSequence(k, e).toString()
                }
            }

            var depth = 0
            var hasColon = false
            var contentEnd = leadEnd
            var backslash = false
            var k = leadStart
            while (k < length) {
                val c = text[k]
                when {
                    c == '\n' || c == '\r' -> {
                        if (depth <= 0 && !backslash) break
                        backslash = false
                        k = skipLineBreak(text, k)
                    }
                    c == ' ' || c == '\t' -> k++
                    c == '#' -> k = skipToLineBreak(text, k)
                    c == '\\' -> { backslash = true; k++ }
                    c == '"' || c == '\'' -> { k = skipString(text, k); contentEnd = k }
                    else -> {
                        when (c) {
                            '(', '[', '{' -> depth++
                            ')', ']', '}' -> if (depth > 0) depth--
                            ':' -> if (depth == 0) hasColon = true
                        }
                        backslash = false
                        k++
                        contentEnd = k
                    }
                }
            }

            lines += Line(indent, wordStart, leadEnd, word, hasColon, contentEnd)
            i = skipLineBreak(text, k)
        }
        return lines
    }

    /** Skips a string literal starting at the quote [start], returning the offset just past it. */
    private fun skipString(text: CharSequence, start: Int): Int {
        val length = text.length
        val quote = text[start]
        val triple = start + 2 < length && text[start + 1] == quote && text[start + 2] == quote
        var i = start + if (triple) 3 else 1
        while (i < length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == quote && triple ->
                    if (i + 2 < length && text[i + 1] == quote && text[i + 2] == quote) return i + 3 else i++
                c == quote -> return i + 1
                // An unterminated single-quoted string ends at the line break, as Python lexes it.
                !triple && (c == '\n' || c == '\r') -> return i
                else -> i++
            }
        }
        return length
    }

    private fun skipToLineBreak(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return skipLineBreak(text, i)
    }

    private fun skipLineBreak(text: CharSequence, from: Int): Int {
        var i = from
        if (i < text.length && text[i] == '\r') i++
        if (i < text.length && text[i] == '\n') i++
        return i
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    // -------------------------------------------------------------------------
    // Keywords
    // -------------------------------------------------------------------------

    private val HEADS = setOf("if", "for", "while", "try")
    private val CONTINUATIONS = setOf("elif", "else", "except", "finally")

    private val FOLLOWERS: Map<String, Set<String>> = mapOf(
        "if" to setOf("elif", "else"),
        "try" to setOf("except", "else", "finally"),
        "for" to setOf("else"),
        "while" to setOf("else"),
    )
}

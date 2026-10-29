package dev.basedpython.pycharm.editor.scope

import com.intellij.openapi.util.text.StringUtil
import dev.basedpython.pycharm.lsp.outline.ByOutline

/**
 * Where a scope of a `.by` file ends, and the header it echoes there.
 *
 * [end] is the offset its last statement ends at, [header] the header's offset (the line the echo
 * names and a click goes to), [column] how far that line is indented, [text] the header as written
 * without its colon, on one line, and [depth] how many scopes hold it, so the echoes of scopes that
 * end on one line can go innermost first.
 */
data class ByScopeEnd(val end: Int, val header: Int, val column: Int, val text: String, val depth: Int)

/**
 * The scope ends of a document, read off `by`'s outline of it ([ByOutline]): one for every compound
 * statement whose body is not on its header's line — a `def`, a `class`, an `if`, `for`, `while`,
 * `with`, `try` or `match`.
 *
 * A statement with several clauses is one scope: an `if` with its `elif`s and `else`, a `try` with
 * its handlers, a loop with its `else`, a `match` with its `case`s. Its echo comes after the last
 * clause's body and names the first clause's header, which is the line that opened it; the later
 * clauses are inside the one scope and get none of their own. A `case` is a clause of its `match`.
 *
 * Every offset is the outline's, so only meaningful against the text it describes, which is the
 * [text] given here.
 */
object ByScopeEnds {

    fun of(outline: ByOutline, text: CharSequence): List<ByScopeEnd> {
        val found = mutableListOf<ByScopeEnd>()
        fun visit(statements: List<ByOutline.Statement>, depth: Int) {
            for (statement in statements) {
                val first = statement.clauses.firstOrNull() ?: continue
                for (clause in statement.clauses) visit(clause.body, depth + 1)
                // a trailing lambda's clause has no keyword and no header line of its own
                if (first.keyword == null) continue
                val colon = first.colon ?: continue
                val end = statement.range.endOffset
                if (onSameLine(text, colon.startOffset, end)) continue
                val start = headerStart(text, statement, first)
                val lineStart = lineStart(text, start)
                val header = text.subSequence(start, colon.startOffset).toString()
                found += ByScopeEnd(
                    end = end,
                    header = start,
                    column = start - lineStart,
                    text = header.replace(WHITESPACE, " ").trim(),
                    depth = depth,
                )
            }
        }
        visit(outline.statements, 0)
        return found.sortedWith(compareBy({ it.end }, { -it.depth }))
    }

    /**
     * Where the header of [statement] starts. `by` starts the first clause at its keyword, after an
     * `async` or a basedpython modifier written before it on the same line, and starts the
     * statement at its first token, which on a decorated definition is the first decorator, lines
     * above. So the statement's start where it is on the header's line, and the clause's otherwise.
     */
    private fun headerStart(text: CharSequence, statement: ByOutline.Statement, first: ByOutline.Clause): Int {
        val statementStart = statement.range.startOffset
        val clauseStart = first.range.startOffset
        return if (onSameLine(text, statementStart, clauseStart)) statementStart else clauseStart
    }

    private fun onSameLine(text: CharSequence, from: Int, to: Int): Boolean =
        (from until to).none { text[it] == '\n' }

    private fun lineStart(text: CharSequence, offset: Int): Int =
        StringUtil.lastIndexOf(text, '\n', 0, offset) + 1

    private val WHITESPACE = Regex("\\s+")
}

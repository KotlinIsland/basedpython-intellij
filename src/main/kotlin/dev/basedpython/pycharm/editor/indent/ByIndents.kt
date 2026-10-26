package dev.basedpython.pycharm.editor.indent

import com.intellij.formatting.IndentInfo
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.outline.ByOutline

/**
 * Where a `.by` line's indentation belongs, and what breaking a line at an offset has to do to keep
 * the code what it was: the one model behind Enter, End on a blank line, Auto-Indent Lines and
 * smart Backspace.
 *
 * Which suite a line is in, which statement it continues and which statement ends a suite's flow
 * are `by`'s outline of [text] ([ByOutline]); a statement's extent is never guessed from the text.
 * What is lexical stays lexical: the brackets open at an offset are counted with the lexer from the
 * start of the statement the outline says the offset is in, and a comment is the lexer's token.
 *
 * The rules are PyCharm's for `.py`, measured in the same IDE: a suite is one level deeper than its
 * header; after `return`, `pass` or `raise` the next line goes back to the header's level, after
 * `break` and `continue` it does not; inside brackets a line aligns with what follows the open
 * bracket on its line, or hangs one level deeper than that line when nothing does; a closing bracket
 * on a line of its own goes back to the open bracket's line.
 *
 * [options] says how wide a level is; columns are counted with its tab size, so a tab-indented line
 * is measured by the columns it reaches.
 */
internal class ByIndents(
    private val outline: ByOutline,
    private val text: CharSequence,
    private val options: CommonCodeStyleSettings.IndentOptions,
) {

    private val unit: Int get() = options.INDENT_SIZE

    /** The whitespace that indents a line to [columns], in the file's tabs or spaces. */
    fun whitespace(columns: Int): String = IndentInfo(0, columns, 0).generateNewWhiteSpace(options)

    /**
     * What a line break at [offset] does, or null when nothing here knows and the break is the
     * platform's own.
     */
    fun lineBreak(offset: Int): LineBreak? {
        val string = outline.strings.firstOrNull { it.range.startOffset < offset && offset < it.range.endOffset }
        if (string != null) {
            // in an interpolation the line goes on inside its braces, as inside brackets
            string.interpolations.firstOrNull { it.startOffset < offset && offset < it.endOffset }?.let { braces ->
                return LineBreak(bracketIndent(braces.startOffset, offset))
            }
            // in the text, the literal is split; in a triple-quoted one the break is the text's own
            return stringBreak(offset)
        }
        val scan = scan(offset)
        scan.comment?.let { comment -> commentBreak(offset, comment)?.let { return it } }
        scan.brackets.lastOrNull()?.let { return bracketBreak(offset, it) }
        suiteOpenedAt(offset)?.let { return LineBreak(headerIndent(it) + unit) }
        return statementBreak(offset)
    }

    /**
     * The columns the line starting at [lineStart] belongs at, or null when its indentation is not
     * this model's to decide: a line inside a string, the continuation of a statement after a
     * backslash, or a comment in the first column.
     */
    fun lineIndent(lineStart: Int): Int? {
        if (outline.strings.any { it.range.startOffset < lineStart && lineStart < it.range.endOffset }) return null
        val first = skipBlanks(lineStart)
        val blank = first >= text.length || text[first] == '\n' || text[first] == '\r'
        // a comment in the first column stays there, as Python's keeps it
        if (!blank && text[first] == '#' && first == lineStart) return null
        if (!blank && text[first] != '#') {
            scan(first).brackets.lastOrNull()?.let { open ->
                return if (closes(text[open], text[first])) indentAt(open) else bracketIndent(open, lineEnd(open))
            }
            clauseStartingAt(first)?.let { return it }
            statementStartingAt(first)?.let { (statement, parent, siblings) -> return statementIndent(statement, parent, siblings) }
            // a line that starts inside a statement or a header without a bracket open: after a backslash
            if (innermostStatementAround(first) != null || headerAround(first) != null) return null
        } else {
            scan(lineStart).brackets.lastOrNull()?.let { open -> return bracketIndent(open, lineStart) }
        }
        return afterStatements(lineStart)
    }

    // ---- breaking a line ----

    /** Inside a single-quoted string's text: close it, and reopen it with the same prefix and quote on the next line. */
    private fun stringBreak(offset: Int): LineBreak? {
        val part = outline.strings.firstOrNull { it.range.startOffset < offset && offset < it.range.endOffset } ?: return null
        val shape = Quoted.of(text, part.range) ?: return null
        if (shape.triple) return null
        if (offset < shape.textStart || offset > shape.textEnd) return null
        val at = part.escapes.firstOrNull { it.startOffset < offset && offset < it.endOffset }?.startOffset ?: offset
        val quote = shape.quote.toString()
        val reopen = text.subSequence(part.range.startOffset, shape.textStart).toString()
        val open = scan(part.range.startOffset).brackets.lastOrNull()
        if (open != null) {
            return LineBreak(bracketIndent(open, at), at = at, before = quote, after = reopen)
        }
        // A string run outside any bracket can only go on to the next line inside parentheses
        // around the whole run: around one part alone, the part before would be called.
        val run = stringRun(part)
        return LineBreak(
            indent = column(run.startOffset) + 1,
            at = at,
            before = quote,
            after = reopen,
            wrap = run,
        )
    }

    /** The parts concatenated with [part]: the ones next to it with nothing but spaces between. */
    private fun stringRun(part: ByOutline.StringPart): TextRange {
        val index = outline.strings.indexOf(part)
        var first = index
        while (first > 0 && onlySpaces(outline.strings[first - 1].range.endOffset, outline.strings[first].range.startOffset)) first--
        var last = index
        while (last + 1 < outline.strings.size && onlySpaces(outline.strings[last].range.endOffset, outline.strings[last + 1].range.startOffset)) last++
        return TextRange(outline.strings[first].range.startOffset, outline.strings[last].range.endOffset)
    }

    /** Inside a comment with more of it after [offset]: the rest goes on a comment line of its own. */
    private fun commentBreak(offset: Int, comment: Int): LineBreak? {
        if (offset <= comment) return null
        val rest = text.subSequence(offset, lineEnd(offset))
        if (rest.isBlank()) return null
        var prefixEnd = comment + 1
        while (prefixEnd < text.length && (text[prefixEnd] == ' ' || text[prefixEnd] == '\t')) prefixEnd++
        val prefix = text.subSequence(comment, prefixEnd).toString().let { if (it == "#") "# " else it }
        // a comment after code goes on at the line's indentation, one on a line of its own where it is
        return LineBreak(indentAt(comment), after = prefix)
    }

    /** Inside brackets: align with what follows the bracket, or hang one level deeper. */
    private fun bracketBreak(offset: Int, open: Int): LineBreak {
        val next = skipBlanks(offset)
        val emptyPair = next < text.length && closes(text[open], text[next]) &&
            text.subSequence(open + 1, offset).isBlank()
        return if (emptyPair) {
            LineBreak(indentAt(open) + unit, closeOnOwnLine = indentAt(open))
        } else {
            LineBreak(bracketIndent(open, offset))
        }
    }

    /**
     * Anywhere else: inside a statement, a backslash carries it on to the next line; at its end, the
     * next line is where the statement after it would go.
     */
    private fun statementBreak(offset: Int): LineBreak? {
        val statement = innermostStatementAround(offset)
        if (statement != null && continuesAfter(offset, statement)) {
            if (precedingNonBlank(offset) == '\\') return null
            return LineBreak(indentAt(statement.startOffset) + unit, before = "\\")
        }
        val header = headerAround(offset)
        if (header != null && continuesAfter(offset, header)) {
            if (precedingNonBlank(offset) == '\\') return null
            return LineBreak(indentAt(header.startOffset) + unit, before = "\\")
        }
        return afterStatements(offset)?.let { LineBreak(it) }
    }

    /** Whether [range] has code both before [offset] and after it on the offset's line. */
    private fun continuesAfter(offset: Int, range: TextRange): Boolean {
        if (offset <= range.startOffset || offset >= range.endOffset) return false
        val end = minOf(range.endOffset, lineEnd(offset))
        return !text.subSequence(skipBlanks(offset), end).isBlank() &&
            !text.subSequence(range.startOffset, offset).isBlank()
    }

    // ---- where lines go ----

    /**
     * The columns of a line after the statements before [offset] and before the ones after it: the
     * level of the statement that comes next in the same suite, else the level of the one before
     * — one level up when that one is a `return`, `pass` or `raise` — and a level deeper than a
     * header whose suite is still empty.
     */
    private fun afterStatements(offset: Int): Int? {
        val previous = lastStatementBefore(offset) ?: return firstStatementAfter(offset)?.let { indentAt(it.startOffset) } ?: 0
        val (statement, parent, siblings) = previous
        statement.clauses.lastOrNull()?.let { last ->
            if (last.colon != null && last.body.isEmpty()) return headerIndent(last) + unit
        }
        val index = siblings.indexOf(statement)
        if (index + 1 < siblings.size && siblings[index + 1].range.startOffset >= offset) {
            return indentAt(siblings[index + 1].range.startOffset)
        }
        if (statement.clauses.isEmpty() && keyword(statement.range.startOffset) in SUITE_ENDERS) {
            return parent?.let(::headerIndent) ?: 0
        }
        return indentAt(statement.range.startOffset)
    }

    /** The columns a statement starting a line belongs at: its suite's level. */
    private fun statementIndent(
        statement: ByOutline.Statement,
        parent: ByOutline.Clause?,
        siblings: List<ByOutline.Statement>,
    ): Int {
        val index = siblings.indexOf(statement)
        // A header whose suite is empty claims the statement after it: the parser read the
        // statement as its sibling only because it was not indented.
        siblings.getOrNull(index - 1)?.clauses?.lastOrNull()?.let { last ->
            if (last.colon != null && last.body.isEmpty()) return headerIndent(last) + unit
        }
        if (parent == null) return 0
        val first = siblings.first()
        return if (first === statement) headerIndent(parent) + unit else indentAt(first.range.startOffset)
    }

    /**
     * A clause starting [first] that does not start its statement: a decorated definition's, at the
     * level of its decorators, or one after the first, at the first's level — or under `match` for a
     * `case`.
     */
    private fun clauseStartingAt(first: Int): Int? {
        var found: Int? = null
        outline.walk { statement, _ ->
            val clauses = statement.clauses
            for ((i, clause) in clauses.withIndex()) {
                val start = clause.keyword?.startOffset ?: clause.range.startOffset
                if (start != first || start == statement.range.startOffset) continue
                found = when {
                    i == 0 -> indentAt(statement.range.startOffset)
                    keyword(start) == "case" -> headerIndent(clauses.first()) + unit
                    else -> headerIndent(clauses.first())
                }
            }
        }
        return found
    }

    private data class Placed(
        val statement: ByOutline.Statement,
        val parent: ByOutline.Clause?,
        val siblings: List<ByOutline.Statement>,
    )

    private fun statementStartingAt(first: Int): Placed? {
        var found: Placed? = null
        walk { statement, parent, siblings -> if (statement.range.startOffset == first) found = Placed(statement, parent, siblings) }
        return found
    }

    /**
     * The deepest statement ending at or before [offset] on a line before the next statement starts
     * — the one a new line at [offset] comes after.
     */
    private fun lastStatementBefore(offset: Int): Placed? {
        var found: Placed? = null
        walk { statement, parent, siblings ->
            val end = statement.range.endOffset
            if (end > offset) return@walk
            val best = found
            // ends later, or ends together and is nested inside (a suite's last statement and its compound end together)
            if (best == null || end > best.statement.range.endOffset ||
                end == best.statement.range.endOffset && statement.range.startOffset >= best.statement.range.startOffset
            ) {
                found = Placed(statement, parent, siblings)
            }
        }
        return found
    }

    /** Where the first statement starting at or after [offset] starts, at any depth. */
    private fun firstStatementAfter(offset: Int): TextRange? {
        var found: TextRange? = null
        walk { statement, _, _ ->
            val start = statement.range.startOffset
            if (start >= offset && (found == null || start < found!!.startOffset)) found = statement.range
        }
        return found
    }

    /** The innermost simple statement whose range holds [offset]. */
    private fun innermostStatementAround(offset: Int): TextRange? {
        var found: TextRange? = null
        walk { statement, _, _ ->
            if (statement.clauses.isEmpty() && statement.range.startOffset < offset && offset < statement.range.endOffset) {
                found = statement.range
            }
        }
        return found
    }

    /** The clause header — from its keyword to its `:` — that holds [offset]. */
    private fun headerAround(offset: Int): TextRange? {
        var found: TextRange? = null
        outline.walk { statement, _ ->
            for (clause in statement.clauses) {
                val start = clause.keyword?.startOffset ?: clause.range.startOffset
                val end = clause.colon?.startOffset ?: continue
                if (start < offset && offset <= end) found = TextRange(start, end)
            }
        }
        return found
    }

    /**
     * The clause whose suite a line break at [offset] starts: its `:` is before the offset on the
     * same line, and nothing of its body comes before the offset — so `if x:|` and `if x:| return`
     * qualify, and `if x: return|` does not.
     */
    fun suiteOpenedAt(offset: Int): ByOutline.Clause? {
        val lineStart = lineStart(offset)
        var found: ByOutline.Clause? = null
        outline.walk { statement, _ ->
            for (clause in statement.clauses) {
                val colon = clause.colon ?: continue
                if (colon.endOffset > offset || colon.endOffset < lineStart) continue
                val bodyStart = clause.body.firstOrNull()?.range?.startOffset
                if (bodyStart != null && bodyStart < offset) continue
                found = clause
            }
        }
        return found
    }

    private fun headerIndent(clause: ByOutline.Clause): Int = indentAt(clause.keyword?.startOffset ?: clause.range.startOffset)

    /** Every statement with the clause whose suite it is in and its siblings there. */
    private fun walk(visit: (ByOutline.Statement, ByOutline.Clause?, List<ByOutline.Statement>) -> Unit) {
        fun walk(statements: List<ByOutline.Statement>, parent: ByOutline.Clause?) {
            for (statement in statements) {
                visit(statement, parent, statements)
                for (clause in statement.clauses) walk(clause.body, clause)
            }
        }
        walk(outline.statements, null)
    }

    // ---- brackets ----

    /** The column a line inside the bracket at [open] goes to, given the text up to [until] on its line. */
    private fun bracketIndent(open: Int, until: Int): Int {
        val content = skipBlanks(open + 1)
        val end = minOf(lineEnd(open), until)
        return if (content < end && text[content] != '#') column(content) else indentAt(open) + unit
    }

    private class Scan(val brackets: List<Int>, val comment: Int?)

    /**
     * The brackets open at [offset], innermost last, and the comment it is in, counted with the
     * lexer from the start of the statement or clause header the outline says it is in. Outside any
     * statement nothing is open.
     */
    private fun scan(offset: Int): Scan {
        val start = logicalStart(offset) ?: lineStart(offset)
        val lexer = BasedPythonLexer()
        lexer.start(text, start, offset, 0)
        val brackets = ArrayList<Int>()
        var comment: Int? = null
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            comment = null
            when (type) {
                BasedPythonTokenTypes.LPAREN, BasedPythonTokenTypes.LBRACKET, BasedPythonTokenTypes.LBRACE ->
                    brackets += lexer.tokenStart
                BasedPythonTokenTypes.RPAREN, BasedPythonTokenTypes.RBRACKET, BasedPythonTokenTypes.RBRACE ->
                    brackets.removeLastOrNull()
                BasedPythonTokenTypes.COMMENT -> if (lexer.tokenEnd == offset) comment = lexer.tokenStart
            }
            lexer.advance()
        }
        return Scan(brackets, comment)
    }

    /** Where the logical line holding [offset] starts: its statement's, or its clause header's. */
    private fun logicalStart(offset: Int): Int? {
        var siblings = outline.statements
        while (true) {
            val statement = siblings.lastOrNull { it.range.startOffset <= offset && offset <= it.range.endOffset } ?: return null
            if (statement.clauses.isEmpty()) return statement.range.startOffset
            val clause = statement.clauses.lastOrNull { it.range.startOffset <= offset } ?: return statement.range.startOffset
            val colon = clause.colon ?: return clause.range.startOffset
            if (offset < colon.endOffset) return clause.range.startOffset
            siblings = clause.body
        }
    }

    private fun closes(open: Char, close: Char): Boolean =
        open == '(' && close == ')' || open == '[' && close == ']' || open == '{' && close == '}'

    // ---- text ----

    private fun keyword(offset: Int): String {
        var end = offset
        while (end < text.length && (text[end] == '_' || text[end].isLetterOrDigit())) end++
        return text.subSequence(offset, end).toString()
    }

    private fun lineStart(offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n' && text[i - 1] != '\r') i--
        return i
    }

    private fun lineEnd(offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    private fun skipBlanks(offset: Int): Int {
        var i = offset
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    private fun precedingNonBlank(offset: Int): Char? {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return if (i >= 0) text[i] else null
    }

    private fun onlySpaces(from: Int, to: Int): Boolean =
        (from until to).all { text[it] == ' ' || text[it] == '\t' }

    /** The column [offset] is at, a tab reaching the next tab stop. */
    fun column(offset: Int): Int {
        var column = 0
        val tab = options.TAB_SIZE
        for (i in lineStart(offset) until offset) {
            column = if (text[i] == '\t' && tab > 0) (column / tab + 1) * tab else column + 1
        }
        return column
    }

    /** The columns the leading whitespace of the line holding [offset] spans. */
    fun indentAt(offset: Int): Int = column(skipBlanks(lineStart(offset)))

    /** A string part's prefix and quotes, read off its text. */
    private class Quoted(val quote: Char, val triple: Boolean, val textStart: Int, val textEnd: Int) {
        companion object {
            fun of(text: CharSequence, range: TextRange): Quoted? {
                var i = range.startOffset
                while (i < range.endOffset && text[i].isLetter()) i++
                if (i >= range.endOffset) return null
                val quote = text[i]
                if (quote != '"' && quote != '\'') return null
                val triple = i + 2 < range.endOffset && text[i + 1] == quote && text[i + 2] == quote
                val width = if (triple) 3 else 1
                val textStart = i + width
                val textEnd = range.endOffset - width
                // an unterminated literal has no closing quote to break before
                if (textEnd < textStart || text[range.endOffset - 1] != quote) return null
                return Quoted(quote, triple, textStart, textEnd)
            }
        }
    }

    /**
     * What a line break does beyond the line break itself: the columns the new line is indented
     * to, and the text that keeps the code what it was.
     */
    class LineBreak(
        /** The columns of the new line. */
        val indent: Int,
        /** Where the line is broken, when not where it was asked: before an escape it would split. */
        val at: Int? = null,
        /** Inserted before the line break: a backslash, or the closing quote of a split string. */
        val before: String = "",
        /** Inserted at the start of the new line, after its indentation: a reopened string, a comment's `#`. */
        val after: String = "",
        /** Wrapped in parentheses first, so a split string run stays one expression. */
        val wrap: TextRange? = null,
        /** Breaking between an empty bracket pair: the columns of the closing bracket's own line. */
        val closeOnOwnLine: Int? = null,
    )

    companion object {
        /** Statements after which the next line leaves the suite, as PyCharm does for `.py`. */
        private val SUITE_ENDERS = setOf("return", "pass", "raise")
    }
}

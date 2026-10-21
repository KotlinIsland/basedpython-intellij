package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.JoinLinesHandlerDelegate.CANNOT_JOIN
import com.intellij.codeInsight.editorActions.JoinRawLinesHandlerDelegate
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.StringLiteralShape
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Join Lines (Ctrl+Shift+J) in a `.by` file, joined the way the code means rather than with a space.
 *
 * The platform joins two lines by putting one space where the line break was, which in python is
 * right only inside brackets. After a backslash it leaves the backslash in the middle of the line,
 * a syntax error; two statements become one that is not a statement; a statement joined to the end
 * of a comment is commented out; and a line from the end of a suite joined to the line after the
 * suite moves it into or out of the suite. So:
 *
 *  - inside brackets, the lines join with a space, or with none after an opening bracket or before a
 *    closing one: `f(` + `x)` is `f(x)`;
 *  - after a backslash, the backslash goes, and the lines join the same way;
 *  - two string literals that run into each other are merged into one when they are the same kind
 *    of literal: `"ab"` + `"cd"` is `"abcd"`;
 *  - two simple statements of the same suite join with `; `;
 *  - a header joins with a suite of one simple statement: `if x:` + `pass` is `if x: pass`;
 *  - a comment ending the first line goes to the end of the joined one;
 *  - anything else — lines in different suites, a decorator and its definition, a header and a
 *    suite of several statements — is not joined, because no single line means the same.
 *
 * ## Why this reads `by`'s outline rather than the tokens
 *
 * Which of those cases a line break is, is a question about statements: where one ends, which suite
 * it is in, whether a `:` ends a header. That is the parser's to say, and [ByOutlines] holds what
 * `by`'s parser said about the text on screen, as the Enter key and Move Statement read it. The
 * plugin's lexer knows brackets and strings but not statements: it cannot tell a line ending a
 * suite from one continuing it, and a `.by` file's soft keywords and statement forms are the
 * parser's, not the lexer's. With no outline for the text on screen this does nothing, and the
 * platform joins the lines as it would any text.
 */
class BasedPythonJoinLinesHandler : JoinRawLinesHandlerDelegate {

    override fun tryJoinRawLines(document: Document, file: PsiFile, start: Int, end: Int): Int {
        if (file !is BasedPythonFile) return CANNOT_JOIN
        val outline = ByOutlines.getInstance(file.project).current(document) ?: return CANNOT_JOIN
        return when (val join = ByLineJoin.join(document.immutableCharSequence, outline, start, end)) {
            null -> CANNOT_JOIN
            ByLineJoin.Join.Refused -> start
            is ByLineJoin.Join.Edit -> {
                document.replaceString(join.range.startOffset, join.range.endOffset, join.replacement)
                join.caret
            }
        }
    }

    /** Everything is decided before the line break goes, in [tryJoinRawLines]. */
    override fun tryJoinLines(document: Document, file: PsiFile, start: Int, end: Int): Int = CANNOT_JOIN
}

/** How two lines of a `.by` file join, given `by`'s outline of the text. */
internal object ByLineJoin {

    sealed interface Join {
        /** Replace [range] with [replacement]; the caret goes to [caret], in the text after. */
        data class Edit(val range: TextRange, val replacement: String, val caret: Int) : Join

        /** The lines stay as they are: no single line means what the two do. */
        data object Refused : Join
    }

    /**
     * How the line ending at [start] — just past its last character that is not whitespace — joins
     * the line whose first character that is not whitespace is at [end]. `null` when it is not this
     * handler's to say: a line break inside a string literal, or a comment joining a comment.
     */
    fun join(text: CharSequence, outline: ByOutline, start: Int, end: Int): Join? {
        val firstLineStart = lineStart(text, start)
        val secondLineEnd = lineEnd(text, end)
        // a line break inside a triple-quoted string is part of the string, and joins as text
        if (outline.strings.any { it.range.startOffset < start && end < it.range.endOffset }) return null

        val firstComment = commentStart(text, outline, firstLineStart, start)
        val secondComment = commentStart(text, outline, end, secondLineEnd)
        val firstCode = firstComment?.let { trimEnd(text, firstLineStart, it) } ?: start
        val secondCode = secondComment?.let { trimEnd(text, end, it) } ?: trimEnd(text, end, secondLineEnd)
        when {
            // comments on both lines, nothing else: the platform joins comments
            firstCode == firstLineStart && secondCode == end -> return null
            // a comment would swallow the code joined after it, or it would land between comments
            firstCode == firstLineStart -> return Join.Refused
            secondCode == end -> return null
            firstComment != null && secondComment != null -> return Join.Refused
        }

        // `a = 1;` ends its statement before the `;`
        val semicolon = text[firstCode - 1] == ';'
        val statementEnd = if (semicolon) trimEnd(text, firstLineStart, firstCode - 1) else firstCode
        val (left, right, separator) = when (context(outline.statements, firstCode, statementEnd, end)) {
            Context.Expression -> expressionJoin(text, outline, firstCode, end)
            Context.Suite -> Triple(firstCode, end, " ")
            Context.Statements -> Triple(firstCode, end, if (semicolon) " " else "; ")
            Context.Apart -> return Join.Refused
        }

        if (firstComment == null) {
            return Join.Edit(TextRange(left, right), separator, left + separator.length)
        }
        // `f(a,  # note` + `b)` is `f(a, b)  # note`
        val comment = text.subSequence(firstComment, start)
        val replacement = separator + text.subSequence(right, secondCode) + "  " + comment
        return Join.Edit(TextRange(left, secondCode), replacement, left + separator.length)
    }

    /** Where a line break between [left], the end of the first line's code, and [right] is. */
    private enum class Context {
        /** Inside one statement: in brackets, after a backslash, or inside a header. */
        Expression,

        /** Between a header ending in its `:` and the one simple statement of its suite. */
        Suite,

        /** Between two simple statements of the same suite. */
        Statements,

        /** Anywhere else. */
        Apart,
    }

    /**
     * Where the line break between [left] and [right] is among [statements]; [statementEnd] is
     * [left] with a trailing `;` taken off, which is where a statement ending the line ends.
     */
    private fun context(statements: List<ByOutline.Statement>, left: Int, statementEnd: Int, right: Int): Context {
        for ((index, statement) in statements.withIndex()) {
            val range = statement.range
            if (range.startOffset < left && right < range.endOffset) {
                if (statement.clauses.isEmpty()) return Context.Expression
                val clause = statement.clauses.firstOrNull { it.range.startOffset < left && right <= it.range.endOffset }
                    ?: return Context.Apart // between two clauses, or a decorator and its definition
                val colon = clause.colon
                return when {
                    colon == null || right <= colon.startOffset -> Context.Expression
                    left == colon.endOffset -> {
                        val only = clause.body.singleOrNull()
                        val oneSimple = only != null && only.range.startOffset == right && only.clauses.isEmpty()
                        if (oneSimple) Context.Suite else Context.Apart
                    }
                    else -> context(clause.body, left, statementEnd, right)
                }
            }
            if (range.endOffset == statementEnd) {
                // the line ends this statement: the next line has to start the next one, in this suite
                val next = statements.getOrNull(index + 1)
                if (next == null || next.range.startOffset != right) return Context.Apart
                return if (statement.clauses.isEmpty() && next.clauses.isEmpty()) Context.Statements else Context.Apart
            }
        }
        return Context.Apart
    }

    /**
     * Two lines of one expression: the backslash ending the first goes, two string literals that run
     * into each other are merged, and the lines meet with a space, or none next to a bracket.
     */
    private fun expressionJoin(text: CharSequence, outline: ByOutline, firstCode: Int, end: Int): Triple<Int, Int, String> {
        var left = firstCode
        if (text[left - 1] == '\\') left = trimEnd(text, lineStart(text, left), left - 1)

        val before = outline.strings.firstOrNull { it.range.endOffset == left }
        val after = outline.strings.firstOrNull { it.range.startOffset == end }
        if (before != null && after != null) {
            val first = StringLiteralShape.of(text.subSequence(before.range.startOffset, before.range.endOffset))
            val second = StringLiteralShape.of(text.subSequence(after.range.startOffset, after.range.endOffset))
            if (first != null && second != null && mergeable(text, before.range, first, after.range, second)) {
                return Triple(before.range.startOffset + first.contentEnd, after.range.startOffset + second.contentStart, "")
            }
        }

        val separator = when {
            text[left - 1] in "([{" -> ""
            text[end] in ")]}" -> ""
            else -> " "
        }
        return Triple(left, end, separator)
    }

    /**
     * Whether two adjacent string literals can be written as one: the same quotes, one line each,
     * and the same prefix, so that every character and escape means in the merged literal what it
     * meant in its own.
     */
    private fun mergeable(
        text: CharSequence,
        firstRange: TextRange,
        first: StringLiteralShape,
        secondRange: TextRange,
        second: StringLiteralShape,
    ): Boolean {
        if (!first.isTerminated || !second.isTerminated) return false
        if (first.isTriple || second.isTriple || first.quote != second.quote) return false
        val firstPrefix = prefix(text, firstRange, first)
        val secondPrefix = prefix(text, secondRange, second)
        return firstPrefix == secondPrefix
    }

    /** The prefix letters of a literal, in lower case and in a fixed order; `u` counts for nothing. */
    private fun prefix(text: CharSequence, range: TextRange, shape: StringLiteralShape): String {
        val quotes = if (shape.isTriple) 3 else 1
        return text.subSequence(range.startOffset, range.startOffset + shape.contentStart - quotes)
            .toString().lowercase().filter { it != 'u' }.toSortedSet().joinToString("")
    }

    /** Where the comment on the line from [from] to [to] starts: its `#`, outside every string. */
    private fun commentStart(text: CharSequence, outline: ByOutline, from: Int, to: Int): Int? {
        var index = from
        while (index < to) {
            val string = outline.strings.firstOrNull { it.range.startOffset <= index && index < it.range.endOffset }
            if (string != null) {
                index = string.range.endOffset
                continue
            }
            if (text[index] == '#') return index
            index++
        }
        return null
    }

    private fun trimEnd(text: CharSequence, from: Int, to: Int): Int {
        var index = to
        while (index > from && text[index - 1].isWhitespace()) index--
        return index
    }

    private fun lineStart(text: CharSequence, offset: Int): Int {
        var index = offset
        while (index > 0 && text[index - 1] != '\n') index--
        return index
    }

    private fun lineEnd(text: CharSequence, offset: Int): Int {
        var index = offset
        while (index < text.length && text[index] != '\n') index++
        return index
    }
}

package dev.basedpython.pycharm.editor.highlight

import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Where a multiline string's incidental indentation ends — the column basedpython trims to.
 *
 * Offsets are into the document text the margin was computed from.
 */
data class StringMargin(
    /** The literal this margin belongs to, prefix and quotes included. */
    val literalStart: Int,
    val literalEnd: Int,
    /** How many leading whitespace characters come off every line of the literal's content. */
    val indent: Int,
    /** Start of the first line the margin is drawn on — the line after the opening quotes. */
    val firstLineStart: Int,
    /**
     * Start of the last line it is drawn on: the last line of *text*.
     *
     * Closing quotes on a line of their own are the margin already — they stand at the column
     * being marked, which is the whole reason that line counts towards it. The rule stops where
     * they start rather than running down beside them, so the line points at the quotes instead
     * of overshooting the literal and looking like it belongs to whatever follows.
     */
    val lastLineStart: Int,
    /**
     * Where the margin is drawn: [indent] characters into the line that *defines* it.
     *
     * A column would not be enough. Which pixel column an indent lands on depends on the
     * characters before it — a tab is one character and eight columns wide — so the line is
     * placed by asking the editor where this offset is, on a line whose leading whitespace is
     * known to be exactly the whitespace being stripped.
     */
    val anchorOffset: Int,
)

/**
 * The trim margin of every multiline string in a file: what basedpython strips, drawn where it
 * strips it.
 *
 * basedpython trims triple-quoted strings the way Java trims a text block, and that is a rule you
 * cannot see. In Python the literal *is* its own content, so the indentation you type is the
 * indentation you get and there is nothing to mark; in basedpython the leading whitespace shared
 * by every line is incidental — it belongs to the code's layout, not to the string — and it is
 * removed. Which whitespace that is depends on the *least*-indented line, so a single line moved
 * left changes what every other line contains, silently, from somewhere else in the literal. Java
 * has the same rule and IntelliJ IDEA answers it with a vertical line in the text block; this is
 * that line for `.by`.
 *
 * The rule, as JLS 3.10.6 states it for text blocks and `String::stripIndent` implements it:
 *
 * - every non-blank line of the content contributes its own indentation,
 * - blank lines contribute nothing — they are stripped entirely, so they cannot pull the margin
 *   left,
 * - the line carrying the *closing* quotes contributes its indentation even though it is blank,
 *   which is what makes moving that line the way you set the margin,
 * - the margin is the smallest of those.
 *
 * One shape has to be decided here rather than read off Java, because Java cannot write it: a
 * literal with content on the opening line (`"""Summary.` — the ordinary docstring). That text
 * starts immediately after the quotes, so it carries no indentation to measure and none can be
 * taken off it. It is left out of the minimum, exactly as `inspect.cleandoc` leaves it out, and
 * the margin is not drawn across it. In the shape Java *can* write — nothing after the opening
 * quotes — the two rules are the same rule.
 *
 * A margin of zero is not drawn ([marginOf] returns null). Nothing is stripped from such a
 * literal, and a line against the left edge of the text would be marking that fact in the one
 * place a reader cannot tell it from the editor's own border.
 *
 * Pure and text-driven, like [BlockClauses], and for the same reason: this is a question about
 * lexical shape, which the `by` server has no request to answer. Its `textDocument/inlayHint` and
 * semantic tokens both stop at the quotes — a string literal is one token, and nothing inside it
 * is ever reported.
 */
object StringMargins {

    /** The margin of every multiline triple-quoted string in [text], in source order. */
    fun marginsIn(text: CharSequence): List<StringMargin> {
        val margins = mutableListOf<StringMargin>()
        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            if (lexer.tokenType == BasedPythonTokenTypes.STRING) {
                marginOf(text, lexer.tokenStart, lexer.tokenEnd)?.let(margins::add)
            }
            lexer.advance()
        }
        return margins
    }

    /**
     * The margin of the literal at `[start, end)`, or null when there is none to draw: a literal
     * that is not triple-quoted, one that occupies a single line, or one whose lines share no
     * indentation at all.
     */
    fun marginOf(text: CharSequence, start: Int, end: Int): StringMargin? {
        val content = contentOf(text, start, end) ?: return null

        // The opening line is whatever follows the quotes up to the first newline. It is not a
        // candidate — it has no indentation of its own — and the margin is not drawn on it.
        val firstLineStart = text.indexOfNewline(content.start, content.end)?.plus(1) ?: return null

        // Candidates, in source order, so the last one to reach the minimum is the closing line
        // whenever the closing line reaches it.
        var indent = Int.MAX_VALUE
        var anchorOffset = -1
        var lineStart = firstLineStart
        var previousLineStart = -1
        var lastLineStart: Int
        while (true) {
            val lineEnd = text.indexOfNewline(lineStart, content.end) ?: content.end
            val closing = lineEnd == content.end
            val lineIndent = indentOf(text, lineStart, lineEnd)
            val blank = lineIndent == lineEnd - lineStart
            // A blank line is erased by the trim, so it says nothing about the margin — unless it
            // is the closing line, whose indentation is the whole point of putting the quotes on
            // a line of their own. An unterminated literal has no such line: its last line is the
            // one being typed, and letting a half-typed blank line count would drag the margin to
            // zero on every press of Enter.
            val counts = (closing && content.closed) || !blank
            if (counts && lineIndent <= indent) {
                indent = lineIndent
                anchorOffset = lineStart + lineIndent
            }
            if (closing) {
                // Quotes on a line of their own end the rule rather than carrying it: they stand
                // at the column it marks. Quotes trailing text (`world"""`) are on a line of
                // content like any other, and that line is drawn.
                lastLineStart = if (content.closed && blank) previousLineStart else lineStart
                break
            }
            previousLineStart = lineStart
            lineStart = lineEnd + 1
        }

        if (indent == Int.MAX_VALUE || indent == 0) return null
        // A literal with nothing between its quotes but the line they sit on. There is a margin,
        // and no line of text to draw it beside.
        if (lastLineStart < firstLineStart) return null
        return StringMargin(
            literalStart = start,
            literalEnd = end,
            indent = indent,
            firstLineStart = firstLineStart,
            lastLineStart = lastLineStart,
            anchorOffset = anchorOffset,
        )
    }

    /** The quoted content of a triple-quoted literal, or null when it is not one. */
    private fun contentOf(text: CharSequence, start: Int, end: Int): Content? {
        var i = start
        while (i < end && text[i].isStringPrefix()) i++
        if (i >= end) return null

        val quote = text[i]
        if (quote != '"' && quote != '\'') return null
        if (i + 2 >= end || text[i + 1] != quote || text[i + 2] != quote) return null

        val contentStart = i + 3
        // Unterminated — the usual state while typing — runs to the end of the token, and the
        // margin is computed from the lines that are already there.
        val closed = end - contentStart >= 3 &&
            text[end - 1] == quote && text[end - 2] == quote && text[end - 3] == quote
        val contentEnd = if (closed) end - 3 else end
        if (contentStart >= contentEnd) return null
        return Content(contentStart, contentEnd, closed)
    }

    private class Content(val start: Int, val end: Int, val closed: Boolean)

    /** Leading spaces and tabs on the line `[lineStart, lineEnd)`, as a character count. */
    private fun indentOf(text: CharSequence, lineStart: Int, lineEnd: Int): Int {
        var i = lineStart
        while (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
        return i - lineStart
    }

    /**
     * The next `\n` in `[from, limit)`, or null.
     *
     * `\n` alone, with no `\r` case: a [com.intellij.openapi.editor.Document] holds only line
     * feeds whatever the file on disk uses, and a document is what this reads.
     */
    private fun CharSequence.indexOfNewline(from: Int, limit: Int): Int? {
        var i = from
        while (i < limit) {
            if (this[i] == '\n') return i
            i++
        }
        return null
    }

    private fun Char.isStringPrefix(): Boolean = this in "rRbBfFuU"
}

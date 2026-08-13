package dev.basedpython.pycharm.inspections.logpoint

/**
 * Finds `print(...)` statements a log point could stand in for, and works out where the log point
 * has to go once the call is gone.
 *
 * A log point is an ordinary line breakpoint that logs an expression instead of suspending; the
 * platform sends its log expression to the adapter as DAP `logMessage`, and debugpy turns that back
 * into a print inside the debuggee — so the output lands in the same run console the `print` was
 * writing to. Which is why this is a swap rather than a rewrite: nothing about the program changes
 * except that the line is no longer in the file.
 *
 * A breakpoint fires *before* its line runs, so the log point cannot go where the call was — that
 * line is about to disappear. It goes on the statement that followed, which runs the expression at
 * exactly the moment the `print` used to. That only reads the same way while the follower is in the
 * same block: a `print` at the end of a function is followed by a line at a lower indent that runs
 * at some entirely different time (often once, at import), so those are left alone rather than
 * silently moved. There is no third option — `pass` left behind as an anchor emits no bytecode in
 * CPython, so the line has no trace event and the breakpoint would never bind.
 *
 * Everything here is offsets into the file text: `.by` has no composite PSI (see
 * `BasedPythonParserDefinition`), so this scans like the other lexer-driven inspections do.
 */
object PrintToLogpoint {

    data class Candidate(
        /** Offset of the `print` name — what the inspection anchors its problem to. */
        val callOffset: Int,
        /** Start of the statement's line; [lineStart] to [lineEndWithSeparator] is what the fix deletes. */
        val lineStart: Int,
        /** End of the statement's line, past its line separator. */
        val lineEndWithSeparator: Int,
        /** The call's argument text, verbatim — this becomes the log point's expression. */
        val expression: String,
        /** 0-based line of the statement that follows, as the document reads *now*. */
        val followerLine: Int,
    ) {
        /**
         * 0-based line the log point goes on once the statement line is gone. Deleting one whole
         * line moves everything below it up by one.
         */
        val logpointLine: Int get() = followerLine - 1
    }

    /** Every convertible `print` in [text], in document order. */
    fun candidates(text: CharSequence): List<Candidate> {
        val lines = lineRanges(text)
        return lines.indices.mapNotNull { candidateAt(text, lines, it) }
    }

    /** The candidate whose `print` name starts at [callOffset], if that is still what is there. */
    fun at(text: CharSequence, callOffset: Int): Candidate? {
        val lines = lineRanges(text)
        val index = lines.indexOfFirst { callOffset >= it.start && callOffset < it.endWithSeparator }
        if (index < 0) return null
        return candidateAt(text, lines, index)?.takeIf { it.callOffset == callOffset }
    }

    // ---------------------------------------------------------------- detection

    private const val NAME = "print"

    private fun candidateAt(text: CharSequence, lines: List<Line>, index: Int): Candidate? {
        val line = lines[index]
        if (!text.startsWith(NAME, line.contentStart)) return null

        // `printer(x)` also starts with the name; only a space or the paren itself may follow.
        var open = line.contentStart + NAME.length
        while (open < line.contentEnd && (text[open] == ' ' || text[open] == '\t')) open++
        if (open >= line.contentEnd || text[open] != '(') return null

        // A call continued onto the next line is deliberately not offered: the fix deletes one line.
        val close = matchingParen(text, open, line.contentEnd) ?: return null

        // Nothing but a trailing comment may follow, or this is not a statement of its own
        // (`print(x); y = 1` would lose the second half).
        var after = close + 1
        while (after < line.contentEnd && (text[after] == ' ' || text[after] == '\t')) after++
        if (after < line.contentEnd && text[after] != '#') return null

        val expression = text.subSequence(open + 1, close).toString().trim()
        // `print()` has nothing to log, and `print(x, file=…)` / `sep=` / `end=` / `flush=` are
        // asking for something a log point does not do.
        if (expression.isEmpty() || hasKeywordArgument(expression)) return null

        val follower = nextStatementLine(lines, index) ?: return null
        if (lines[follower].indent != line.indent) return null

        return Candidate(
            callOffset = line.contentStart,
            lineStart = line.start,
            lineEndWithSeparator = line.endWithSeparator,
            expression = expression,
            followerLine = follower,
        )
    }

    /**
     * Offset just past the `)` closing the call that opens at [open], or null when it does not close
     * before [limit]. Strings are skipped whole, so a bracket or a `#` inside one counts for nothing.
     */
    private fun matchingParen(text: CharSequence, open: Int, limit: Int): Int? {
        var depth = 0
        var i = open
        while (i < limit) {
            when (val c = text[i]) {
                '\'', '"' -> {
                    i = skipString(text, i, limit) ?: return null
                    continue
                }
                '#' -> return null
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) return if (c == ')') i else null
                    if (depth < 0) return null
                }
            }
            i++
        }
        return null
    }

    /** Offset just past the literal starting at [start], or null when it does not close before [limit]. */
    private fun skipString(text: CharSequence, start: Int, limit: Int): Int? {
        val quote = text[start]
        val triple = start + 2 < limit && text[start + 1] == quote && text[start + 2] == quote
        var i = start + if (triple) 3 else 1
        while (i < limit) {
            when {
                text[i] == '\\' -> i += 2
                text[i] != quote -> i++
                !triple -> return i + 1
                i + 2 < limit && text[i + 1] == quote && text[i + 2] == quote -> return i + 3
                else -> i++
            }
        }
        return null
    }

    /**
     * Whether the argument list carries a keyword argument. `==` / `!=` / `<=` / `>=` / `:=` are
     * comparisons, and an `=` nested inside brackets belongs to a call of its own.
     */
    private fun hasKeywordArgument(expression: String): Boolean {
        var depth = 0
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '\'' || c == '"' -> {
                    // An unterminated literal here means the scan cannot be trusted; decline.
                    i = skipString(expression, i, expression.length) ?: return true
                    continue
                }
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == '=' && depth == 0 -> {
                    if (expression.getOrNull(i + 1) == '=') {
                        i += 2
                        continue
                    }
                    val previous = expression.getOrNull(i - 1)
                    if (previous == null || previous !in COMPARISON_HEADS) return true
                }
            }
            i++
        }
        return false
    }

    private val COMPARISON_HEADS = setOf('=', '!', '<', '>', ':')

    /** The next line that runs — blank lines and whole-line comments are neither. */
    private fun nextStatementLine(lines: List<Line>, from: Int): Int? =
        (from + 1 until lines.size).firstOrNull { !lines[it].isBlank && !lines[it].isComment }

    // ---------------------------------------------------------------- lines

    private class Line(
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val endWithSeparator: Int,
        val indent: Int,
        val isBlank: Boolean,
        val isComment: Boolean,
    )

    private fun lineRanges(text: CharSequence): List<Line> {
        val lines = mutableListOf<Line>()
        var i = 0
        while (i < text.length) {
            val start = i
            var indent = 0
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
                indent += if (text[i] == '\t') TAB_WIDTH else 1
                i++
            }
            val contentStart = i
            while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
            val contentEnd = i
            if (i < text.length && text[i] == '\r') i++
            if (i < text.length && text[i] == '\n') i++
            lines += Line(
                start = start,
                contentStart = contentStart,
                contentEnd = contentEnd,
                endWithSeparator = i,
                indent = indent,
                isBlank = contentStart == contentEnd,
                isComment = contentStart < contentEnd && text[contentStart] == '#',
            )
        }
        return lines
    }

    /** Matches [dev.basedpython.pycharm.structure.IndentScanner], so the two agree on what a block is. */
    private const val TAB_WIDTH = 4
}

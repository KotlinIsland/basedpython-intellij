package dev.basedpython.pycharm.inspections.logpoint

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lsp.outline.ByOutline

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
 * exactly the moment the `print` used to. That only reads the same way while the follower is the
 * next statement of the same suite: a `print` at the end of a function is followed by a line that
 * runs at some entirely different time (often once, at import), so those are left alone rather than
 * silently moved. There is no third option — `pass` left behind as an anchor emits no bytecode in
 * CPython, so the line has no trace event and the breakpoint would never bind.
 *
 * Which statements are calls, what they call, what their arguments are and which statement follows
 * in the same suite are all `by`'s parse — see [dev.basedpython.pycharm.lsp.outline.ByOutlines].
 */
object PrintToLogpoint {

    data class Candidate(
        /** Offset of the `print` name — what the inspection anchors its problem to. */
        val callOffset: Int,
        /** Start of the statement's line; [lineStart] to [lineEndWithSeparator] is what the fix deletes. */
        val lineStart: Int,
        /** End of the statement's line, past its line separator. */
        val lineEndWithSeparator: Int,
        /** The call's arguments, verbatim — this becomes the log point's expression. */
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

    /** Every convertible `print` in [document], which [outline] describes, in document order. */
    fun candidates(outline: ByOutline, document: Document): List<Candidate> {
        val found = mutableListOf<Candidate>()
        suites(outline) { suite -> suite.indices.mapNotNullTo(found) { candidateAt(document, suite, it) } }
        return found.sortedBy { it.callOffset }
    }

    /** The candidate whose `print` name starts at [callOffset], if that is still what is there. */
    fun at(outline: ByOutline, document: Document, callOffset: Int): Candidate? =
        candidates(outline, document).firstOrNull { it.callOffset == callOffset }

    private const val NAME = "print"

    private fun candidateAt(document: Document, suite: List<ByOutline.Statement>, index: Int): Candidate? {
        val statement = suite[index]
        val call = statement.call ?: return null
        if (text(document, call.callee) != NAME) return null
        // `print()` has nothing to log, and `print(x, file=…)` / `sep=` / `end=` / `flush=` or an
        // unpacked argument ask for something a log point does not do.
        val arguments = call.arguments ?: return null
        if (!call.positionalOnly) return null

        // The fix deletes one line, so the statement has to be the whole of one: not a call
        // continued onto the next line, not one sharing its line with another statement, and not
        // the inline suite of a header (`if x: print(x)`).
        val line = document.getLineNumber(statement.range.startOffset)
        if (document.getLineNumber(statement.range.endOffset) != line) return null
        val lineStart = document.getLineStartOffset(line)
        val before = document.immutableCharSequence.subSequence(lineStart, statement.range.startOffset)
        if (before.isNotBlank()) return null
        val follower = suite.getOrNull(index + 1) ?: return null
        val followerLine = document.getLineNumber(follower.range.startOffset)
        if (followerLine == line) return null

        return Candidate(
            callOffset = call.callee.startOffset,
            lineStart = lineStart,
            lineEndWithSeparator = minOf(document.getLineEndOffset(line) + 1, document.textLength),
            expression = text(document, arguments),
            followerLine = followerLine,
        )
    }

    /** Every suite in [outline] — the module's statements and each clause's body. */
    private fun suites(outline: ByOutline, visit: (List<ByOutline.Statement>) -> Unit) {
        visit(outline.statements)
        outline.walk { statement, _ -> statement.clauses.forEach { visit(it.body) } }
    }

    private fun text(document: Document, range: TextRange): String =
        document.immutableCharSequence.subSequence(range.startOffset, range.endOffset).toString()
}

package dev.basedpython.pycharm.lang.parser

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Indent-aware lexer used for PARSING ONLY. It wraps the plain [BasedPythonLexer] and, on
 * [start], pre-tokenizes the whole buffer into a flat list of triples
 * `(IElementType, start, end)`. Synthetic [BasedPythonTokenTypes.INDENT] /
 * [BasedPythonTokenTypes.DEDENT] tokens (zero-width) and
 * [BasedPythonTokenTypes.STATEMENT_BREAK] tokens (covering the trailing newline of a logical
 * line) are spliced in so the parser can build a real suite tree.
 *
 * This lexer is NEVER used by the syntax highlighter; the highlighter keeps its own plain
 * [BasedPythonLexer] instance so highlighting is unaffected by these synthetic tokens.
 *
 * Indentation model (mirrors `structure.IndentScanner` conceptually):
 *  - A *logical line* begins at the first non-whitespace token after a real newline (or BOF).
 *  - Blank lines and full-line comments do not affect the indent stack.
 *  - Newlines/indentation are suppressed while inside unclosed `()[]{}` (implicit joining)
 *    and immediately after a backslash line-continuation.
 *  - INDENT is emitted when a logical line's indent exceeds the stack top; DEDENT(s) when it
 *    is smaller. At EOF all remaining indents are unwound.
 */
class BasedPythonIndentingLexer : LexerBase() {

    private data class Tok(val type: IElementType, val start: Int, val end: Int)

    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0

    private val tokens = ArrayList<Tok>()
    private var index = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        tokens.clear()
        index = 0
        preTokenize()
    }

    override fun getState(): Int = 0
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun getTokenType(): IElementType? = tokens.getOrNull(index)?.type
    override fun getTokenStart(): Int = tokens.getOrNull(index)?.start ?: endOffset
    override fun getTokenEnd(): Int = tokens.getOrNull(index)?.end ?: endOffset

    override fun advance() {
        if (index < tokens.size) index++
    }

    // -------------------------------------------------------------------------
    // Pre-tokenization
    // -------------------------------------------------------------------------

    private fun isNewline(t: Tok): Boolean {
        // The plain lexer emits newline runs as WHITESPACE. Distinguish them from
        // pure space/tab whitespace by checking the actual characters.
        if (t.type != TokenType.WHITE_SPACE) return false
        for (i in t.start until t.end) {
            val c = buffer[i]
            if (c == '\n' || c == '\r') return true
        }
        return false
    }

    private fun isLineContinuation(t: Tok): Boolean {
        // The plain lexer emits a backslash + newline as a WHITESPACE token starting with '\'.
        return t.type == TokenType.WHITE_SPACE && t.start < t.end && buffer[t.start] == '\\'
    }

    /** Indentation width (spaces=1, tabs=4) of the line containing [offset]. */
    private fun indentWidthAtLineStart(offset: Int): Int {
        // Walk back to the start of the line.
        var ls = offset
        while (ls > startOffset && buffer[ls - 1] != '\n' && buffer[ls - 1] != '\r') ls--
        var width = 0
        var i = ls
        while (i < endOffset) {
            val c = buffer[i]
            when (c) {
                ' ' -> width += 1
                '\t' -> width += 4
                else -> break
            }
            i++
        }
        return width
    }

    private fun preTokenize() {
        // 1) Drive the plain lexer to EOF.
        val raw = ArrayList<Tok>()
        val lx = BasedPythonLexer()
        lx.start(buffer, startOffset, endOffset, 0)
        while (lx.tokenType != null) {
            raw += Tok(lx.tokenType!!, lx.tokenStart, lx.tokenEnd)
            lx.advance()
        }

        // Every raw token is preserved verbatim so the text round-trips and the platform can
        // build a consistent tree. We only (a) insert zero-width INDENT/DEDENT before the first
        // significant token of a logical line, and (b) RECLASSIFY a significant trailing newline
        // WHITESPACE token into a STATEMENT_BREAK (same span). Synthetic INDENT/DEDENT must be
        // emitted BEFORE the leading whitespace of their line so they don't split a whitespace
        // run; we therefore buffer pending leading whitespace until the first content token.

        val indentStack = ArrayDeque<Int>()
        indentStack.addLast(0)
        var bracketDepth = 0
        var atLogicalLineStart = true
        var lineHasContent = false
        var pendingContinuation = false
        // Whitespace tokens that lead a (potential) logical line, held until we know the indent.
        val pendingLeadingWs = ArrayList<Tok>()

        fun flushLeadingWs() {
            tokens.addAll(pendingLeadingWs)
            pendingLeadingWs.clear()
        }

        var i = 0
        while (i < raw.size) {
            val t = raw[i]

            if (t.type == TokenType.WHITE_SPACE) {
                if (isLineContinuation(t)) {
                    pendingContinuation = true
                    if (atLogicalLineStart) pendingLeadingWs += t else tokens += t
                    i++
                    continue
                }
                if (isNewline(t)) {
                    if (bracketDepth > 0 || pendingContinuation) {
                        // Insignificant newline: keep as plain whitespace.
                        pendingContinuation = false
                        if (atLogicalLineStart) pendingLeadingWs += t else tokens += t
                        i++
                        continue
                    }
                    // Significant newline.
                    if (lineHasContent) {
                        // Any buffered leading ws belongs after content already emitted — flush it.
                        flushLeadingWs()
                        tokens += Tok(BasedPythonTokenTypes.STATEMENT_BREAK, t.start, t.end)
                        lineHasContent = false
                    } else {
                        // Blank line: emit the newline as plain whitespace, keep leading ws buffered.
                        if (atLogicalLineStart) pendingLeadingWs += t else tokens += t
                    }
                    atLogicalLineStart = true
                    i++
                    continue
                }
                // Plain spaces/tabs.
                if (atLogicalLineStart) pendingLeadingWs += t else tokens += t
                i++
                continue
            }

            if (t.type == BasedPythonTokenTypes.COMMENT) {
                // Comments never start a logical line; keep their leading ws buffered so a
                // full-line comment does not trigger indentation changes.
                if (atLogicalLineStart) pendingLeadingWs += t else tokens += t
                i++
                continue
            }

            pendingContinuation = false

            // First significant token of a logical line → resolve indentation.
            // INDENT/DEDENT must be placed at the line-start offset (BEFORE any leading
            // whitespace) so the platform sees strictly non-descending token offsets.
            if (atLogicalLineStart && bracketDepth == 0) {
                val lineStart = pendingLeadingWs.firstOrNull()?.start ?: t.start
                val width = indentWidthAtLineStart(t.start)
                val top = indentStack.last()
                if (width > top) {
                    indentStack.addLast(width)
                    tokens += Tok(BasedPythonTokenTypes.INDENT, lineStart, lineStart)
                } else if (width < top) {
                    while (indentStack.size > 1 && indentStack.last() > width) {
                        indentStack.removeLast()
                        tokens += Tok(BasedPythonTokenTypes.DEDENT, lineStart, lineStart)
                    }
                }
                atLogicalLineStart = false
            }
            flushLeadingWs()

            when (t.type) {
                BasedPythonTokenTypes.LPAREN,
                BasedPythonTokenTypes.LBRACKET,
                BasedPythonTokenTypes.LBRACE -> bracketDepth++
                BasedPythonTokenTypes.RPAREN,
                BasedPythonTokenTypes.RBRACKET,
                BasedPythonTokenTypes.RBRACE -> if (bracketDepth > 0) bracketDepth--
            }

            tokens += t
            lineHasContent = true
            i++
        }

        // Flush any trailing buffered whitespace (e.g. blank lines at EOF).
        flushLeadingWs()

        // End-of-file: close a dangling logical line, then unwind indents.
        if (lineHasContent) {
            tokens += Tok(BasedPythonTokenTypes.STATEMENT_BREAK, endOffset, endOffset)
        }
        while (indentStack.size > 1) {
            indentStack.removeLast()
            tokens += Tok(BasedPythonTokenTypes.DEDENT, endOffset, endOffset)
        }
    }
}

package dev.basedpython.pycharm.lang

import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType

/**
 * The editor's lexer for `.by`: [BasedPythonLexer]'s tokens, with each f-string and t-string read
 * as its parts ([FStrings]) — prefix and quotes, text, the braces of each field and the field's
 * expression as ordinary tokens, a `!r` conversion, a format spec's `:` — instead of one string.
 *
 * What the editor reads off its lexer is then what it is for a `.py`: the braces of a field match
 * and, being structural ([BasedPythonBraceMatcher]), show the scope of a field spanning lines; the
 * caret stops at a field's edges; Find's *Except string literals* searches a field's code; and the
 * parts have colours of their own. The tree is built from [BasedPythonLexer] and holds the literal
 * whole.
 *
 * The lexer restarts only between top-level tokens: a token inside a literal reports a state other
 * than the initial one, so the editor re-lexes a changed literal from its start.
 */
class BasedPythonHighlightingLexer : LexerBase() {

    private val base = BasedPythonLexer()

    /** The current literal's parts, when the base lexer is at an interpolated one. */
    private val parts = ArrayList<Part>()
    private var index = 0

    private class Part(val start: Int, val end: Int, val type: IElementType)

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        base.start(buffer, startOffset, endOffset, 0)
        split()
    }

    private fun split() {
        parts.clear()
        index = 0
        if (base.tokenType != BasedPythonTokenTypes.STRING) return
        val buffer = base.bufferSequence
        var quote = base.tokenStart
        while (quote < base.tokenEnd && buffer[quote].isLetter()) quote++
        if (!FStrings.isInterpolated(buffer, base.tokenStart, quote)) return
        FStrings.scan(buffer, base.tokenStart, base.tokenEnd) { start, end, type -> if (end > start) parts += Part(start, end, type) }
    }

    override fun getState(): Int = if (parts.isNotEmpty() && index > 0) IN_LITERAL else 0
    override fun getTokenType(): IElementType? = if (parts.isEmpty()) base.tokenType else parts[index].type
    override fun getTokenStart(): Int = if (parts.isEmpty()) base.tokenStart else parts[index].start
    override fun getTokenEnd(): Int = if (parts.isEmpty()) base.tokenEnd else parts[index].end
    override fun getBufferSequence(): CharSequence = base.bufferSequence
    override fun getBufferEnd(): Int = base.bufferEnd

    override fun advance() {
        if (parts.isNotEmpty() && ++index < parts.size) return
        base.advance()
        split()
    }

    companion object {
        /** The state of a token inside a literal, from which the editor does not restart. */
        private const val IN_LITERAL = 1

        /**
         * The whole literal whose text [iterator] is at — a string token, or a part of an f-string's
         * text or quotes — as the editor's lexer reads it; null at anything else, a field's code
         * included. Leaves the iterator where it was.
         */
        fun literalAt(iterator: HighlighterIterator): TextRange? {
            val type = iterator.tokenType
            if (type == BasedPythonTokenTypes.STRING) return TextRange(iterator.start, iterator.end)
            if (type !in BasedPythonTokenTypes.FSTRING_TEXT_PARTS) return null
            // back to this literal's first part, past any literal nested in a field before it
            var depth = 0
            var steps = 0
            var start: Int? = null
            while (!iterator.atEnd()) {
                when (iterator.tokenType) {
                    BasedPythonTokenTypes.FSTRING_END -> if (steps > 0) depth++
                    BasedPythonTokenTypes.FSTRING_START -> if (depth == 0) {
                        start = iterator.start
                        break
                    } else depth--
                }
                if (iterator.start == 0) break
                iterator.retreat()
                steps++
            }
            repeat(steps) { iterator.advance() }
            start ?: return null
            val text = iterator.document.immutableCharSequence
            return TextRange(start, FStrings.scan(text, start, text.length))
        }
    }
}

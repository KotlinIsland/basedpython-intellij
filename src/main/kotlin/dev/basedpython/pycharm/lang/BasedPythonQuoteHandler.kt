package dev.basedpython.pycharm.lang

import com.intellij.codeInsight.editorActions.MultiCharQuoteHandler
import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.tree.TokenSet

/**
 * Pairs a `.by` string's quotes as they are typed, as PyCharm's does for `.py`: `"` gets its `"`,
 * `f"` and `rb'` get theirs after the prefix, and the third quote of `"""` gets the closing `"""`.
 * Typing a closing quote over the one already there steps over it, in a triple-quoted literal too.
 *
 * All of it is the editor's lexer's: a literal is one string token, prefix and quotes included, and
 * an f-string or t-string is its parts ([BasedPythonHighlightingLexer]) — where its opening quotes
 * are is its first part, whether it is closed is whether a last one follows.
 */
class BasedPythonQuoteHandler :
    SimpleTokenSetQuoteHandler(TokenSet.orSet(TokenSet.create(BasedPythonTokenTypes.STRING), BasedPythonTokenTypes.FSTRING_TEXT_PARTS)),
    MultiCharQuoteHandler {

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType != BasedPythonTokenTypes.STRING && iterator.tokenType != BasedPythonTokenTypes.FSTRING_START) return false
        return offset == Literal.of(iterator)?.quoteStart
    }

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean = when (iterator.tokenType) {
        BasedPythonTokenTypes.FSTRING_END -> offset >= iterator.start && offset < iterator.end
        BasedPythonTokenTypes.STRING -> {
            val literal = Literal.of(iterator)
            literal != null && literal.closed && offset >= iterator.end - literal.width && offset < iterator.end
        }
        else -> false
    }

    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean = when (iterator.tokenType) {
        BasedPythonTokenTypes.STRING -> Literal.of(iterator)?.closed != true
        BasedPythonTokenTypes.FSTRING_START -> !closedFString(iterator)
        else -> false
    }

    /** The closing `"""` or `'''` for the third quote of an opening one typed just before [offset]. */
    override fun getClosingQuote(iterator: HighlighterIterator, offset: Int): CharSequence? {
        // the literal's opening may end at the offset, with the token there the one after it
        if (iterator.start == offset && iterator.start > 0) iterator.retreat()
        if (iterator.atEnd()) return null
        if (iterator.tokenType != BasedPythonTokenTypes.STRING && iterator.tokenType != BasedPythonTokenTypes.FSTRING_START) return null
        val literal = Literal.of(iterator) ?: return null
        if (literal.width != 3 || literal.quoteStart + 3 != offset) return null
        return iterator.document.immutableCharSequence.subSequence(literal.quoteStart, offset)
    }

    /** Whether the f-string whose first part [iterator] is at ends in its closing quotes. */
    private fun closedFString(iterator: HighlighterIterator): Boolean {
        val text = iterator.document.immutableCharSequence
        val opening = Literal.of(iterator) ?: return false
        val end = FStrings.scan(text, iterator.start, text.length)
        val quote = text[opening.quoteStart]
        return end - opening.quoteStart >= 2 * opening.width && (end - opening.width until end).all { text[it] == quote }
    }

    /** A string token's, or an f-string's first part's, prefix and quotes, read off its text. */
    private class Literal(val quoteStart: Int, val width: Int, val closed: Boolean) {
        companion object {
            fun of(iterator: HighlighterIterator): Literal? {
                val text = iterator.document.immutableCharSequence
                val start = iterator.start
                val end = iterator.end
                var quoteStart = start
                while (quoteStart < end && text[quoteStart].isLetter()) quoteStart++
                if (quoteStart >= end) return null
                val quote = text[quoteStart]
                if (quote != '"' && quote != '\'') return null
                val width = if (quoteStart + 2 < text.length && text[quoteStart + 1] == quote && text[quoteStart + 2] == quote &&
                    quoteStart + 2 < end
                ) 3 else 1
                var escapes = 0
                while (end - width - 1 - escapes > quoteStart && text[end - width - 1 - escapes] == '\\') escapes++
                val closed = end - quoteStart >= 2 * width && (end - width until end).all { text[it] == quote } &&
                    escapes % 2 == 0
                return Literal(quoteStart, width, closed)
            }
        }
    }
}

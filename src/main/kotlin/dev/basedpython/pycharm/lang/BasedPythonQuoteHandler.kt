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
 * All of it is the lexer's: the lexer reads a literal as one token, prefix and quotes included, so
 * where its opening quote is and whether it is closed are read off that token's text.
 */
class BasedPythonQuoteHandler :
    SimpleTokenSetQuoteHandler(TokenSet.create(BasedPythonTokenTypes.STRING)),
    MultiCharQuoteHandler {

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType != BasedPythonTokenTypes.STRING) return false
        return offset == Literal.of(iterator)?.quoteStart
    }

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        if (iterator.tokenType != BasedPythonTokenTypes.STRING) return false
        val literal = Literal.of(iterator) ?: return false
        return literal.closed && offset >= iterator.end - literal.width && offset < iterator.end
    }

    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean =
        Literal.of(iterator)?.closed != true

    /** The closing `"""` or `'''` for the third quote of an opening one typed just before [offset]. */
    override fun getClosingQuote(iterator: HighlighterIterator, offset: Int): CharSequence? {
        // the literal may end at the offset, with the token there the one after it
        if (iterator.start == offset && iterator.start > 0) iterator.retreat()
        if (iterator.atEnd() || iterator.tokenType != BasedPythonTokenTypes.STRING) return null
        val literal = Literal.of(iterator) ?: return null
        if (literal.width != 3 || literal.quoteStart + 3 != offset) return null
        return iterator.document.immutableCharSequence.subSequence(literal.quoteStart, offset)
    }

    /** A string token's prefix and quotes, read off its text. */
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
                val width = if (quoteStart + 2 < end && text[quoteStart + 1] == quote && text[quoteStart + 2] == quote) 3 else 1
                var escapes = 0
                while (end - width - 1 - escapes > quoteStart && text[end - width - 1 - escapes] == '\\') escapes++
                val closed = end - quoteStart >= 2 * width && (end - width until end).all { text[it] == quote } &&
                    escapes % 2 == 0
                return Literal(quoteStart, width, closed)
            }
        }
    }
}

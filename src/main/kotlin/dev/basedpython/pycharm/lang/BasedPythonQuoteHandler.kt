package dev.basedpython.pycharm.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.psi.tree.TokenSet

/**
 * Handles autoclose / smart-skip for `'`, `"`, `"""`, `'''`. We treat all our string
 * tokens uniformly; the lexer emits one STRING token per literal (including triples).
 */
class BasedPythonQuoteHandler : SimpleTokenSetQuoteHandler(TokenSet.create(BasedPythonTokenTypes.STRING)) {

    override fun isOpeningQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        // Opening quote when caret is at the start of a string token.
        return iterator.tokenType == BasedPythonTokenTypes.STRING && offset == iterator.start
    }

    override fun isClosingQuote(iterator: HighlighterIterator, offset: Int): Boolean {
        // Closing quote when caret is right before the last char of a string token.
        return iterator.tokenType == BasedPythonTokenTypes.STRING && offset == iterator.end - 1
    }
}

package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.codeInsight.editorActions.TypedHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonQuoteHandler
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Backspace between the quotes of an empty `"""…"""` takes all six, as PyCharm's
 * `PyTripleQuoteBackspaceDelegate` does for `.py`: typing `"""` writes the closing `"""` as well
 * ([BasedPythonQuoteHandler]), and Backspace straight after is taking that back.
 *
 * Without it the platform's Backspace removes one quote and its pair — the pair of a single quote is
 * all it knows — and leaves `""|"""` behind (measured in PyCharm 263.7649: `.py` went back to
 * `x = |`, `.by` to `x = ""|"""`). PyCharm's delegate cannot take a `.by` file: it acts only for a
 * quote handler of Python's own class.
 *
 * All of it is the editor's lexer's: the opening quotes are where the quote handler says a literal
 * opens, and the literal is empty when its token — or, for an f-string or t-string, its first part
 * and the closing part right after — ends three quotes past the caret.
 */
class ByTripleQuoteBackspaceDelegate : BackspaceHandlerDelegate() {

    /** Whether the character about to be deleted is the third quote of an empty triple-quoted literal. */
    private var emptyTriple = false

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        val quotes = TypedHandler.getQuoteHandler(file, editor) as? BasedPythonQuoteHandler
        emptyTriple = quotes != null && (c == '"' || c == '\'') &&
            CodeInsightSettings.getInstance().AUTOINSERT_PAIR_QUOTE &&
            isEmptyTriple(editor, quotes, c)
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (!emptyTriple) return false
        emptyTriple = false
        // the third opening quote is gone: the other two before the caret and the closing three after it
        val offset = editor.caretModel.offset
        editor.document.deleteString(offset - 2, offset + 3)
        return true
    }

    private fun isEmptyTriple(editor: Editor, quotes: BasedPythonQuoteHandler, quote: Char): Boolean {
        val offset = editor.caretModel.offset
        val text = editor.document.immutableCharSequence
        val opening = offset - 3
        if (opening < 0 || offset + 3 > text.length) return false
        if ((opening until offset + 3).any { text[it] != quote }) return false
        val iterator = editor.highlighter.createIterator(opening)
        if (iterator.atEnd() || !quotes.isOpeningQuote(iterator, opening)) return false
        return when (iterator.tokenType) {
            BasedPythonTokenTypes.STRING -> iterator.end == offset + 3
            BasedPythonTokenTypes.FSTRING_START -> {
                if (iterator.end != offset) return false
                iterator.advance()
                !iterator.atEnd() && iterator.tokenType == BasedPythonTokenTypes.FSTRING_END && iterator.end == offset + 3
            }
            else -> false
        }
    }
}

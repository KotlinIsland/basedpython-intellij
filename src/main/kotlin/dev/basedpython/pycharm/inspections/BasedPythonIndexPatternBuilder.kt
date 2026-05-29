package dev.basedpython.pycharm.inspections

import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Registers BasedPython (.by) comment tokens with the IDE's index pattern infrastructure
 * so that TODO/FIXME markers inside `#` comments appear in the TODO tool window.
 */
class BasedPythonIndexPatternBuilder : IndexPatternBuilder {

    companion object {
        private val COMMENT_TOKENS: TokenSet = TokenSet.create(BasedPythonTokenTypes.COMMENT)
    }

    override fun getIndexingLexer(file: PsiFile): Lexer? =
        if (file is BasedPythonFile) BasedPythonLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? =
        if (file is BasedPythonFile) COMMENT_TOKENS else null

    override fun getCommentStartDelta(tokenType: IElementType?): Int = 1  // skip the leading '#'

    override fun getCommentEndDelta(tokenType: IElementType?): Int = 0
}

package dev.basedpython.pycharm.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class BasedPythonBraceMatcher : PairedBraceMatcher {

    private val pairs: Array<BracePair> = arrayOf(
        BracePair(BasedPythonTokenTypes.LPAREN, BasedPythonTokenTypes.RPAREN, false),
        BracePair(BasedPythonTokenTypes.LBRACKET, BasedPythonTokenTypes.RBRACKET, false),
        BracePair(BasedPythonTokenTypes.LBRACE, BasedPythonTokenTypes.RBRACE, false),
    )

    override fun getPairs(): Array<BracePair> = pairs
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

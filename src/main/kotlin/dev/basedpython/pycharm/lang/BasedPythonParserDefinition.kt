package dev.basedpython.pycharm.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Minimal parser definition. We intentionally produce a flat tree: the lexer streams tokens,
 * the parser wraps them in the file element. Real semantic analysis is delegated to the LSP.
 *
 * `createElement` should never be called for non-file nodes because we never produce composite
 * nodes — but the platform requires the method, so we return a safe placeholder.
 */
class BasedPythonParserDefinition : ParserDefinition {

    companion object {
        val FILE: IFileElementType = IFileElementType(BasedPythonLanguage)

        val COMMENTS: TokenSet = TokenSet.create(BasedPythonTokenTypes.COMMENT)
        val STRINGS: TokenSet = TokenSet.create(BasedPythonTokenTypes.STRING)
        val WHITESPACE: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
    }

    override fun createLexer(project: Project?): Lexer = BasedPythonLexer()

    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun getFileNodeType(): IFileElementType = FILE

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        parseFlat(root, builder)
    }

    private fun parseFlat(root: com.intellij.psi.tree.IElementType, builder: PsiBuilder): ASTNode {
        val marker = builder.mark()
        while (!builder.eof()) builder.advanceLexer()
        marker.done(root)
        return builder.treeBuilt
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = BasedPythonFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement =
        throw UnsupportedOperationException("BasedPython parser produces no composite nodes; node=$node")
}

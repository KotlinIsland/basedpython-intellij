package dev.basedpython.pycharm.lang

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import dev.basedpython.pycharm.lang.parser.BasedPythonIndentingLexer
import dev.basedpython.pycharm.lang.parser.BasedPythonParser
import dev.basedpython.pycharm.lang.psi.BasedPythonPsiFactory

/**
 * Parser definition for `.by`. Parsing uses the indent-aware [BasedPythonIndentingLexer] and
 * the tolerant [BasedPythonParser] to build a real composite PSI tree.
 *
 * NOTE: this lexer is for PARSING ONLY. Syntax highlighting keeps its own plain
 * [BasedPythonLexer] (see [BasedPythonSyntaxHighlighter]), so the synthetic INDENT/DEDENT/
 * STATEMENT_BREAK tokens never reach the highlighter.
 *
 * INDENT/DEDENT/STATEMENT_BREAK are deliberately NOT in the whitespace/comment sets so the
 * parser can consume them as structural tokens.
 */
class BasedPythonParserDefinition : ParserDefinition {

    companion object {
        val FILE: IFileElementType = IFileElementType(BasedPythonLanguage)

        val COMMENTS: TokenSet = TokenSet.create(BasedPythonTokenTypes.COMMENT)
        val STRINGS: TokenSet = TokenSet.create(BasedPythonTokenTypes.STRING)
        val WHITESPACE: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
    }

    override fun createLexer(project: Project?): Lexer = BasedPythonIndentingLexer()

    override fun getCommentTokens(): TokenSet = COMMENTS
    override fun getStringLiteralElements(): TokenSet = STRINGS
    override fun getWhitespaceTokens(): TokenSet = WHITESPACE

    override fun getFileNodeType(): IFileElementType = FILE

    override fun createParser(project: Project?): PsiParser = BasedPythonParser()

    override fun createFile(viewProvider: FileViewProvider): PsiFile = BasedPythonFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = BasedPythonPsiFactory.createElement(node)
}

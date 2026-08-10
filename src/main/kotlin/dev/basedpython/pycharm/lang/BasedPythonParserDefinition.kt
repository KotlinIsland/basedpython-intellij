package dev.basedpython.pycharm.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
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
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * Parser definition for `.by`.
 *
 * Deliberately flat: the tree is the file node and one leaf per token, nothing more.
 *
 * There used to be a real composite parser here — an indent-aware lexer emitting INDENT/DEDENT plus
 * a tolerant recursive-descent parser building defs, classes, imports and blocks. It was a second,
 * always-behind implementation of a language whose grammar the `by` server already knows, and after
 * the structure view moved to LSP document symbols nothing read the tree it produced. So it is gone.
 *
 * The platform still requires a `ParserDefinition` to register a language, and the leaves it
 * produces are what the lexer-driven features work on: syntax highlighting, the commenter, brace
 * matching, the TODO index, spell checking and the annotators. Everything that needs to know what
 * the code *means* — symbols, folding ranges, semantic colour, diagnostics — asks the server.
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

    override fun createParser(project: Project?): PsiParser = FlatParser

    override fun createFile(viewProvider: FileViewProvider): PsiFile = BasedPythonFile(viewProvider)

    /** Never reached — [FlatParser] produces no composite nodes below the file. */
    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    /** Consumes every token into the file node. */
    private object FlatParser : PsiParser {
        override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
            val marker = builder.mark()
            while (!builder.eof()) builder.advanceLexer()
            marker.done(root)
            return builder.treeBuilt
        }
    }
}

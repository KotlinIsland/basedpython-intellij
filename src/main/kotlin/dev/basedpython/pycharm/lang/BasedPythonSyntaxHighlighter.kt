package dev.basedpython.pycharm.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class BasedPythonSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = BasedPythonLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        BasedPythonTokenTypes.KEYWORD     -> pack(BasedPythonColors.KEYWORD)
        BasedPythonTokenTypes.STRING      -> pack(BasedPythonColors.STRING)
        BasedPythonTokenTypes.NUMBER      -> pack(BasedPythonColors.NUMBER)
        BasedPythonTokenTypes.COMMENT     -> pack(BasedPythonColors.COMMENT)
        BasedPythonTokenTypes.OPERATOR    -> pack(BasedPythonColors.OPERATOR)
        BasedPythonTokenTypes.IDENTIFIER  -> pack(BasedPythonColors.IDENTIFIER)
        BasedPythonTokenTypes.LPAREN,
        BasedPythonTokenTypes.RPAREN      -> pack(BasedPythonColors.PARENTHESES)
        BasedPythonTokenTypes.LBRACKET,
        BasedPythonTokenTypes.RBRACKET    -> pack(BasedPythonColors.BRACKETS)
        BasedPythonTokenTypes.LBRACE,
        BasedPythonTokenTypes.RBRACE      -> pack(BasedPythonColors.BRACES)
        BasedPythonTokenTypes.COMMA       -> pack(BasedPythonColors.COMMA)
        BasedPythonTokenTypes.SEMICOLON   -> pack(BasedPythonColors.SEMICOLON)
        BasedPythonTokenTypes.DOT         -> pack(BasedPythonColors.DOT)
        TokenType.BAD_CHARACTER           -> pack(BasedPythonColors.BAD_CHARACTER)
        else                              -> EMPTY
    }

    private companion object {
        val EMPTY: Array<TextAttributesKey> = emptyArray()
    }
}

class BasedPythonSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        BasedPythonSyntaxHighlighter()
}

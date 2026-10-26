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
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys

class BasedPythonSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = BasedPythonHighlightingLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = when (tokenType) {
        BasedPythonTokenTypes.KEYWORD     -> pack(BasedPythonColors.KEYWORD)
        BasedPythonTokenTypes.STRING,
        BasedPythonTokenTypes.FSTRING_START,
        BasedPythonTokenTypes.FSTRING_TEXT,
        BasedPythonTokenTypes.FSTRING_END -> pack(BasedPythonColors.STRING)
        BasedPythonTokenTypes.FSTRING_ESCAPED_BRACE -> pack(BasedPythonColors.STRING, BasedPythonHighlightKeys.STRING_ESCAPE)
        BasedPythonTokenTypes.FSTRING_LBRACE,
        BasedPythonTokenTypes.FSTRING_RBRACE -> pack(BasedPythonHighlightKeys.FSTRING_BRACES)
        BasedPythonTokenTypes.FSTRING_CONVERSION -> pack(BasedPythonHighlightKeys.FSTRING_CONVERSION)
        BasedPythonTokenTypes.FSTRING_FORMAT_START -> pack(BasedPythonHighlightKeys.FSTRING_FORMAT_START)
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

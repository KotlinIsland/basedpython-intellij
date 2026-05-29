package dev.basedpython.pycharm.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

class BasedPythonTokenType(debugName: String) : IElementType(debugName, BasedPythonLanguage)

object BasedPythonTokenTypes {
    @JvmField val KEYWORD: IElementType = BasedPythonTokenType("BY_KEYWORD")
    @JvmField val IDENTIFIER: IElementType = BasedPythonTokenType("BY_IDENTIFIER")
    @JvmField val NUMBER: IElementType = BasedPythonTokenType("BY_NUMBER")
    @JvmField val STRING: IElementType = BasedPythonTokenType("BY_STRING")
    @JvmField val COMMENT: IElementType = BasedPythonTokenType("BY_COMMENT")
    @JvmField val OPERATOR: IElementType = BasedPythonTokenType("BY_OPERATOR")
    @JvmField val LPAREN: IElementType = BasedPythonTokenType("BY_LPAREN")
    @JvmField val RPAREN: IElementType = BasedPythonTokenType("BY_RPAREN")
    @JvmField val LBRACKET: IElementType = BasedPythonTokenType("BY_LBRACKET")
    @JvmField val RBRACKET: IElementType = BasedPythonTokenType("BY_RBRACKET")
    @JvmField val LBRACE: IElementType = BasedPythonTokenType("BY_LBRACE")
    @JvmField val RBRACE: IElementType = BasedPythonTokenType("BY_RBRACE")
    @JvmField val COMMA: IElementType = BasedPythonTokenType("BY_COMMA")
    @JvmField val COLON: IElementType = BasedPythonTokenType("BY_COLON")
    @JvmField val SEMICOLON: IElementType = BasedPythonTokenType("BY_SEMICOLON")
    @JvmField val DOT: IElementType = BasedPythonTokenType("BY_DOT")
    @JvmField val NEWLINE: IElementType = BasedPythonTokenType("BY_NEWLINE")

    // Re-export platform WHITESPACE / BAD_CHARACTER for convenience
    @JvmField val WHITESPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    // Python keywords
    val PYTHON_KEYWORDS: Set<String> = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield", "match", "case", "type",
    )

    // BasedPython extras
    val BASEDPYTHON_KEYWORDS: Set<String> = setOf(
        "final", "override", "abstract", "static", "protocol",
        "let", "newtype", "public", "private", "data", "frozen", "enum",
    )

    val ALL_KEYWORDS: Set<String> = PYTHON_KEYWORDS + BASEDPYTHON_KEYWORDS
}

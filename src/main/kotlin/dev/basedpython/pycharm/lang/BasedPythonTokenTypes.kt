package dev.basedpython.pycharm.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class BasedPythonTokenType(debugName: String) : IElementType(debugName, BasedPythonLanguage)

/**
 * A node with children, as against a token.
 *
 * There is exactly one, and it exists so that a string literal has something to be: see
 * [dev.basedpython.pycharm.lang.BasedPythonStringLiteral].
 */
class BasedPythonElementType(debugName: String) : IElementType(debugName, BasedPythonLanguage)

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

    // The parts of an f-string or t-string, as the HIGHLIGHTING lexer reads one
    // (BasedPythonHighlightingLexer, by FStrings): never in the tree, which holds the literal as one
    // STRING, but what the editor's lexer — brace matching, scope and word stops, Find's string
    // contexts, colours — sees. A field's expression is ordinary tokens between the braces.
    /** The prefix and opening quotes: `f"`, `rt'''`. */
    @JvmField val FSTRING_START: IElementType = BasedPythonTokenType("BY_FSTRING_START")
    /** A run of the literal's text, a format spec's included. */
    @JvmField val FSTRING_TEXT: IElementType = BasedPythonTokenType("BY_FSTRING_TEXT")
    /** `{{` or `}}`: a brace written as text. */
    @JvmField val FSTRING_ESCAPED_BRACE: IElementType = BasedPythonTokenType("BY_FSTRING_ESCAPED_BRACE")
    /** The `{` and `}` around a replacement field. */
    @JvmField val FSTRING_LBRACE: IElementType = BasedPythonTokenType("BY_FSTRING_LBRACE")
    @JvmField val FSTRING_RBRACE: IElementType = BasedPythonTokenType("BY_FSTRING_RBRACE")
    /** A field's `!r`, `!s` or `!a`. */
    @JvmField val FSTRING_CONVERSION: IElementType = BasedPythonTokenType("BY_FSTRING_CONVERSION")
    /** The `:` a field's format spec starts with. */
    @JvmField val FSTRING_FORMAT_START: IElementType = BasedPythonTokenType("BY_FSTRING_FORMAT_START")
    /** The closing quotes. */
    @JvmField val FSTRING_END: IElementType = BasedPythonTokenType("BY_FSTRING_END")

    /** The parts of an f-string that are the literal's text and quotes, not its fields' code. */
    @JvmField val FSTRING_TEXT_PARTS: TokenSet = TokenSet.create(FSTRING_START, FSTRING_TEXT, FSTRING_ESCAPED_BRACE, FSTRING_END)

    /**
     * The one composite node in a `.by` tree: a [STRING] token with a PSI element around it.
     *
     * Not a token — nothing lexes to it. The parser wraps every string, so that the literal is
     * something the platform can inject a language into; see [BasedPythonStringLiteral].
     */
    @JvmField val STRING_LITERAL: IElementType = BasedPythonElementType("BY_STRING_LITERAL")

    // Synthetic structural tokens produced by the indent-aware PARSING lexer only.
    // These are NEVER fed to the syntax highlighter (which keeps using the plain lexer).
    @JvmField val INDENT: IElementType = BasedPythonTokenType("BY_INDENT")
    @JvmField val DEDENT: IElementType = BasedPythonTokenType("BY_DEDENT")
    @JvmField val STATEMENT_BREAK: IElementType = BasedPythonTokenType("BY_STATEMENT_BREAK")

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

    // basedpython extras
    // Modifier keywords (may precede def/class/let/assignment, in any order) plus the
    // class-kind / binding introducers. Matches the parser's `is_modifier_kw` list, with
    // `protocol`/`let`/`newtype` (introducers) added.
    // `out`/`in` are use-site variance markers in type brackets (`[out T]`, `[in T]`,
    // `[in out T]`); `in` is already a Python keyword, so only `out` is added here.
    // These are soft keywords (also valid identifiers); the highlighter colors them
    // unconditionally, consistent with how `match`/`case`/`type`/`data` are treated.
    val BASEDPYTHON_KEYWORDS: Set<String> = setOf(
        "final", "override", "abstract", "static", "protocol", "open", "export",
        "let", "newtype", "public", "private", "data", "frozen", "enum", "out",
    )

    val ALL_KEYWORDS: Set<String> = PYTHON_KEYWORDS + BASEDPYTHON_KEYWORDS
}

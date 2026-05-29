package dev.basedpython.pycharm.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Additional TextAttributesKeys for semantic (annotator-driven) highlighting.
 * These are layered on top of the lexer-based [dev.basedpython.pycharm.lang.BasedPythonColors].
 * When the LSP server is running its semantic tokens take priority; these keys serve as
 * lexer-driven fallbacks for the no-LSP (e.g. free-IDE) case.
 */
object BasedPythonHighlightKeys {

    @JvmField
    val BUILTIN_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_BUILTIN_NAME",
        DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )

    @JvmField
    val SELF_PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_SELF_PARAMETER",
        DefaultLanguageHighlighterColors.KEYWORD
    )

    @JvmField
    val DECORATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DECORATOR",
        DefaultLanguageHighlighterColors.METADATA
    )

    @JvmField
    val FUNCTION_DECLARATION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FUNCTION_DECLARATION",
        DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    )

    @JvmField
    val CLASS_DECLARATION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_CLASS_DECLARATION",
        DefaultLanguageHighlighterColors.CLASS_NAME
    )

    @JvmField
    val PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_PARAMETER",
        DefaultLanguageHighlighterColors.PARAMETER
    )

    @JvmField
    val TYPE_NAME: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_TYPE_NAME",
        DefaultLanguageHighlighterColors.CLASS_REFERENCE
    )

    @JvmField
    val KEYWORD_ARGUMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_KEYWORD_ARGUMENT",
        DefaultLanguageHighlighterColors.PARAMETER
    )

    @JvmField
    val STRING_ESCAPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_STRING_ESCAPE",
        DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    )

    @JvmField
    val FSTRING_INTERP: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FSTRING_INTERP",
        DefaultLanguageHighlighterColors.IDENTIFIER
    )
}

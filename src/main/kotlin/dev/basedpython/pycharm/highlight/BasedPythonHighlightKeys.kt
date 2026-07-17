package dev.basedpython.pycharm.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Additional TextAttributesKeys for semantic (annotator-driven) highlighting.
 * These are layered on top of the lexer-based [dev.basedpython.pycharm.lang.BasedPythonColors].
 *
 * **The `by` LSP is the source of truth for semantic colour and is always preferred when
 * available.** Its semantic tokens know the types and symbols the annotator can only guess at, and
 * they track the language on their own: a new keyword or construct in `by` colours correctly with
 * no change here.
 *
 * The annotator behind these keys is a best-effort fallback for the no-LSP case, and that case is
 * **very low priority** — basedpython is not usable without `by`, so an editor without it is
 * already degraded. Don't spend effort raising the fallback's fidelity, and never let it override
 * the LSP: prefer `.textAttributes(key)`, which yields to semantic tokens, over
 * `.enforcedTextAttributes(...)`, which does not.
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

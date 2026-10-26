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

    /** A function or method where it is defined inside another function. */
    @JvmField
    val NESTED_FUNCTION_DECLARATION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_NESTED_FUNCTION_DECLARATION",
        FUNCTION_DECLARATION
    )

    /** A function used — called, or passed — rather than defined. */
    @JvmField
    val FUNCTION_CALL: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FUNCTION_CALL",
        DefaultLanguageHighlighterColors.FUNCTION_CALL
    )

    /** A method used rather than defined. */
    @JvmField
    val METHOD_CALL: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_METHOD_CALL",
        FUNCTION_CALL
    )

    /** A class used rather than defined. */
    @JvmField
    val CLASS_REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_CLASS_REFERENCE",
        DefaultLanguageHighlighterColors.CLASS_REFERENCE
    )

    /** A class or type alias named in a type expression — an annotation, a bound, a `cast`'s type. */
    @JvmField
    val ANNOTATION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_ANNOTATION",
        CLASS_REFERENCE
    )

    /** A type parameter. */
    @JvmField
    val TYPE_PARAMETER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_TYPE_PARAMETER",
        TYPE_NAME
    )

    /** A module. */
    @JvmField
    val NAMESPACE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_NAMESPACE",
        DefaultLanguageHighlighterColors.IDENTIFIER
    )

    /** A parameter or a variable bound in a function's, a lambda's or a comprehension's scope. */
    @JvmField
    val LOCAL_VARIABLE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_LOCAL_VARIABLE",
        DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    )

    /** A dunder where it is defined, `__init__`. */
    @JvmField
    val DUNDER_DEFINITION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DUNDER_DEFINITION",
        DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )

    /** A dunder used, `__name__`. */
    @JvmField
    val DUNDER_USAGE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DUNDER_USAGE",
        DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
    )

    /** `None`, `True`, `False`. */
    @JvmField
    val BUILTIN_CONSTANT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_BUILTIN_CONSTANT",
        DefaultLanguageHighlighterColors.KEYWORD
    )

    /** A docstring. */
    @JvmField
    val DOCSTRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DOCSTRING",
        DefaultLanguageHighlighterColors.DOC_COMMENT
    )

    @JvmField
    val STRING_ESCAPE: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_STRING_ESCAPE",
        DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    )

    /** The `{` and `}` of an f-string's field, read off the highlighting lexer; Python's `{` is a keyword's colour. */
    @JvmField
    val FSTRING_BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FSTRING_BRACES",
        DefaultLanguageHighlighterColors.KEYWORD
    )

    /** An f-string field's `!r`, `!s` or `!a`. */
    @JvmField
    val FSTRING_CONVERSION: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FSTRING_CONVERSION",
        DefaultLanguageHighlighterColors.KEYWORD
    )

    /** The `:` an f-string field's format spec starts with. */
    @JvmField
    val FSTRING_FORMAT_START: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_FSTRING_FORMAT_START",
        DefaultLanguageHighlighterColors.KEYWORD
    )
}

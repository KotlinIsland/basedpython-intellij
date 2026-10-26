package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors

/**
 * Pure, testable mapping from LSP semantic-token *types* (and *modifiers*) to basedpython's own
 * [TextAttributesKey]s, so theme customization flows through our color settings page instead of the
 * platform's built-in defaults.
 *
 * This object holds no platform/LSP runtime state — it is just a lookup table keyed on the token-type
 * and modifier *names* advertised by the `by` LSP server. The companion
 * [BasedPythonLspSemanticTokensSupport] adapts it to the platform's
 * `com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport` hook.
 *
 * Returning `null` from [keyFor] means "no opinion" — the platform should fall back to its own
 * default semantic-token mapping for that token.
 */
object BasedPythonSemanticTokensMapping {

    // region: standard LSP semantic token type names
    // (per the LSP spec `SemanticTokenTypes`, plus a few common extensions the `by` server emits)
    const val TYPE_NAMESPACE = "namespace"
    const val TYPE_CLASS = "class"
    const val TYPE_ENUM = "enum"
    const val TYPE_INTERFACE = "interface"
    const val TYPE_STRUCT = "struct"
    const val TYPE_TYPE_PARAMETER = "typeParameter"
    const val TYPE_TYPE = "type"
    const val TYPE_PARAMETER = "parameter"
    const val TYPE_VARIABLE = "variable"
    const val TYPE_PROPERTY = "property"
    const val TYPE_ENUM_MEMBER = "enumMember"
    const val TYPE_DECORATOR = "decorator"
    const val TYPE_EVENT = "event"
    const val TYPE_FUNCTION = "function"
    const val TYPE_METHOD = "method"
    const val TYPE_MACRO = "macro"
    const val TYPE_KEYWORD = "keyword"
    const val TYPE_MODIFIER = "modifier"
    const val TYPE_COMMENT = "comment"
    const val TYPE_STRING = "string"
    const val TYPE_NUMBER = "number"
    const val TYPE_REGEXP = "regexp"
    const val TYPE_OPERATOR = "operator"
    // endregion

    // region: standard LSP semantic token modifier names
    const val MOD_DECLARATION = "declaration"
    const val MOD_DEFINITION = "definition"
    const val MOD_READONLY = "readonly"
    const val MOD_STATIC = "static"
    const val MOD_DEPRECATED = "deprecated"
    const val MOD_ABSTRACT = "abstract"
    const val MOD_ASYNC = "async"
    const val MOD_MODIFICATION = "modification"
    const val MOD_DOCUMENTATION = "documentation"
    const val MOD_DEFAULT_LIBRARY = "defaultLibrary"
    // endregion

    /**
     * A basedpython-owned key for `readonly`/constant-like identifiers. No existing key in
     * [BasedPythonColors]/[BasedPythonHighlightKeys] models "constant", so we define one here, scoped
     * to the semantic-tokens feature, and fall its theme default back to the platform CONSTANT color.
     */
    @JvmField
    val CONSTANT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_SEMANTIC_CONSTANT",
        DefaultLanguageHighlighterColors.CONSTANT
    )

    /**
     * A basedpython-owned key for instance/static fields/properties. No existing key models a class
     * field; we define one here, defaulting to the platform INSTANCE_FIELD color.
     */
    @JvmField
    val PROPERTY: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_SEMANTIC_PROPERTY",
        DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )

    /**
     * A basedpython-owned key for a static field/property (defaults to the platform STATIC_FIELD color).
     */
    @JvmField
    val STATIC_PROPERTY: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_SEMANTIC_STATIC_PROPERTY",
        DefaultLanguageHighlighterColors.STATIC_FIELD
    )

    // region: `by`'s own token types and modifiers (docs/basedpython/features/editor.md in `by`)
    const val TYPE_SELF_PARAMETER = "selfParameter"
    const val TYPE_CLS_PARAMETER = "clsParameter"
    const val TYPE_BUILTIN_CONSTANT = "builtinConstant"
    const val MOD_FUNCTION_SCOPE = "functionScope"
    const val MOD_ANNOTATION = "annotation"
    const val MOD_DUNDER = "dunder"
    const val MOD_KEYWORD_ARGUMENT = "keywordArgument"
    // endregion

    /**
     * Map an LSP semantic-token [tokenType] (refined by [modifiers]) to the basedpython
     * [TextAttributesKey] that should colour it, or `null` to let the platform apply its own default.
     *
     * Every type and modifier `by` sends has a say, Python's colour page being the model:
     *  - `string` + `documentation` is a docstring;
     *  - `definition` tells a declaration from a use: a `def` from a call ([BasedPythonHighlightKeys.FUNCTION_CALL],
     *    [BasedPythonHighlightKeys.METHOD_CALL]), a `class` from a reference to it — a nested `def`
     *    (`functionScope`) being a declaration of its own;
     *  - `dunder` is a special name, defined or used, ahead of the kind of thing it names;
     *  - `defaultLibrary` is a builtin — `print`, `len`, `str` — wherever it is, an annotation included;
     *  - `annotation` on a class or type alias is a type annotation;
     *  - `parameter` + `keywordArgument` is the name a keyword argument is passed by;
     *  - `selfParameter` and `clsParameter` are `self` and `cls`;
     *  - `variable` + `functionScope` is a local, `readonly` a constant.
     *
     * Without these every call was coloured as a declaration, every builtin class as a class
     * declaration, `self` not at all, and a docstring as a string.
     *
     * Token-type matching is case-sensitive, matching the LSP spec's exact names.
     */
    @JvmStatic
    fun keyFor(tokenType: String, modifiers: List<String> = emptyList()): TextAttributesKey? {
        val defined = MOD_DEFINITION in modifiers || MOD_DECLARATION in modifiers
        if (MOD_DUNDER in modifiers && tokenType in NAMES) {
            return if (defined) BasedPythonHighlightKeys.DUNDER_DEFINITION else BasedPythonHighlightKeys.DUNDER_USAGE
        }
        if (MOD_DEFAULT_LIBRARY in modifiers && tokenType in NAMES) return BasedPythonHighlightKeys.BUILTIN_NAME
        return when (tokenType) {
            TYPE_NAMESPACE -> BasedPythonHighlightKeys.NAMESPACE
            TYPE_CLASS, TYPE_ENUM, TYPE_INTERFACE, TYPE_STRUCT, TYPE_TYPE -> when {
                defined -> BasedPythonHighlightKeys.CLASS_DECLARATION
                MOD_ANNOTATION in modifiers -> BasedPythonHighlightKeys.ANNOTATION
                else -> BasedPythonHighlightKeys.CLASS_REFERENCE
            }
            TYPE_TYPE_PARAMETER -> BasedPythonHighlightKeys.TYPE_PARAMETER

            TYPE_PARAMETER ->
                if (MOD_KEYWORD_ARGUMENT in modifiers) BasedPythonHighlightKeys.KEYWORD_ARGUMENT
                else BasedPythonHighlightKeys.PARAMETER
            TYPE_SELF_PARAMETER, TYPE_CLS_PARAMETER -> BasedPythonHighlightKeys.SELF_PARAMETER

            TYPE_VARIABLE -> when {
                MOD_READONLY in modifiers -> CONSTANT
                MOD_FUNCTION_SCOPE in modifiers -> BasedPythonHighlightKeys.LOCAL_VARIABLE
                else -> BasedPythonColors.IDENTIFIER
            }

            TYPE_PROPERTY ->
                if (MOD_STATIC in modifiers) STATIC_PROPERTY else PROPERTY

            TYPE_ENUM_MEMBER -> CONSTANT

            TYPE_DECORATOR -> BasedPythonHighlightKeys.DECORATOR

            TYPE_FUNCTION -> when {
                !defined -> BasedPythonHighlightKeys.FUNCTION_CALL
                MOD_FUNCTION_SCOPE in modifiers -> BasedPythonHighlightKeys.NESTED_FUNCTION_DECLARATION
                else -> BasedPythonHighlightKeys.FUNCTION_DECLARATION
            }

            TYPE_METHOD ->
                if (defined) BasedPythonHighlightKeys.FUNCTION_DECLARATION else BasedPythonHighlightKeys.METHOD_CALL

            TYPE_MACRO -> BasedPythonHighlightKeys.DECORATOR

            TYPE_KEYWORD -> BasedPythonColors.KEYWORD
            TYPE_MODIFIER -> BasedPythonColors.KEYWORD
            TYPE_BUILTIN_CONSTANT -> BasedPythonHighlightKeys.BUILTIN_CONSTANT

            TYPE_COMMENT -> BasedPythonColors.COMMENT
            TYPE_STRING ->
                if (MOD_DOCUMENTATION in modifiers) BasedPythonHighlightKeys.DOCSTRING else BasedPythonColors.STRING
            TYPE_REGEXP -> BasedPythonColors.STRING
            TYPE_NUMBER -> BasedPythonColors.NUMBER
            TYPE_OPERATOR -> BasedPythonColors.OPERATOR

            else -> null
        }
    }

    /** The token types that are names, where `dunder` and `defaultLibrary` say what kind. */
    private val NAMES = setOf(
        TYPE_CLASS, TYPE_FUNCTION, TYPE_METHOD, TYPE_VARIABLE, TYPE_PROPERTY, TYPE_NAMESPACE, TYPE_TYPE,
    )
}

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

    /**
     * Map an LSP semantic-token [tokenType] (optionally refined by [modifiers]) to the basedpython
     * [TextAttributesKey] that should colour it, or `null` to let the platform apply its own default.
     *
     * Modifier handling (only where it changes the chosen key):
     *  - `variable` + `readonly`  -> [CONSTANT]
     *  - `property`/`enumMember` + `static` -> [STATIC_PROPERTY]
     *  - `function`/`method` + `defaultLibrary` -> [BasedPythonHighlightKeys.BUILTIN_NAME]
     *
     * Token-type matching is case-sensitive, matching the LSP spec's exact names.
     */
    @JvmStatic
    fun keyFor(tokenType: String, modifiers: List<String> = emptyList()): TextAttributesKey? = when (tokenType) {
        TYPE_NAMESPACE -> BasedPythonHighlightKeys.TYPE_NAME
        TYPE_CLASS -> BasedPythonHighlightKeys.CLASS_DECLARATION
        TYPE_ENUM -> BasedPythonHighlightKeys.CLASS_DECLARATION
        TYPE_INTERFACE -> BasedPythonHighlightKeys.CLASS_DECLARATION
        TYPE_STRUCT -> BasedPythonHighlightKeys.CLASS_DECLARATION
        TYPE_TYPE, TYPE_TYPE_PARAMETER -> BasedPythonHighlightKeys.TYPE_NAME

        TYPE_PARAMETER -> BasedPythonHighlightKeys.PARAMETER

        TYPE_VARIABLE ->
            if (MOD_READONLY in modifiers) CONSTANT else BasedPythonColors.IDENTIFIER

        TYPE_PROPERTY ->
            if (MOD_STATIC in modifiers) STATIC_PROPERTY else PROPERTY

        TYPE_ENUM_MEMBER -> CONSTANT

        TYPE_DECORATOR -> BasedPythonHighlightKeys.DECORATOR

        TYPE_FUNCTION ->
            if (MOD_DEFAULT_LIBRARY in modifiers) BasedPythonHighlightKeys.BUILTIN_NAME
            else BasedPythonHighlightKeys.FUNCTION_DECLARATION

        TYPE_METHOD ->
            if (MOD_DEFAULT_LIBRARY in modifiers) BasedPythonHighlightKeys.BUILTIN_NAME
            else BasedPythonHighlightKeys.FUNCTION_DECLARATION

        TYPE_MACRO -> BasedPythonHighlightKeys.DECORATOR

        TYPE_KEYWORD -> BasedPythonColors.KEYWORD
        TYPE_MODIFIER -> BasedPythonColors.KEYWORD

        TYPE_COMMENT -> BasedPythonColors.COMMENT
        TYPE_STRING -> BasedPythonColors.STRING
        TYPE_REGEXP -> BasedPythonColors.STRING
        TYPE_NUMBER -> BasedPythonColors.NUMBER
        TYPE_OPERATOR -> BasedPythonColors.OPERATOR

        else -> null
    }
}

package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport

/**
 * Adapts [BasedPythonSemanticTokensMapping] to the IntelliJ Platform LSP semantic-tokens hook.
 *
 * The platform exposes [LspSemanticTokensSupport] (a concrete subclass of the sealed-ish
 * `LspSemanticTokensCustomizer`) with a public no-arg constructor and an overridable
 * `open fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>): TextAttributesKey?`.
 * Its default implementation returns `null`, which makes the platform fall back to its own built-in
 * token-type -> attributes mapping.
 *
 * We override it to delegate to our pure mapping, so the `by` server's semantic tokens are coloured
 * with basedpython's own [TextAttributesKey]s (and therefore become themeable through our color
 * settings page). Returning `null` for an unrecognised token type preserves the platform default for
 * that token.
 *
 * This class is a standalone, instantiable object: wiring it requires only overriding
 * `semanticTokensCustomizer` in `ByLspServerDescriptor`'s `LspCustomization`
 * (see `_integration/SEMANTICTOKENS.md`).
 */
class BasedPythonLspSemanticTokensSupport : LspSemanticTokensSupport() {

    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>): TextAttributesKey? =
        BasedPythonSemanticTokensMapping.keyFor(tokenType, tokenModifiers)
}

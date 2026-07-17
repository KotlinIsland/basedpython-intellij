package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile

/**
 * Adapts [BasedPythonSemanticTokensMapping] to the IntelliJ Platform LSP semantic-tokens hook.
 *
 * The platform exposes [LspSemanticTokensSupport] with a public no-arg constructor and an
 * overridable `getTextAttributesKey(tokenType, tokenModifiers)`. Its default returns `null`, which
 * makes the platform fall back to its own built-in token-type -> attributes mapping. We override it
 * to delegate to our pure mapping, so the `by` server's tokens are coloured with basedpython's own
 * [TextAttributesKey]s and stay themeable through our color settings page. Returning `null` for an
 * unrecognised token type preserves the platform default for that token.
 */
class BasedPythonLspSemanticTokensSupport : LspSemanticTokensSupport() {

    /**
     * Ask `by` for semantic tokens on every file we serve.
     *
     * The platform's default is `language.id == "TEXT" || language.id == "textmate"` — that is, it
     * only requests tokens for files with *no* native support, on the assumption that a language
     * with its own lexer colours itself. basedpython registers a real Language, so the default
     * silently returns false and the server is never asked: diagnostics and completion arrive
     * (those aren't gated) while every identifier stays uncoloured, which reads like semantic
     * highlighting being broken rather than never requested.
     *
     * The assumption doesn't hold here. Our lexer only knows token boundaries; whether a name is a
     * class, a parameter or a builtin — and whether a soft keyword like `cast` is acting as a
     * keyword — needs the type resolution only `by` has.
     */
    override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = true

    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>): TextAttributesKey? =
        BasedPythonSemanticTokensMapping.keyFor(tokenType, tokenModifiers)
}

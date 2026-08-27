package dev.basedpython.pycharm.lsp.typeinfo

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.ByHover
import dev.basedpython.pycharm.lsp.ByHoverRequest
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Type Info (Ctrl+Shift+P) for `.by` files, answered by the `by` language server.
 *
 * The platform's `ShowExpressionTypeHandler` asks every [ExpressionTypeProvider] registered for the
 * caret's language which expressions sit under the caret, then asks the winning provider for a hint.
 * Without a provider the action is simply dead in a `.by` file, which is what it was.
 *
 * Where the type comes from: `by` has no bespoke "provide type" request — its LSP surface is the
 * standard one — so this asks `textDocument/hover` and takes the first block of the reply, which is
 * the inferred type (or the call signature). [ByHoverMarkup] does that parsing. A second press of
 * Ctrl+Shift+P asks for "advanced information" and gets the whole hover, docstring included.
 *
 * Threading: the platform computes the hint inside `ReadAction.nonBlocking` on a background thread,
 * so blocking on the server here is allowed — see [ByHoverRequest] for what that costs and what
 * bounds it.
 */
class ByTypeInfoProvider : ExpressionTypeProvider<PsiElement>() {

    /**
     * The leaf under the caret, when it is a token `by` can have a type for.
     *
     * The PSI for `.by` is flat — one leaf per token, no expression nodes (see
     * `BasedPythonParserDefinition`) — so this is a single leaf rather than the innermost-outward
     * chain a real tree would offer. In practice hover is what closes that gap: asked about the
     * `baz` in `foo.bar.baz`, the server types the whole attribute expression, not just the name.
     *
     * Numbers and punctuation are left out on purpose: `by` deliberately reports nothing for a
     * literal expression, so offering them would trade a greyed-out action for a hint that says
     * nothing. Strings stay in — a string used as a `TypedDict` key has its own hover.
     */
    override fun getExpressionsAt(elementAt: PsiElement): List<PsiElement> {
        if (elementAt.containingFile !is BasedPythonFile) return emptyList()
        val type = elementAt.node?.elementType ?: return emptyList()
        return if (type in TYPEABLE_TOKENS) listOf(elementAt) else emptyList()
    }

    override fun getInformationHint(element: PsiElement): String = hint(element, advanced = false)

    override fun hasAdvancedInformation(): Boolean = true

    override fun getAdvancedInformationHint(element: PsiElement): String = hint(element, advanced = true)

    /** The server does the resolving, so indexing state is irrelevant here. */
    override fun isDumbAware(): Boolean = true

    override fun getErrorHint(): String = BasedPythonBundle.message("typeInfo.error.noExpression")

    private fun hint(element: PsiElement, advanced: Boolean): String {
        val markup = when (val hover = hoverAt(element)) {
            is ByHover.Markup -> hover.text
            ByHover.NoServer -> return plain("typeInfo.serverUnavailable")
            ByHover.Nothing -> return plain("typeInfo.noType")
        }
        val html = if (advanced) ByHoverMarkup.fullHtml(markup) else ByHoverMarkup.typeHtml(markup)
        return html ?: plain("typeInfo.noType")
    }

    /** Bundle messages are plain text; the hint they land in is HTML. */
    private fun plain(key: String): String = StringUtil.escapeXmlEntities(BasedPythonBundle.message(key))

    /** The server's payload for the leaf under the caret. */
    private fun hoverAt(element: PsiElement): ByHover {
        val file = element.containingFile ?: return ByHover.NoServer
        return ByHoverRequest.at(file, element.textRange.startOffset, HOVER_TIMEOUT_MS)
    }

    private companion object {
        /**
         * Bounds the wait on a server that has stopped answering. Well under the platform's 10s
         * default: this runs behind a keystroke, and a hint that arrives ten seconds later is worse
         * than one that says it could not be had.
         */
        const val HOVER_TIMEOUT_MS = 3_000

        val TYPEABLE_TOKENS = setOf(
            BasedPythonTokenTypes.IDENTIFIER,
            BasedPythonTokenTypes.KEYWORD,
            BasedPythonTokenTypes.STRING,
        )
    }
}

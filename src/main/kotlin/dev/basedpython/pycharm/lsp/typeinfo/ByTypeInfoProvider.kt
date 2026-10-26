package dev.basedpython.pycharm.lsp.typeinfo

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByHover
import dev.basedpython.pycharm.lsp.ByHoverRequest
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByExpressionType
import dev.basedpython.pycharm.lsp.ext.ByExpressionTypesParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Type Info (Ctrl+Shift+P) for `.by` files, answered by the `by` language server.
 *
 * The platform's `ShowExpressionTypeHandler` asks every [ExpressionTypeProvider] registered for the
 * caret's language which expressions sit under the caret, keeps those that hold the selection, and
 * offers a chooser when there is more than one — `spec`, `spec.partition`, `spec.partition(":")` on
 * `spec`, as PyCharm offers for a `.py`.
 *
 * ## Which expressions
 *
 * `by/expressionTypes`: every expression of the statement around the token under the caret,
 * innermost first, each with its type. The token is what the platform hands over; a selection is
 * then matched by the platform, which keeps only the expressions holding it, and only the one that
 * is exactly it when there is one. A keyword, a `def`'s name or anything else in no expression is
 * no expression, and the platform says so.
 *
 * A `by` from before the request (`MethodNotFound`), or one that does not answer, gets what this
 * did before it: the name, keyword or string token under the caret, typed by its hover.
 *
 * ## Which thread asks
 *
 * The platform asks for the expressions on the EDT, inside the action, so the request goes under a
 * modal progress that shows only if `by` is slow; asked off the EDT it simply waits. The hint is
 * computed in a background read action, and for an expression `by` has typed needs no request.
 */
class ByTypeInfoProvider : ExpressionTypeProvider<PsiElement>() {

    override fun getExpressionsAt(elementAt: PsiElement): List<PsiElement> {
        val file = elementAt.containingFile as? BasedPythonFile ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile
        val client = virtualFile?.let { byServerFor(file.project, it) }
        if (client != null) {
            when (val answer = expressionsAround(client, file, elementAt.textRange)) {
                is ByAnswer.Answer -> return answer.value
                ByAnswer.None -> return emptyList()
                ByAnswer.Failed -> Unit // the token, as before the request
            }
        }
        val type = elementAt.node?.elementType ?: return emptyList()
        return if (type in TYPEABLE_TOKENS) listOf(elementAt) else emptyList()
    }

    override fun getInformationHint(element: PsiElement): String {
        if (element is ByTypedExpression) return element.typeHtml()
        return hint(element, advanced = false)
    }

    override fun hasAdvancedInformation(): Boolean = true

    /** The hover's whole text for a name; for any other expression `by` typed, its type again. */
    override fun getAdvancedInformationHint(element: PsiElement): String {
        if (element is ByTypedExpression && !element.isName()) return element.typeHtml()
        return hint(element, advanced = true)
    }

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

    internal companion object {
        /**
         * Bounds the wait on a server that has stopped answering. Well under the platform's 10s
         * default: this runs behind a keystroke, and a hint that arrives ten seconds later is worse
         * than one that says it could not be had.
         */
        const val HOVER_TIMEOUT_MS = 3_000

        /** As long as a hover may take: the request types every expression of one statement. */
        const val EXPRESSIONS_TIMEOUT_MS = 3_000

        val TYPEABLE_TOKENS = setOf(
            BasedPythonTokenTypes.IDENTIFIER,
            BasedPythonTokenTypes.KEYWORD,
            BasedPythonTokenTypes.STRING,
        )

        /**
         * The expressions [client] says hold [range] of [file], innermost first, on whichever thread
         * this is: [ByAnswer.None] for a range in no expression, [ByAnswer.Failed] for a `by` that did
         * not answer or has no such request.
         */
        fun expressionsAround(client: LspClient, file: PsiFile, range: TextRange): ByAnswer<List<PsiElement>> {
            val virtualFile = file.originalFile.virtualFile ?: return ByAnswer.Failed
            val document = file.viewProvider.document ?: return ByAnswer.Failed
            val params = runReadActionBlocking {
                ByExpressionTypesParams(TextDocumentIdentifier(client.getDocumentIdentifier(virtualFile).uri), getLsp4jRange(document, range.startOffset, range.length))
            }
            val answer = if (ApplicationManager.getApplication().isDispatchThread) {
                runWithModalProgressBlocking(file.project, BasedPythonBundle.message("typeInfo.progress")) {
                    readAction { } // a cancellation point before the request
                    client.awaitBy("by/expressionTypes", EXPRESSIONS_TIMEOUT_MS.toLong()) { (it as ByServerExtensions).expressionTypes(params) }
                }
            } else {
                client.askBy("by/expressionTypes", EXPRESSIONS_TIMEOUT_MS) { (it as ByServerExtensions).expressionTypes(params) }
            }
            return when (answer) {
                is ByAnswer.Answer -> {
                    val expressions = runReadActionBlocking { answer.value.mapNotNull { ByTypedExpression.of(file, it) } }
                    if (expressions.isEmpty()) ByAnswer.None else ByAnswer.Answer(expressions)
                }
                // `null` is language services being off: nothing to type
                ByAnswer.None -> ByAnswer.None
                ByAnswer.Failed -> ByAnswer.Failed
            }
        }
    }
}

/**
 * One expression `by/expressionTypes` typed: where it is, and its type as the document's language
 * spells it. Equal to another for the same range and type, so a second Ctrl+Shift+P on the hint
 * on screen is the platform's "the same again", which asks for the advanced information.
 */
internal class ByTypedExpression(
    private val file: PsiFile,
    private val range: TextRange,
    val type: String,
) : ByRangeElement(file, range) {

    fun typeHtml(): String = StringUtil.escapeXmlEntities(type)

    /** Whether this expression is one name, which its hover says more about. */
    fun isName(): Boolean {
        val leaf = file.findElementAt(range.startOffset) ?: return false
        return leaf.textRange == range && leaf.node.elementType == BasedPythonTokenTypes.IDENTIFIER
    }

    override fun equals(other: Any?): Boolean =
        other is ByTypedExpression && other.file == file && other.range == range && other.type == type

    override fun hashCode(): Int = (file.hashCode() * 31 + range.hashCode()) * 31 + type.hashCode()

    override fun toString(): String = "ByTypedExpression$range: $type"

    companion object {
        /** [expression] in [file], or `null` when it has no type or does not fit the text. */
        fun of(file: PsiFile, expression: ByExpressionType): ByTypedExpression? {
            val type = expression.type ?: return null
            val document = file.viewProvider.document ?: return null
            val range = expression.range?.let { getRangeInDocument(document, it) } ?: return null
            return ByTypedExpression(file, range, type)
        }
    }
}

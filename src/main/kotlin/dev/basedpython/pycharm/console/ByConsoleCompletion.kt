package dev.basedpython.pycharm.console

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.completion.BySmartCompletion
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Completion in a basedpython console: `by`'s `textDocument/completion`, asked of the session module
 * with the input at its end ([ByConsoleDocument]), so what is offered is what the session has defined
 * and what the project's modules export, and moved back onto the console's input.
 *
 * Each item is drawn and written as smart completion's are ([BySmartCompletion]), in `by`'s order.
 * An edit `by` makes before the input — an import an auto-import completion adds at the top of the
 * module — is made at the start of the input instead, which is where the session can still take it.
 */
internal class ByConsoleCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val file = parameters.originalFile.virtualFile ?: return
        val session = ByConsoles.getInstance(parameters.originalFile.project).sessionFor(file) ?: return
        val by = session.runningBy() ?: return
        val input = parameters.editor.document
        val offset = parameters.offset
        val source = session.sessionSource()
        val module = EditorFactory.getInstance().createDocument(source + input.immutableCharSequence)
        session.document.sync(by, module.text)
        val params = CompletionParams(TextDocumentIdentifier(session.document.uri), getLsp4jPosition(module, source.length + offset))
        val answer = by.askBy<Either<List<CompletionItem>, CompletionList>>("textDocument/completion") {
            it.textDocumentService.completion(params)
        }.value ?: return
        val items = (if (answer.isLeft) answer.left else answer.right.items).mapNotNull { onInput(it, module, source.length, input) }
        result.stopHere()
        for ((index, item) in items.withIndex()) {
            val prefix = BySmartCompletion.typedPrefix(input, offset, item) ?: result.prefixMatcher.prefix
            val element = BySmartCompletion.element(item, input, offset)
            result.withPrefixMatcher(prefix).addElement(PrioritizedLookupElement.withPriority(element, (items.size - index).toDouble()))
        }
    }

    companion object {
        /**
         * [item], whose ranges are [module]'s, with its ranges in [input], which starts at [base] in
         * [module]; null when its own edit is not in the input.
         */
        fun onInput(item: CompletionItem, module: Document, base: Int, input: Document): CompletionItem? {
            fun moved(range: Range): Range? {
                val start = getOffsetInDocument(module, range.start)?.minus(base) ?: return null
                val end = getOffsetInDocument(module, range.end)?.minus(base) ?: return null
                if (start < 0 || end < start || end > input.textLength) return null
                return Range(getLsp4jPosition(input, start), getLsp4jPosition(input, end))
            }
            item.textEdit?.let { edit ->
                item.textEdit = if (edit.isLeft) {
                    Either.forLeft(TextEdit(moved(edit.left.range) ?: return null, edit.left.newText))
                } else {
                    val replace = edit.right
                    Either.forRight(InsertReplaceEdit(replace.newText, moved(replace.insert) ?: return null, moved(replace.replace) ?: return null))
                }
            }
            item.additionalTextEdits = item.additionalTextEdits?.mapNotNull { edit ->
                moved(edit.range)?.let { TextEdit(it, edit.newText) }
                    // an insertion before the input: an import, which the input can begin with
                    ?: edit.takeIf { it.range.start == it.range.end && (getOffsetInDocument(module, it.range.start) ?: base) < base }
                        ?.let { TextEdit(Range(Position(0, 0), Position(0, 0)), it.newText) }
            }
            return item
        }
    }
}

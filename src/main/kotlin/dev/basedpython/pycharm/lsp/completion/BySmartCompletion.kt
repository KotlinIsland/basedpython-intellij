package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Smart completion (Ctrl+Shift+Space) in a `.by` file: `by/smartCompletion`, the completions that fit
 * the type the position expects — on `draw(` for a `draw(shape: Shape)`, the variables holding a
 * `Shape`, `Shape` and its subclasses, and the functions returning one. Where the position expects
 * no type, `by` answers `null` and there is nothing to offer, as `.py`'s smart completion offers
 * nothing there.
 *
 * The platform's LSP completion sends `textDocument/completion` whatever the completion's type, so
 * Ctrl+Shift+Space was basic completion; [ByCompletionSupport] leaves smart completion to this. Each
 * item is drawn as the platform draws `by`'s others, matched against what its edit's range holds
 * before the caret, kept in `by`'s order, and written as its edit says, a snippet as a live template
 * ([BySnippets]). A `by` from before the request answers `MethodNotFound`, and the completion is then
 * the platform's, basic, as it was.
 */
internal class BySmartCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.SMART) return
        val file = parameters.originalFile as? BasedPythonFile ?: return
        val virtualFile = file.virtualFile ?: return
        val project = file.project
        val server = byServerFor(project, virtualFile) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val offset = parameters.offset
        val params = BySmartCompletionParams(
            TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri),
            getLsp4jPosition(document, offset),
            ByTextHash.of(document.immutableCharSequence),
        )
        val unknown = AtomicBoolean()
        val list = askingAgain<CompletionList>("by/smartCompletion") { sent ->
            server.sendRequestSync { lsp ->
                (lsp as ByServerExtensions).smartCompletion(params)
                    .whenComplete { _, error -> if (error != null && isMethodNotFound(error)) unknown.set(true) }
                    .also(sent)
            }
        }.value
        if (unknown.get()) {
            (parameters.process as? UserDataHolder)?.putUserData(UNSUPPORTED, true)
            return
        }
        result.stopHere()
        val items = list?.items.orEmpty()
        for ((index, item) in items.withIndex()) {
            val prefix = BySmartCompletion.typedPrefix(document, offset, item) ?: result.prefixMatcher.prefix
            val element = BySmartCompletion.element(item, document, offset)
            result.withPrefixMatcher(prefix).addElement(PrioritizedLookupElement.withPriority(element, (items.size - index).toDouble()))
        }
    }

    companion object {
        /** Set on a completion `by` could not answer smartly, so the platform's own runs instead. */
        val UNSUPPORTED: Key<Boolean> = Key.create("basedpython.smartCompletion.unsupported")

        /** Whether the platform's LSP completion runs for [parameters]: not for smart completion `by` answers. */
        fun platformRuns(parameters: CompletionParameters): Boolean =
            parameters.completionType != CompletionType.SMART ||
                (parameters.process as? UserDataHolder)?.getUserData(UNSUPPORTED) == true
    }
}

/** Reading and writing one of `by/smartCompletion`'s items, as the platform does `textDocument/completion`'s. */
internal object BySmartCompletion {

    /** What [item]'s edit replaces up to the caret at [offset], which is what has been typed of it. */
    fun typedPrefix(document: Document, offset: Int, item: CompletionItem): String? {
        val range = item.textEdit?.left?.range ?: item.textEdit?.right?.insert ?: return null
        val start = getOffsetInDocument(document, range.start) ?: return null
        if (start > offset) return null
        return document.immutableCharSequence.subSequence(start, offset).toString()
    }

    /**
     * The lookup element for [item]: its edit's text as what is inserted, matched also by its filter
     * text or label, drawn as `by`'s descriptor draws its completions, and written by [insert].
     */
    fun element(item: CompletionItem, document: Document, offset: Int): LookupElement {
        val newText = item.textEdit?.let { it.left?.newText ?: it.right?.newText } ?: item.insertText ?: item.label
        val inserted = StringUtil.convertLineSeparators(newText)
        val matched = StringUtil.convertLineSeparators(item.filterText ?: item.label)
        // what lies past the caret that the edit replaces too
        val beyond = (item.textEdit?.left?.range ?: item.textEdit?.right?.replace)
            ?.let { getOffsetInDocument(document, it.end) }?.minus(offset)?.coerceAtLeast(0) ?: 0
        return LookupElementBuilder.create(item, inserted)
            .withLookupString(matched)
            .withRenderer(object : LookupElementRenderer<LookupElement>() {
                override fun renderElement(element: LookupElement, presentation: LookupElementPresentation) =
                    ByCompletionSupport.renderLookupElement(item, presentation)
            })
            .withInsertHandler { context, _ -> insert(context, item, beyond) }
    }

    /**
     * Writes [item] where the lookup put its text: what its edit replaces beyond the caret removed,
     * its other edits applied where they come before it, and a snippet run as a live template.
     */
    private fun insert(context: InsertionContext, item: CompletionItem, beyond: Int) {
        val document = context.document
        if (beyond > 0) document.deleteString(context.tailOffset, minOf(context.tailOffset + beyond, document.textLength))
        item.additionalTextEdits?.takeIf { it.isNotEmpty() }?.let { edits ->
            val start = getLsp4jPosition(document, context.startOffset)
            applyTextEdits(document, edits.filter { before(it.range.start, start) })
        }
        if (item.insertTextFormat != InsertTextFormat.Snippet) return
        val snippet = document.getText(com.intellij.openapi.util.TextRange(context.startOffset, context.tailOffset))
        document.deleteString(context.startOffset, context.tailOffset)
        context.editor.caretModel.moveToOffset(context.startOffset)
        TemplateManager.getInstance(context.project).startTemplate(context.editor, BySnippets.template(context.project, BySnippets.parse(snippet)))
    }

    private fun before(a: Position, b: Position) = a.line < b.line || a.line == b.line && a.character <= b.character
}

/** `textDocument/completion`'s params in one text of the document, for `by/smartCompletion`. */
internal class BySmartCompletionParams(textDocument: TextDocumentIdentifier, position: Position, val textHash: String) :
    CompletionParams(textDocument, position)

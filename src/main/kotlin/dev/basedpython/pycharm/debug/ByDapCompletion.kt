package dev.basedpython.pycharm.debug

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XDebugSession
import com.jetbrains.dap.protocol.CompletionItem
import com.jetbrains.dap.protocol.CompletionItemType
import com.jetbrains.dap.protocol.CompletionsArguments
import com.jetbrains.dap.protocol.DapColumn
import com.jetbrains.dap.protocol.DapLine
import com.intellij.util.containers.ContainerUtil
import java.lang.ref.WeakReference
import javax.swing.Icon
import kotlin.coroutines.cancellation.CancellationException

/**
 * Completion in a `.by` session's expression fields — *Evaluate Expression* and the Watches field —
 * from the debug adapter's `completions` request, asked in the frame on screen.
 *
 * What the platform's DAP client does for its own plain-text fields, done again for basedpython
 * ones because the client's cannot be borrowed: its contributor is registered for plain text, and
 * it recognises a field by two document keys that are Kotlin-`internal`
 * (`DAP_EXPRESSION_DOCUMENT_KEY`, `DAP_EXPRESSION_SESSION_ID_KEY`), as are its helpers — so a field
 * that is not the client's own plain text gets nothing from it. The answer is read the way the
 * client reads it ([ByDapCompletionItems]), so a field offers what it offered before it was `.by`,
 * except where the client misreads where an item starts (see [ByDapCompletionItems.specs]).
 *
 * Whether there is anything to offer is the adapter's: debugpy answers `completions` from the
 * stopped frame; bpd does not advertise the request, and an adapter that does not is not asked.
 *
 * Nothing opens the popup but what opens it in any `.by` field: typing `.` after a name already
 * does (measured, in a breakpoint's field as in a session's, with this plugin's postfix templates
 * in it), and the adapter's answer arrives in that popup.
 */
internal class ByDapCompletionContributor : CompletionContributor(), DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val session = parameters.originalFile.sessionOfField() ?: return
        if (!session.isSuspended) return
        val frame = session.currentStackFrame as? DefaultDapXStackFrame ?: return
        val text = parameters.editor.document.text
        val caret = parameters.offset.coerceIn(0, text.length)
        val (line, column) = ByDapCompletionItems.caretPosition(text, caret)
        val arguments = CompletionsArguments(frame.frame.id, text, DapColumn(column), DapLine(line))
        val items = try {
            runBlockingCancellable {
                frame.executor.withSession {
                    if (capabilities.supportsCompletionsRequest == true) server.completions(arguments).targets else null
                }
            }
        } catch (e: Throwable) {
            if (e is ControlFlowException || e is CancellationException) throw e
            LOG.warn("the completions request failed", e)
            null
        } ?: return
        val specs = ByDapCompletionItems.specs(text, caret, items)
        specs.forEachIndexed { index, spec ->
            result.withPrefixMatcher(spec.matchPrefix)
                .addElement(PrioritizedLookupElement.withPriority(spec.toLookupElement(), (specs.size - index).toDouble()))
        }
    }

    private companion object {
        val LOG = Logger.getInstance(ByDapCompletionContributor::class.java)
    }
}

/**
 * The session each field's document was made for by [BySessionEditorsProvider].
 *
 * Kept here rather than on the document: a session is a platform object, but it holds the plugin's
 * debug process, its console and its handlers, and a field's document outlives both the session and
 * the plugin — the platform's workspace-model file finder keeps the last light file it was asked
 * about, and that file its document. Kept on the document, the mark held a long-stopped session and
 * through it the plugin's class loader after an unload (measured in unpinned PyCharm 263.7355). Weak
 * at both ends, so a mark keeps neither the field nor its session.
 */
@Service(Service.Level.APP)
internal class ByFieldSessions {

    private val sessions: MutableMap<Document, WeakReference<XDebugSession>> = ContainerUtil.createConcurrentWeakMap()

    fun mark(document: Document, session: XDebugSession) {
        sessions[document] = WeakReference(session)
    }

    /** The session [document] was made for, or null for any other document. */
    fun sessionOf(document: Document): XDebugSession? = sessions[document]?.get()

    companion object {
        fun getInstance(): ByFieldSessions = service()
    }
}

private fun Document.sessionOfField(): XDebugSession? = ByFieldSessions.getInstance().sessionOf(this)?.takeUnless { it.isStopped }

private fun PsiFile.sessionOfField(): XDebugSession? = viewProvider.document.sessionOfField()

/**
 * How a `completions` answer becomes lookup items, as the platform's DAP client reads it for its
 * own fields. Pure, so the arithmetic is tested without an adapter.
 */
internal object ByDapCompletionItems {

    /**
     * One completion: [insertText] replaces [prefix] (the text from where the adapter said the
     * completion starts, up to the caret), and [tailReplaceLength] characters after the caret, of
     * which the first [identifierTailLength] are the rest of the identifier the caret is in.
     */
    data class Spec(
        val insertText: String,
        val label: String,
        val typeText: String?,
        val type: CompletionItemType?,
        val prefix: String,
        val tailReplaceLength: Int,
        val identifierTailLength: Int,
        val selectionStart: Int,
        val selectionLength: Int,
    ) {
        /** The identifier at the end of [prefix]: what the typed text is matched against. */
        val matchPrefix: String get() = prefix.takeLastWhile(Character::isJavaIdentifierPart)

        /** The rest of [prefix], before [matchPrefix], which the lookup does not replace itself. */
        val prefixDeleteLength: Int get() = prefix.length - matchPrefix.length

        /**
         * How much after the inserted text is still to go. Tab has the lookup replace the
         * identifier after the caret already, so only the replacement beyond it is left.
         */
        fun tailDeleteLength(completionChar: Char): Int =
            if (completionChar == Lookup.REPLACE_SELECT_CHAR) (tailReplaceLength - identifierTailLength).coerceAtLeast(0)
            else tailReplaceLength

        /** What the adapter asked to have selected, within [insertText]. */
        fun selectionInInsertText(): TextRange {
            val start = selectionStart.coerceIn(0, insertText.length)
            return TextRange(start, (start + selectionLength.coerceAtLeast(0)).coerceAtMost(insertText.length))
        }
    }

    /** The caret as the request gives it: 1-based line and column within [text]. */
    fun caretPosition(text: String, caret: Int): Pair<Int, Int> {
        val at = caret.coerceIn(0, text.length)
        val line = text.take(at).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        return (line + 1) to (at - lineStart + 1)
    }

    /**
     * The adapter's items, ordered by `sortText` (else label); an item with neither text nor label
     * is dropped. With no `start` the completion is inserted at the caret.
     *
     * `start` is read as a 0-based column on the caret's line, taken only between the line's start
     * and the caret. The protocol says it counts from 1 when the client's columns do, as this
     * client's do, but debugpy counts from 0 whatever it is told (pydevd answers with the column it
     * converted to 0-based) and VS Code, the client adapters are written against, reads it from 0.
     * Measured with debugpy stopped in a `.by` session: `sys.vers` is answered `start=4, length=4`,
     * the `vers`. The platform's client reads it from 1, so picking `version_info` in its own field
     * replaced `.vers` and left `sysversion_info`; read from 0 it is `sys.version_info`.
     */
    fun specs(text: String, caret: Int, items: List<CompletionItem>): List<Spec> {
        val at = caret.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val caretColumn = at - lineStart
        val identifierTail = text.substring(at, lineEnd).takeWhile(Character::isJavaIdentifierPart).length
        return items
            .sortedBy { it.sortText?.takeIf(String::isNotEmpty) ?: it.label }
            .mapNotNull { item ->
                val insert = item.text?.takeIf(String::isNotEmpty) ?: item.label
                if (insert.isEmpty()) return@mapNotNull null
                val startColumn = item.start?.takeIf { it in 0..caretColumn } ?: caretColumn
                val replaceStart = lineStart + startColumn
                val length = (item.length ?: 0).coerceAtLeast(0)
                Spec(
                    insertText = insert,
                    label = item.label,
                    typeText = item.detail,
                    type = item.type,
                    prefix = text.substring(replaceStart, at),
                    tailReplaceLength = (replaceStart + length - at).coerceIn(0, lineEnd - at),
                    identifierTailLength = identifierTail,
                    selectionStart = item.selectionStart ?: insert.length,
                    selectionLength = item.selectionLength ?: 0,
                )
            }
    }

    fun icon(type: CompletionItemType?): Icon? = when (type) {
        CompletionItemType.Method -> AllIcons.Nodes.Method
        CompletionItemType.Function, CompletionItemType.Constructor -> AllIcons.Nodes.Function
        CompletionItemType.Field -> AllIcons.Nodes.Field
        CompletionItemType.Variable -> AllIcons.Nodes.Variable
        CompletionItemType.Class -> AllIcons.Nodes.Class
        CompletionItemType.Interface -> AllIcons.Nodes.Interface
        CompletionItemType.Module -> AllIcons.Nodes.Module
        CompletionItemType.Property -> AllIcons.Nodes.Property
        CompletionItemType.Enum -> AllIcons.Nodes.Enum
        CompletionItemType.Snippet -> AllIcons.Nodes.Template
        CompletionItemType.Keyword -> AllIcons.FileTypes.Any_type
        else -> null
    }

    /**
     * After the lookup has put [Spec.insertText] in place of the identifier before the caret: take
     * out what else the adapter said the completion replaces, before it and after it, and put the
     * caret and selection where the adapter asked.
     */
    fun complete(spec: Spec, context: InsertionContext) {
        val document = context.document
        val tailEnd = (context.tailOffset + spec.tailDeleteLength(context.completionChar)).coerceAtMost(document.textLength)
        if (tailEnd > context.tailOffset) document.deleteString(context.tailOffset, tailEnd)
        val headStart = (context.startOffset - spec.prefixDeleteLength).coerceAtLeast(0)
        if (headStart < context.startOffset) document.deleteString(headStart, context.startOffset)
        val selection = spec.selectionInInsertText().shiftRight(headStart)
        context.editor.caretModel.moveToOffset(selection.startOffset)
        if (!selection.isEmpty) context.editor.selectionModel.setSelection(selection.startOffset, selection.endOffset)
    }
}

private fun ByDapCompletionItems.Spec.toLookupElement(): LookupElement {
    var element = LookupElementBuilder.create(insertText)
        .withLookupString(label)
        .withPresentableText(label)
    typeText?.let { element = element.withTypeText(it) }
    ByDapCompletionItems.icon(type)?.let { element = element.withIcon(it) }
    return element.withInsertHandler { context, _ -> ByDapCompletionItems.complete(this, context) }
}

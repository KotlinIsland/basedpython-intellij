package dev.basedpython.pycharm.debug

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapInlineValueLocator
import com.intellij.platform.dap.DapPresentationSupport
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiFile
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.ExpressionInfo
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByEvaluatableExpression
import dev.basedpython.pycharm.lsp.ext.ByEvaluatableExpressionParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise

/**
 * The frames of a `.by` session, each with an evaluator that asks `by` which expression a position
 * is on ([ByDapEvaluator]).
 *
 * The frames are where it has to go. A stopped session's value tooltip, *Evaluate Expression* and
 * *Quick Evaluate* all take the evaluator of the frame on screen — the platform's backend asks
 * `XStackFrame.getEvaluator`, not the process — and `DefaultDapXStackFrame` makes its own
 * `DefaultDapXDebuggerEvaluator` rather than asking the customization's `DapExpressionSupport`,
 * which only supplies the evaluator for a session with no frame. So this is the platform's own
 * factory, making the platform's own frames, with the evaluator wrapped: everything that recognises
 * a DAP frame still does.
 */
internal object ByPresentationSupport : DapPresentationSupport() {
    override fun createPresentationFactory(inlineValueLocator: DapInlineValueLocator?): DapXDebuggerPresentationFactory =
        ByPresentationFactory(inlineValueLocator)
}

private class ByPresentationFactory(inlineValueLocator: DapInlineValueLocator?) :
    DefaultDapXDebuggerPresentationFactory(inlineValueLocator) {
    override fun createStackFrame(executor: DapSessionExecutor, thread: DapThread, frame: DapStackFrame): XStackFrame =
        ByStackFrame(this, executor, thread, frame)
}

private class ByStackFrame(
    factory: DapXDebuggerPresentationFactory,
    executor: DapSessionExecutor,
    thread: DapThread,
    frame: DapStackFrame,
) : DefaultDapXStackFrame(factory, executor, thread, frame) {
    private val byEvaluator: XDebuggerEvaluator by lazy { ByDapEvaluator(super.getEvaluator()) }

    override fun getEvaluator(): XDebuggerEvaluator = byEvaluator
}

/**
 * The platform's DAP evaluator, with the expression at a position answered by `by`.
 *
 * What the platform does on its own, measured in a stopped bpd session on
 * `total = a.b[c].d + compute(len(c))`: the caret on `b` gives `a.b` and on `c` gives `c`, but on
 * `d` it gives **nothing** — hover shows no value and *Evaluate Expression* opens empty, while
 * `a.b[c].d` evaluates to `42` when typed. The platform reads characters: it walks back over
 * identifiers and dots and gives up at the `]`. Where a reference starts is a fact about the
 * syntax tree, which is `by`'s ([ByServerExtensions.evaluatableExpression]).
 *
 * Evaluating is untouched: everything but the position question goes to [platform].
 *
 * A `by` from before the request answers `MethodNotFound`, and then the position is answered as the
 * platform answers it, which is what the session did before this existed. A `null` from `by` is an
 * answer — there is nothing to evaluate there — and so is a failed request: neither is handed to the
 * platform's character walk, which would put a guess where `by` said nothing.
 */
internal class ByDapEvaluator(
    private val platform: XDebuggerEvaluator,
    /** The `by` that answers for a file; a test's stands in for the project's. */
    private val serverFor: (Project, VirtualFile) -> LspClient? = ::byServerFor,
) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        platform.evaluate(expression, callback, expressionPosition)

    override fun evaluate(expression: XExpression, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        platform.evaluate(expression, callback, expressionPosition)

    override fun isCodeFragmentEvaluationSupported(): Boolean = platform.isCodeFragmentEvaluationSupported

    override fun formatTextForEvaluation(text: String): String = platform.formatTextForEvaluation(text)

    override fun getEvaluationMode(text: String, startOffset: Int, endOffset: Int, psiFile: PsiFile?): EvaluationMode =
        platform.getEvaluationMode(text, startOffset, endOffset, psiFile)

    /**
     * Asks `by`, off the thread that asked: a value tooltip asks on every mouse move over the editor.
     * [sideEffectsAllowed] is the platform's word for `by`'s `calls`: it is true when the user asked
     * for an evaluation and false for a tooltip.
     */
    override fun getExpressionInfoAtOffsetAsync(
        project: Project,
        document: Document,
        offset: Int,
        sideEffectsAllowed: Boolean,
    ): Promise<ExpressionInfo?> {
        val file = FileDocumentManager.getInstance().getFile(document)
        val server = file?.let { serverFor(project, it) }
            ?: return platform.getExpressionInfoAtOffsetAsync(project, document, offset, sideEffectsAllowed)
        val promise = AsyncPromise<ExpressionInfo?>()
        val job = project.service<ByEvaluatorScope>().scope.launch {
            val answer = try {
                ask(server, file, document, offset, sideEffectsAllowed)
            } catch (e: Throwable) {
                promise.setError(e)
                throw e
            }
            when (answer) {
                Asked.Unknown -> platform.getExpressionInfoAtOffsetAsync(project, document, offset, sideEffectsAllowed)
                    .onProcessed { promise.setResult(it) }
                    .onError { promise.setError(it) }
                is Asked.Range -> promise.setResult(answer.range?.let(::ExpressionInfo))
            }
        }
        promise.onError { job.cancel() }
        return promise
    }

    private sealed interface Asked {
        /** `by` answered: the expression's range in the document, or null for nothing. */
        data class Range(val range: TextRange?) : Asked

        /** `by` does not know the request. */
        data object Unknown : Asked
    }

    private suspend fun ask(
        server: LspClient,
        file: VirtualFile,
        document: Document,
        offset: Int,
        calls: Boolean,
    ): Asked {
        val (params, stamp) = readAction {
            ByServerDocuments.ensureOpen(server, server.project, file)
            val params = ByEvaluatableExpressionParams(
                server.getDocumentIdentifier(file),
                getLsp4jPosition(document, offset),
                calls,
                ByTextHash.of(document.immutableCharSequence),
            )
            params to document.modificationStamp
        }
        var unknown = false
        val answer = awaitingAgain<ByEvaluatableExpression>("by/evaluatableExpression", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                server.sendRequest { (it as ByServerExtensions).evaluatableExpression(params).also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknown = true
                throw e
            }
        }
        if (unknown) return Asked.Unknown
        val range = (answer as? ByAnswer.Answer)?.value?.range ?: return Asked.Range(null)
        return readAction {
            // the answer is about the text the hash named; an edit since then moved what it covers
            if (document.modificationStamp != stamp) return@readAction Asked.Range(null)
            val start = getOffsetInDocument(document, range.start)
            val end = getOffsetInDocument(document, range.end)
            Asked.Range(if (start != null && end != null && start < end) TextRange(start, end) else null)
        }
    }
}

/** Where [ByDapEvaluator] asks `by`: cancelled with the project, and with the plugin when it unloads. */
@Service(Service.Level.PROJECT)
internal class ByEvaluatorScope(val scope: CoroutineScope)

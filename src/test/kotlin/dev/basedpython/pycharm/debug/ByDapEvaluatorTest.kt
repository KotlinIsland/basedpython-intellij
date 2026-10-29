package dev.basedpython.pycharm.debug

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapEvaluationContext
import com.intellij.platform.dap.DapEvaluationResult
import com.intellij.platform.dap.DapScope
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapThread
import com.jetbrains.dap.protocol.FrameId
import com.intellij.platform.dap.StackFrameType
import com.intellij.platform.dap.TextPosition
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.XDebuggerUtil
import kotlinx.coroutines.runBlocking
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.ExpressionInfo
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import dev.basedpython.pycharm.lsp.ext.ByEvaluatableExpression
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.await
import org.jetbrains.concurrency.resolvedPromise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * The expression a stopped `.by` session evaluates at a position is `by`'s answer, not the
 * platform's character walk — which, on `d` in `a.b[c].d`, answers nothing.
 */
@TestFixtures
class ByDapEvaluatorTest {

    private val fixture by codeInsightFixture()

    private val line = "total = a.b[c].d + compute(len(c))\n"

    /** What the platform's own evaluator says for every position: a marker no `by` answer equals. */
    private val platformRange = TextRange(0, 5)

    private val platform = object : XDebuggerEvaluator() {
        override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
            error("not evaluated here")

        override fun getExpressionInfoAtOffsetAsync(
            project: Project,
            document: Document,
            offset: Int,
            sideEffectsAllowed: Boolean,
        ): Promise<ExpressionInfo?> = resolvedPromise(ExpressionInfo(platformRange))
    }

    private suspend fun rangeAt(marker: String, answer: (String) -> CompletableFuture<*>): String? {
        val file = fixture.addFileToProject("main.by", line)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val client = AnsweringClient(fixture.project) { method ->
            assertEquals("by/evaluatableExpression", method)
            answer(method)
        }
        val evaluator = ByDapEvaluator(platform) { _, _ -> client }
        val info = evaluator.getExpressionInfoAtOffsetAsync(fixture.project, document, line.indexOf(marker), false).await()
        return info?.let { document.getText(it.textRange) }
    }

    private fun on(start: Int, end: Int) = ByEvaluatableExpression(Range(Position(0, start), Position(0, end)))

    @Test
    fun `the range by answers is the expression, the whole reference on its last name`() = onEdt {
        val start = line.indexOf("a.b")
        assertEquals("a.b[c].d", rangeAt("d +") { answered(on(start, start + "a.b[c].d".length)) })
    }

    @Test
    fun `by answering null is nothing to evaluate, not the platform's guess`() = onEdt {
        assertNull(rangeAt("+") { answered(null) })
    }

    @Test
    fun `a by from before the request leaves the position to the platform`() = onEdt {
        assertEquals("total", rangeAt("d +") { failed(ResponseErrorCode.MethodNotFound) })
    }

    @Test
    fun `a request that fails is nothing to evaluate, not the platform's guess`() = onEdt {
        assertNull(rangeAt("d +") { failed(ResponseErrorCode.InternalError) })
    }

    /** An evaluator that says which one it is instead of evaluating. */
    private class Named(private val name: String) : XDebuggerEvaluator() {
        override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
            callback.errorOccurred("$name: $expression")
    }

    private fun evaluated(evaluator: XDebuggerEvaluator, text: String, mode: EvaluationMode): String {
        var said = ""
        evaluator.evaluate(XDebuggerUtil.getInstance().createExpression(text, null, null, mode), object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) = error("not evaluated here")
            override fun errorOccurred(errorMessage: String) { said = errorMessage }
        }, null)
        return said
    }

    @Test
    fun `the Evaluate dialog's code fragment is run as statements, and an expression as the platform sends it`() {
        val evaluator = ByDapEvaluator(Named("platform"), statements = Named("statements")) { _, _ -> null }
        assertEquals("statements: y = 7", evaluated(evaluator, "y = 7", EvaluationMode.CODE_FRAGMENT))
        assertEquals("platform: a.b", evaluated(evaluator, "a.b", EvaluationMode.EXPRESSION))
    }

    @Test
    fun `statements are evaluated in the repl context whatever context they were asked in`() {
        val asked = mutableListOf<DapEvaluationContext?>()
        val frame = object : DapStackFrame {
            override val id: FrameId get() = FrameId(1)
            override val name: String = "main"
            override val type: StackFrameType = StackFrameType.Normal
            override val thread: DapThread get() = error("no thread")
            override val source: VirtualFile? = null
            override val startPosition: TextPosition = TextPosition(1, 1)
            override val endPosition: TextPosition = TextPosition(1, 1)
            override suspend fun DapSessionContext.scopes(): List<DapScope> = emptyList()
            override suspend fun DapSessionContext.evaluate(expression: String): DapEvaluationResult {
                asked += null
                return DapEvaluationResult.Error("no context")
            }
            override suspend fun DapSessionContext.evaluate(expression: String, context: DapEvaluationContext): DapEvaluationResult {
                asked += context
                return DapEvaluationResult.Error("in $context")
            }
        }
        val repl = ByReplEvaluator(frame)
        runBlocking {
            val context = answeringContext(this) { _, _ -> Result.success(null) }
            with(repl) {
                context.evaluate("y = 7")
                context.evaluate("y = 7", DapEvaluationContext.Watch)
            }
        }
        assertEquals(listOf<DapEvaluationContext?>(DapEvaluationContext.Repl, DapEvaluationContext.Repl), asked)
    }
}

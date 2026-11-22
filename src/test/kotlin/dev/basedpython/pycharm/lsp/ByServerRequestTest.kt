package dev.basedpython.pycharm.lsp

import com.intellij.openapi.progress.ProcessCanceledException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The rule this file exists for: a cancelled request is not a failed one.
 *
 * `sendRequestSync` waits by polling `ProgressManager.checkCanceled`, so an edit arriving while the
 * daemon is blocked on `by` throws [ProcessCanceledException] out of the request — and every call
 * site used to catch it as an ordinary `Exception` and log it, which the platform reports as an
 * error in its own right (*"Control-flow exceptions … should never be logged"*) and which leaves the
 * pass running under an indicator that has already been cancelled.
 */
class ByServerRequestTest {

    @Test
    fun `cancellation is not caught`() {
        assertThrows(ProcessCanceledException::class.java) {
            answering<String>("textDocument/hover") { throw ProcessCanceledException() }
        }
    }

    @Test
    fun `coroutine cancellation is not caught either`() {
        assertThrows(CancellationException::class.java) {
            answering<String>("textDocument/hover") { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `an ordinary failure is an answer nobody gave`() {
        val answer = answering<String>("textDocument/hover") { error("the server went away") }
        assertEquals(ByAnswer.Failed, answer)
        assertNull(answer.value)
    }

    @Test
    fun `an empty answer is not a failure`() {
        val answer = answering<String>("textDocument/hover") { null }
        assertEquals(ByAnswer.None, answer)
        assertNull(answer.value)
    }

    @Test
    fun `an answer is passed through`() {
        assertEquals("int", answering("textDocument/hover") { "int" }.value)
    }

    // What `askBy` makes of `sendRequestSync`'s null, told apart by the future the request went out
    // as. `sendRequestSync` returns null for all four; each fake here does what it does.

    /** `sendRequestSync` over a request `by` answered with [error]: it logs, and returns null. */
    private fun answeredWithError(error: Throwable): ((CompletableFuture<String?>) -> Unit) -> String? = { sent ->
        sent(CompletableFuture<String?>().apply { completeExceptionally(error) })
        null
    }

    private fun contentModified() = ResponseErrorException(
        ResponseError(ResponseErrorCode.ContentModified, "content modified", null),
    )

    @Test
    fun `an empty answer from the server is still empty`() {
        val answer = askingAgain<String>("by/testItems") { sent -> sent(CompletableFuture.completedFuture(null)); null }
        assertEquals(ByAnswer.None, answer)
    }

    @Test
    fun `an error answer is a failure, not an empty answer`() {
        val internal = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "request handler panicked", null))
        assertEquals(ByAnswer.Failed, askingAgain("by/testItems", send = answeredWithError(internal)))
    }

    @Test
    fun `no answer in time is a failure, not an empty answer`() {
        assertEquals(ByAnswer.Failed, askingAgain<String>("by/testItems") { sent -> sent(CompletableFuture()); null })
    }

    @Test
    fun `a request never sent is a failure, not an empty answer`() {
        assertEquals(ByAnswer.Failed, askingAgain<String>("by/testItems") { null })
    }

    @Test
    fun `content modified is asked again, and the next answer is the answer`() {
        var asked = 0
        val answer = askingAgain<String>("by/testItems") { sent ->
            asked++
            if (asked == 1) answeredWithError(contentModified())(sent) else "test_it".also { sent(CompletableFuture.completedFuture(it)) }
        }
        assertEquals("test_it", answer.value)
        assertEquals(2, asked)
    }

    @Test
    fun `content modified without end is given up on as a failure`() {
        var asked = 0
        val answer = askingAgain<String>("by/testItems") { sent -> asked++; answeredWithError(contentModified())(sent) }
        assertEquals(ByAnswer.Failed, answer)
        assertTrue(asked > 1, "asked $asked times")
    }

    @Test
    fun `content modified is recognised through the future's wrapping`() {
        assertTrue(isContentModified(CompletionException(contentModified())))
        assertFalse(isContentModified(CompletionException(IllegalStateException("no"))))
    }

    // What `awaitBy` makes of the suspending `sendRequest`: it throws an error answer, and returns the
    // same null for a request never sent as for a server that answered null.

    @Test
    fun `awaiting, an empty answer is still empty`() = runBlocking {
        val answer = awaitingAgain<String>("textDocument/codeAction", 1_000) { sent -> sent(CompletableFuture.completedFuture(null)); null }
        assertEquals(ByAnswer.None, answer)
    }

    @Test
    fun `awaiting, a request never sent is a failure, not an empty answer`() = runBlocking {
        assertEquals(ByAnswer.Failed, awaitingAgain<String>("textDocument/codeAction", 1_000) { null })
    }

    @Test
    fun `awaiting, an error answer is a failure`() = runBlocking {
        val internal = ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "request handler panicked", null))
        assertEquals(ByAnswer.Failed, awaitingAgain<String>("textDocument/codeAction", 1_000) { throw internal })
    }

    @Test
    fun `awaiting, no answer in time is a failure`() = runBlocking {
        assertEquals(ByAnswer.Failed, awaitingAgain<String>("textDocument/codeAction", 50) { awaitCancellation() })
    }

    @Test
    fun `awaiting, content modified is asked again`() = runBlocking {
        var asked = 0
        val answer = awaitingAgain<String>("textDocument/codeAction", 1_000) { sent ->
            if (++asked == 1) throw contentModified()
            "fixed".also { sent(CompletableFuture.completedFuture(it)) }
        }
        assertEquals("fixed", answer.value)
        assertEquals(2, asked)
    }

    /** A `by` that stops cancels what it was asked, which must not end the coroutine that asked it. */
    @Test
    fun `awaiting, a request the server cancelled is a failure`() = runBlocking {
        assertEquals(ByAnswer.Failed, awaitingAgain<String>("by/runModules", 1_000) { throw CancellationException("stopped") })
    }

    @Test
    fun `awaiting, the caller's own cancellation goes on up`() = runBlocking {
        var answer: ByAnswer<String>? = null
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            answer = awaitingAgain<String>("by/runModules", 60_000) { awaitCancellation() }
        }
        caller.cancelAndJoin()
        assertNull(answer)
    }

    // What is drawn from an answer is drawn again when one comes, so a timeout is told from the rest.

    @Test
    fun `only no answer in time is a timeout`() {
        fun timeouts(send: (sent: (CompletableFuture<String?>) -> Unit) -> String?): Int {
            var timeouts = 0
            askingAgain("textDocument/inlayHint", { timeouts++ }, send)
            return timeouts
        }
        assertEquals(1, timeouts { sent -> sent(CompletableFuture()); null })
        assertEquals(1, timeouts { sent -> sent(CompletableFuture<String?>().also { it.cancel(true) }); null }, "given up on, as the platform does")
        assertEquals(0, timeouts { "answered" })
        assertEquals(0, timeouts { sent -> sent(CompletableFuture.completedFuture(null)); null })
        assertEquals(0, timeouts { null }, "never sent")
        assertEquals(0, timeouts { sent -> sent(CompletableFuture.failedFuture(IllegalStateException("no"))); null })
    }

    @Test
    fun `awaiting, only no answer in time is a timeout`() = runBlocking {
        var timeouts = 0
        awaitingAgain<String>("textDocument/inlayHint", 1_000, { timeouts++ }) { null }
        awaitingAgain<String>("textDocument/inlayHint", 1_000, { timeouts++ }) { error("no") }
        assertEquals(0, timeouts)
        awaitingAgain<String>("textDocument/inlayHint", 50, { timeouts++ }) { awaitCancellation() }
        assertEquals(1, timeouts)
    }
}

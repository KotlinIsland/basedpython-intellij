package dev.basedpython.pycharm.lsp

import com.intellij.openapi.progress.ProcessCanceledException
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
        assertEquals(ByAnswer.Failed, askingAgain("by/testItems", answeredWithError(internal)))
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
}

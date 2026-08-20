package dev.basedpython.pycharm.lsp

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

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
}

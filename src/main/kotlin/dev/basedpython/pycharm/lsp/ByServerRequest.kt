package dev.basedpython.pycharm.lsp

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.lsp.api.LspClient
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

private val LOG = Logger.getInstance("dev.basedpython.pycharm.lsp.request")

/**
 * What a request to `by` came back with, keeping "nothing to say" apart from "could not be asked".
 *
 * The two are not interchangeable, and reading one as the other is how a transient failure turns
 * into a wrong answer: a `workspace/willRenameFiles` that could not be sent is not a rename with no
 * imports to fix. Callers that genuinely treat both silences alike ask for [value] and get `null`
 * either way; callers that do not `when` over the three cases and say what each means.
 */
internal sealed interface ByAnswer<out R : Any> {

    /** What the server said, or `null` when it said nothing or was not heard from. */
    val value: R?

    /** The server answered with something. */
    data class Answer<R : Any>(override val value: R) : ByAnswer<R>

    /** The server answered, and the answer is empty. An ordinary answer. */
    data object None : ByAnswer<Nothing> {
        override val value: Nothing? get() = null
    }

    /** The request failed or timed out. Never the same as [None]. */
    data object Failed : ByAnswer<Nothing> {
        override val value: Nothing? get() = null
    }
}

/**
 * Sends [request] to `by` and waits, turning a failure into [ByAnswer.Failed] rather than throwing.
 *
 * [what] is the LSP method name, and it is what the log line says — `textDocument/hover`, not prose
 * about hovering, so that a report can be matched against the server's own trace.
 *
 * ## Why cancellation is not a failure
 *
 * `LspClient.sendRequestSync` waits by polling `ProgressManager.checkCanceled`, which is what makes
 * it safe to block a background read action on the server: an edit arriving mid-request cancels the
 * wait instead of queueing behind it. Cancellation arrives as `ProcessCanceledException`, which is a
 * `RuntimeException`, so a plain `catch (e: Exception)` catches it — and every call site here had
 * one.
 *
 * Swallowing it is wrong twice over. The platform requires control-flow exceptions to propagate, and
 * `Logger.ensureNotControlFlow` reports the log call itself as an error the moment one is logged; and
 * the caller carries on doing work under an indicator that has already been cancelled, instead of
 * unwinding. So they are rethrown, by the same test the platform's logger applies.
 *
 * Threading: background only, like the request it wraps.
 */
internal fun <R : Any> LspClient.askBy(
    what: String,
    timeoutMs: Int = LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
    request: (LanguageServer) -> CompletableFuture<R?>,
): ByAnswer<R> = answering(what) { sendRequestSync(timeoutMs, request) }

/**
 * [askBy] without the server, so that the rule above can be stated in a test rather than only in a
 * comment: the swallowed cancellation is what this exists to prevent, and nothing about it needs a
 * running `by` to show.
 */
internal fun <R : Any> answering(what: String, request: () -> R?): ByAnswer<R> =
    try {
        request()?.let { ByAnswer.Answer(it) } ?: ByAnswer.None
    } catch (e: Exception) {
        // Cancellation is not a failure: it is the pass being told to stop, and it must go on up.
        if (e is ControlFlowException || e is CancellationException) throw e
        LOG.warn("$what request to `by` failed", e)
        ByAnswer.Failed
    }

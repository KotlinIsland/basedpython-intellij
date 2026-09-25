package dev.basedpython.pycharm.lsp

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.lsp.api.LspClient
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

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
 * ## Why a `null` from `sendRequestSync` is not an answer by itself
 *
 * `sendRequestSync` returns `null` for four different things: the server answered `null`, the server
 * was not running so nothing was sent, no answer came within [timeoutMs], and the server answered
 * with an **error** — which it logs as a warning and then drops. Read as they come, every one of the
 * last three was [ByAnswer.None], "answered, and empty", and callers remember that: a file whose
 * `by/testItems` failed was cached as a file with no tests.
 *
 * The commonest error is not a fault at all. `by` answers `ContentModified` when an edit lands while
 * it is working on a request — any edit, to any file, because the database it reads is the whole
 * project's — and LSP's instruction for that answer is to ask again. So the future the request went
 * out as is kept and looked at: an error that says the content changed is asked again, up to
 * [CONTENT_MODIFIED_ATTEMPTS] times; any other error, a timeout, or a request never sent is
 * [ByAnswer.Failed].
 *
 * Threading: background only, like the request it wraps.
 */
internal fun <R : Any> LspClient.askBy(
    what: String,
    timeoutMs: Int = LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
    request: (LanguageServer) -> CompletableFuture<R?>,
): ByAnswer<R> = askingAgain(what) { sent -> sendRequestSync(timeoutMs) { server -> request(server).also(sent) } }

/**
 * [askBy] without the server: [send] sends the request once, hands the future it went out as to the
 * callback it is given, and returns what `sendRequestSync` returned.
 */
internal fun <R : Any> askingAgain(
    what: String,
    send: (sent: (CompletableFuture<R?>) -> Unit) -> R?,
): ByAnswer<R> {
    repeat(CONTENT_MODIFIED_ATTEMPTS) {
        val sent = AtomicReference<CompletableFuture<R?>>()
        val answer = answering(what) { send(sent::set) }
        if (answer != ByAnswer.None) return answer
        // Which of the four nulls this was. The future is complete whenever `sendRequestSync` returned
        // because the server replied — its own result is completed off this one.
        val future = sent.get() ?: return ByAnswer.Failed.also {
            LOG.debug("$what request was not sent: no server was running")
        }
        // the platform has logged a timeout already
        if (!future.isDone) return ByAnswer.Failed
        val error = errorOf(future) ?: return ByAnswer.None
        if (!isContentModified(error)) {
            // the platform has logged the error as a warning already; this says which request it was
            LOG.debug("$what request was answered with an error: $error")
            return ByAnswer.Failed
        }
        LOG.debug("$what request was overtaken by an edit; asking again")
    }
    LOG.info("$what request was overtaken by an edit $CONTENT_MODIFIED_ATTEMPTS times running")
    return ByAnswer.Failed
}

/**
 * How many times one ask is sent before a run of `ContentModified` answers is given up on as
 * [ByAnswer.Failed]. Each of them means an edit reached `by` while it worked, so a run of them is
 * someone typing; the bound is there so that a caller not under a cancellable indicator does not ask
 * for as long as they type.
 */
private const val CONTENT_MODIFIED_ATTEMPTS = 5

/** What [future], which is done, failed with; null when it completed normally. */
private fun errorOf(future: CompletableFuture<*>): Throwable? =
    try {
        future.getNow(null)
        null
    } catch (e: CompletionException) {
        e.cause ?: e
    } catch (e: CancellationException) {
        e
    }

/** Whether [error] is the server saying an edit reached it while it worked on the request. */
internal fun isContentModified(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it is ResponseErrorException && it.responseError.code == ResponseErrorCode.ContentModified.value
    }

/** Whether [error] is the server saying it has no such request: a `by` from before the request. */
internal fun isMethodNotFound(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it is ResponseErrorException && it.responseError.code == ResponseErrorCode.MethodNotFound.value
    }

/**
 * [askBy] for a coroutine: suspends rather than blocks, and stops waiting the moment the caller is
 * cancelled — which a blocked thread outside any progress indicator cannot be told.
 *
 * The suspending `LspClient.sendRequest` throws an error answer rather than dropping it, but it
 * still returns `null` both for a server that answered `null` and for a request it never sent
 * because no server was running; the future the request went out as tells those two apart here, as
 * it does for [askBy]. No answer within [timeoutMs] is [ByAnswer.Failed]; only a cancellation of the
 * caller itself propagates. A `ContentModified` answer is asked again, as [askBy] does, within the
 * same [timeoutMs].
 */
internal suspend fun <R : Any> LspClient.awaitBy(
    what: String,
    timeoutMs: Long = LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong(),
    request: (LanguageServer) -> CompletableFuture<R?>,
): ByAnswer<R> = awaitingAgain(what, timeoutMs) { sent -> sendRequest { server -> request(server).also(sent) } }

/**
 * [awaitBy] without the server: [send] sends the request once, hands the future it went out as to
 * the callback it is given, and returns what the suspending `sendRequest` returned or throws what it
 * threw.
 */
internal suspend fun <R : Any> awaitingAgain(
    what: String,
    timeoutMs: Long,
    send: suspend (sent: (CompletableFuture<R?>) -> Unit) -> R?,
): ByAnswer<R> = withTimeoutOrNull<ByAnswer<R>>(timeoutMs) {
    repeat(CONTENT_MODIFIED_ATTEMPTS) {
        try {
            val sent = AtomicReference<CompletableFuture<R?>>()
            val answer = send(sent::set)
            return@withTimeoutOrNull when {
                answer != null -> ByAnswer.Answer(answer)
                sent.get() == null -> ByAnswer.Failed.also { LOG.debug("$what request was not sent: no server was running") }
                else -> ByAnswer.None
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isMethodNotFound(e)) {
                // A `by` from before the request, which is not a fault: what it means is the
                // caller's to say — Go to Super says `by` needs updating, a gutter icon says nothing.
                LOG.debug("$what is not a request this server answers")
                return@withTimeoutOrNull ByAnswer.Failed
            }
            if (!isContentModified(e)) {
                LOG.warn("$what request failed", e)
                return@withTimeoutOrNull ByAnswer.Failed
            }
            LOG.debug("$what request was overtaken by an edit; asking again")
        }
    }
    LOG.info("$what request was overtaken by an edit $CONTENT_MODIFIED_ATTEMPTS times running")
    ByAnswer.Failed
} ?: ByAnswer.Failed.also { LOG.info("$what request got no answer within $timeoutMs ms") }

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
        LOG.warn("$what request failed", e)
        ByAnswer.Failed
    }

package dev.basedpython.pycharm.debug.recompose

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.util.concurrency.ThreadingAssertions
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

private val LOG = Logger.getInstance(ByRecompositionRequests::class.java)

/** How long bpd gets to answer before a request is given up on; the data-flow timeout. */
private const val TIMEOUT_MS = 2_000L

/**
 * What one bpd session can be asked about its compose runtime.
 *
 * An interface so [ByRecompositionSession] can be driven by a test without a debug adapter behind
 * it; [ByRecompositionRequests] is the one real implementation. Both calls block for up to the
 * timeout, and so are refused on the EDT.
 */
internal interface ByRecompositionLink {
    /** `bpd/recompositions {}` — the ring as it stands. */
    fun pull(): ByRecompositionAnswer

    /** `bpd/watchRecompositions {on}` — start or stop the stream of `bpd/recomposition` events. */
    fun watch(on: Boolean): ByRecompositionAnswer
}

/** What a request came back with. */
internal sealed interface ByRecompositionAnswer {
    /** A body, as lsp4j built it — a `JsonObject` because the declared type asked for one. */
    data class Answered(val body: JsonObject) : ByRecompositionAnswer

    /**
     * bpd said no, in a sentence: the program has not imported the runtime, tracing is off, the
     * trace format is one this bpd does not read, or a record slot has a type it does not allow.
     */
    data class Refused(val sentence: String) : ByRecompositionAnswer

    /**
     * No answer at all — no adapter server, no answer in time, or a failure that is not a refusal —
     * and [why], in a sentence, because "no answer" shown as "nothing has run" would be a wrong
     * statement about the program.
     */
    data class Unavailable(val why: String) : ByRecompositionAnswer

    companion object {
        /** No answer within [TIMEOUT_MS]: `bpd did not answer within 2 s`. */
        fun timedOut(): Unavailable =
            Unavailable(BasedPythonBundle.message("recompose.unanswered.timeout", TIMEOUT_MS / 1_000L))

        /** The adapter answered with no body, or there was no adapter server to ask. */
        fun noBody(): Unavailable = Unavailable(BasedPythonBundle.message("recompose.unanswered.noBody"))

        /** The request failed for a reason that is not the adapter refusing. */
        fun failed(e: Throwable): Unavailable =
            Unavailable(BasedPythonBundle.message("recompose.unanswered.failed", e.message ?: e.javaClass.simpleName))
    }
}

/**
 * The one sender of `bpd/recompositions` and `bpd/watchRecompositions`.
 *
 * Custom DAP requests, so they travel the way `bpd/facts` does: declared on
 * [ByDebugProtocolServer] and sent inside a command on the session's [DapCommandProcessor], which
 * is the only context that holds the adapter's server. Session-scoped rather than frame-scoped
 * because the trace is the program's, not a frame's — any held thread answers.
 */
internal class ByRecompositionRequests(private val commandProcessor: DapCommandProcessor) : ByRecompositionLink {

    override fun pull(): ByRecompositionAnswer =
        send("bpd/recompositions") { it.recompositions(ByRecompositionsArguments()).await() }

    override fun watch(on: Boolean): ByRecompositionAnswer =
        send("bpd/watchRecompositions") { it.watchRecompositions(ByWatchRecompositionsArguments(on)).await() }

    private fun send(
        name: String,
        request: suspend (ByDebugProtocolServer) -> JsonObject?,
    ): ByRecompositionAnswer {
        ThreadingAssertions.assertBackgroundThread()
        return runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    val body = commandProcessor.submitCommandAsync {
                        val server = server as? ByDebugProtocolServer ?: return@submitCommandAsync null
                        request(server)
                    }.await()
                    if (body == null) ByRecompositionAnswer.noBody() else ByRecompositionAnswer.Answered(body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val refusal = refusalOf(e)
                    if (refusal != null) {
                        // bpd's own sentence, which the caller shows and logs once. Not an error: a
                        // program that has no compose runtime is an ordinary program
                        ByRecompositionAnswer.Refused(refusal)
                    } else {
                        LOG.warn("$name failed", e)
                        ByRecompositionAnswer.failed(e)
                    }
                }
            } ?: ByRecompositionAnswer.timedOut()
        }
    }

    companion object {
        /**
         * The adapter's own sentence behind a failed request, or null when the failure is not the
         * adapter refusing.
         *
         * lsp4j completes the request's future with a [ResponseErrorException] carrying the error
         * response, and depending on who awaited it that arrives bare, inside a
         * [CompletionException], or inside an [ExecutionException]. The sentence is the same one
         * whichever way it came.
         */
        fun refusalOf(e: Throwable): String? {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is ResponseErrorException) return cause.responseError?.message ?: cause.message
                cause = if (cause is CompletionException || cause is ExecutionException) cause.cause else null
            }
            return null
        }
    }
}

/**
 * The `bpd/recompositions` request body, which is empty: the trace is the program's, and there is
 * nothing to choose. A class rather than nothing because lsp4j serialises the argument it is given,
 * and an object with no fields is `{}`.
 */
class ByRecompositionsArguments

/**
 * The `bpd/watchRecompositions` request body.
 *
 * The field name is the wire format — bpd reads `arguments["on"]` by name — so a rename here is a
 * request it will not understand.
 */
data class ByWatchRecompositionsArguments(val on: Boolean)

package dev.basedpython.pycharm.debug.recompose

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.util.concurrency.ThreadingAssertions
import com.jetbrains.dap.protocol.RequestType
import dev.basedpython.pycharm.debug.ByDapRequests
import dev.basedpython.pycharm.debug.send
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    /** A body that is a JSON object, which is every answer bpd gives these requests. */
    data class Answered(val body: JsonObject) : ByRecompositionAnswer

    /**
     * bpd said no, in a sentence: the program has not imported the runtime, tracing is off, the
     * trace format is one this bpd does not read, or a record slot has a type it does not allow.
     */
    data class Refused(val sentence: String) : ByRecompositionAnswer

    /**
     * No answer at all — no body, no answer in time, or a failure that is not a refusal — and
     * [why], in a sentence, because "no answer" shown as "nothing has run" would be a wrong
     * statement about the program.
     */
    data class Unavailable(val why: String) : ByRecompositionAnswer

    companion object {
        /** No answer within [TIMEOUT_MS]: `bpd did not answer within 2 s`. */
        fun timedOut(): Unavailable =
            Unavailable(BasedPythonBundle.message("recompose.unanswered.timeout", TIMEOUT_MS / 1_000L))

        /** The adapter answered with no body, or with one that is not an object. */
        fun noBody(): Unavailable = Unavailable(BasedPythonBundle.message("recompose.unanswered.noBody"))

        /** The request failed for a reason that is not the adapter refusing. */
        fun failed(e: Throwable): Unavailable =
            Unavailable(BasedPythonBundle.message("recompose.unanswered.failed", e.message ?: e.javaClass.simpleName))
    }
}

/**
 * The one sender of `bpd/recompositions` and `bpd/watchRecompositions`.
 *
 * Custom DAP requests, so they travel the way `bpd/facts` does: one of [ByDapRequests], sent inside
 * a command on the session's [DapSessionExecutor], which is the only context that holds the
 * adapter's endpoint. Session-scoped rather than frame-scoped because the trace is the program's,
 * not a frame's — any held thread answers.
 */
internal class ByRecompositionRequests(private val executor: DapSessionExecutor) : ByRecompositionLink {

    override fun pull(): ByRecompositionAnswer =
        send { ask(ByDapRequests.recompositions, ByRecompositionsArguments.toJson()) }

    override fun watch(on: Boolean): ByRecompositionAnswer =
        send { ask(ByDapRequests.watchRecompositions, ByWatchRecompositionsArguments(on).toJson()) }

    private fun send(request: suspend DapSessionContext.() -> ByRecompositionAnswer): ByRecompositionAnswer {
        ThreadingAssertions.assertBackgroundThread()
        return runBlocking {
            withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    executor.withSession { request() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The work was not taken at all: an executor whose session has stopped refuses it
                    LOG.warn("a recomposition request could not be sent", e)
                    ByRecompositionAnswer.failed(e)
                }
            } ?: ByRecompositionAnswer.timedOut()
        }
    }

    companion object {
        /**
         * Send [request] from inside a command and read what came back.
         *
         * Every failure is answered here rather than left to leave the command: one that does is
         * shown to the user by the platform when the adapter marked it `showUser`, and bpd marks
         * every refusal so — while a refusal is an ordinary answer to these requests (a program with
         * no compose runtime), which the window already says in its own place.
         */
        suspend fun DapSessionContext.ask(
            request: RequestType<JsonObject, JsonElement?>,
            arguments: JsonObject,
        ): ByRecompositionAnswer = try {
            send(request, arguments)
                ?.let { ByRecompositionAnswer.Answered(it) }
                ?: ByRecompositionAnswer.noBody()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val refusal = ByDapRequests.refusalOf(e)
            if (refusal != null) {
                // bpd's own sentence, which the caller shows and logs once. Not an error: a
                // program that has no compose runtime is an ordinary program
                ByRecompositionAnswer.Refused(refusal)
            } else {
                LOG.warn("${request.command} failed", e)
                ByRecompositionAnswer.failed(e)
            }
        }
    }
}

/**
 * The `bpd/recompositions` request body, which is empty: the trace is the program's, and there is
 * nothing to choose. `{}` rather than no arguments at all, which is what this request has always
 * carried.
 */
object ByRecompositionsArguments {
    fun toJson(): JsonObject = buildJsonObject {}
}

/**
 * The `bpd/watchRecompositions` request body.
 *
 * The field name is the wire format — bpd reads `arguments["on"]` by name — so a rename here is a
 * request it will not understand.
 */
data class ByWatchRecompositionsArguments(val on: Boolean) {
    fun toJson(): JsonObject = buildJsonObject { put("on", on) }
}

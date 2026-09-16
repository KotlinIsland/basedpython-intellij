package dev.basedpython.pycharm.debug.dfa

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.frame.XStackFrame
import dev.basedpython.pycharm.debug.ByDapRequests
import dev.basedpython.pycharm.debug.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private val LOG = Logger.getInstance(ByDataFlowRequests::class.java)

/**
 * Asking the debugger what it can prove about a frame's names.
 *
 * The one place the plugin sends `bpd/facts`. It is a custom DAP request, so it travels the way
 * `setPydevdSourceMap` does: one of [ByDapRequests], sent inside a command on the frame's own
 * executor, which is the only context that holds the adapter's endpoint.
 */
object ByDataFlowRequests {

    /**
     * What the adapter can prove about `names` in `frame`, or `null` when it cannot be asked.
     *
     * `null` covers three things that are all the same from here: the frame is not a DAP frame,
     * the adapter does not implement the request, or it did not answer in time. None of them is
     * worth reporting to the user — a debugpy session simply has no facts, and the feature draws
     * nothing rather than complaining about a debugger that is working fine.
     *
     * Suspends rather than blocks, so the stop that asked can cancel it when the program moves on.
     */
    suspend fun facts(frame: XStackFrame, names: List<String>, timeoutMs: Long): JsonObject? {
        val dap = frame as? DefaultDapXStackFrame ?: return null
        val arguments = ByFactsArguments(
            frameId = dap.frame.id.id,
            names = names,
            // Deeper than the default would be paying for paths nobody wrote.
            // `self.config.timeout` is three, and source a person is reading
            // does not go much past it
            limit = ByFactsLimit(depth = 3),
        ).toJson()

        return withTimeoutOrNull(timeoutMs) {
            try {
                // Caught inside the command as well as outside it: a failure that leaves the
                // command is one the platform shows the user when the adapter marked it
                // `showUser`, and bpd marks every refusal so — a notification on every stop for a
                // feature built to shrug at no answer
                dap.executor.withSession {
                    try {
                        send(ByDapRequests.facts, arguments)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        unanswered(e)
                    }
                }
            } catch (e: CancellationException) {
                // The stop was left, or the timeout came: neither is a failure of the request
                throw e
            } catch (e: Exception) {
                // The work was not taken at all: an executor whose session has stopped refuses it
                unanswered(e)
            }
        }
    }

    private fun unanswered(e: Exception): JsonObject? {
        if (ByDapRequests.refusalOf(e) != null) {
            // The ordinary case, and not an error: an adapter that does not implement the
            // request answers `unknown command` as an error response. debugpy is one
            LOG.debug("the debug adapter does not answer bpd/facts", e)
        } else {
            LOG.warn("bpd/facts failed", e)
        }
        return null
    }
}


/**
 * The `bpd/facts` request body.
 *
 * Field names are the wire format — `bpd`'s DAP adapter reads them by these names — so a rename
 * here is a request it will not understand.
 */
data class ByFactsArguments(
    val frameId: Int,
    val names: List<String>,
    val limit: ByFactsLimit,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("frameId", frameId)
        putJsonArray("names") { names.forEach { add(it) } }
        putJsonObject("limit") { put("depth", limit.depth) }
    }
}

/** How much one fact may cost. */
data class ByFactsLimit(
    /** How many segments of a dotted path to follow. */
    val depth: Int,
)

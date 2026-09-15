package dev.basedpython.pycharm.util

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Runs [action] once requests have stopped arriving for [quiet], never two at a time.
 *
 * Requests are a conflated channel read by one coroutine, so there is no job handle for two
 * threads to race over: however many arrive, and from wherever, at most one run is pending while
 * one is in progress, and a request made during a run produces exactly one more run after it.
 *
 * Lives as long as [scope]; a service's injected scope makes that the service's lifetime, which
 * ends with its project and with the plugin.
 */
internal class Debounced(
    scope: CoroutineScope,
    private val quiet: Duration,
    private val action: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                // Every request that arrives inside the window restarts it.
                while (withTimeoutOrNull(quiet) { requests.receive() } != null) Unit
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One failed run must not stop every later request from being served.
                    LOG.warn("debounced action failed", e)
                }
            }
        }
    }

    /** Asks for a run. Safe from any thread; never blocks. */
    fun request() {
        requests.trySend(Unit)
    }

    private companion object {
        val LOG = Logger.getInstance(Debounced::class.java)
    }
}

package dev.basedpython.pycharm.debug

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapMessageDirection
import com.intellij.platform.dap.DapObserversBuilder
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapTrafficObserver
import com.jetbrains.dap.protocol.EventType
import dev.basedpython.pycharm.debug.recompose.ByRecompositions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Registers every `.by` file's source map with pydevd, and tells bpd which of its events this
 * client reads, ahead of the breakpoints and `configurationDone` the platform sends.
 *
 * ## what has to come first
 *
 * The platform answers the adapter's `initialized` event by releasing its configuration sender,
 * which sends `setBreakpoints` for every file and then `configurationDone` — and `configurationDone`
 * is what lets the program run. So everything here has to reach the adapter before that request, or
 * the code that runs first runs without it:
 *
 *  - **the source maps**, or a breakpoint on a `.by` line in the first code the program runs is on
 *    a line pydevd cannot place. Ahead of `setBreakpoints` too, though that half would heal itself:
 *    pydevd's `set_source_mapping` ends in `reapply_breakpoints`, which translates every breakpoint
 *    it has already been sent through the new map
 *  - **`bpd/understands`**, or bpd narrates on the console what it also sends as events, for
 *    whatever happens before it arrives
 *  - **a recomposition watch** that was on before the session began, so it sees the first frame
 *
 * *Reach* rather than *be answered*, because both adapters handle a connection's requests one at a
 * time in the order they arrive — pydevd on its reader thread, which applies a source map inline
 * before it reads the next message — so a request written ahead of `configurationDone` takes effect
 * ahead of it.
 *
 * ## what guarantees it
 *
 * Nothing in the platform's API says so; the order comes from how its session commands run, which is
 * one at a time on one thread, in the order they were posted, each only up to its first suspension.
 * Two things together make every request here be written first:
 *
 *  1. **this sees `initialized` before the platform does.** It is a [DapTrafficObserver], which the
 *     platform calls with each incoming message before handing it to the event handlers, so the
 *     command it posts is queued ahead of the one the platform's own handler posts to release the
 *     configuration sender. Anything that sends the breakpoints is posted later still, by the
 *     sender that release wakes
 *  2. **every request is started in that command's one turn.** Each is a child coroutine launched
 *     before the command first suspends, so each is queued ahead of the release too, and each writes
 *     its request in its own first turn. A request that waits on something first — the platform's
 *     endpoint holds everything until `initialize` is answered, which bpd's `initialized` can beat
 *     — is resumed from the command that answers `initialize`, still ahead of anything posted later
 *
 * Sending them one after another instead, each after the last was answered, is overtaken by
 * `setBreakpoints` at the first answer awaited. Holding the command's thread until they are all
 * answered is worse, and was measured: the platform answers `initialize` on that same thread, so the
 * requests waited on the thread that was waiting on them and the session never started.
 *
 * A failed map is logged and skipped rather than fatal. Losing one file's mapping costs that file's
 * breakpoints; aborting the session would cost all of them.
 */
internal class BySourceMapPublisher(
    /** The session to post to; null before the platform has created it, which it never is by `initialized`. */
    private val executor: () -> DapSessionExecutor?,
    /** The maps to register, known once the adapter's connection has been opened. */
    private val mappings: () -> List<ByFileMapping>,
    /**
     * Started with the other requests, after `bpd/understands` and before the maps, and so written
     * ahead of the platform's breakpoints and `configurationDone` as long as its first request is
     * the first thing it waits on. A watch on the compose runtime is sent here, so it sees the first
     * frame.
     */
    private val onReady: suspend DapSessionContext.() -> Unit = {},
) : DapTrafficObserver {

    override fun observe(direction: DapMessageDirection, message: JsonElement) {
        if (direction != DapMessageDirection.Incoming || !isInitializedEvent(message)) return
        val executor = executor()
        if (executor == null) {
            LOG.warn("the adapter is initialized before the session exists; nothing was published to it")
            return
        }
        executor.post { publish() }
    }

    /** Every request started in this one turn; see the class's account of why. */
    private suspend fun DapSessionContext.publish() = coroutineScope {
        val context = this@publish
        // Before the maps rather than after, because it is the cheaper request and both are ahead
        // of the breakpoints either way. bpd answers it; debugpy answers `unknown command`, which
        // costs nothing — it has no narration to switch off.
        launch {
            try {
                context.send(ByDapRequests.understands, ByUnderstandsArguments(UNDERSTOOD_EVENTS).toJson())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.debug("the debug adapter does not answer bpd/understands", e)
            }
        }
        launch {
            try {
                context.onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever was asked for at this moment is an extra; the session must start
                LOG.warn("a request at the adapter's initialisation failed", e)
            }
        }
        for (mapping in mappings()) {
            launch {
                try {
                    context.send(ByDapRequests.setPydevdSourceMap, mapping.toRequest().toJson())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("setPydevdSourceMap failed for ${mapping.source}", e)
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BySourceMapPublisher::class.java)

        /**
         * Every event of bpd's this plugin reads, and therefore every narration it turns off.
         *
         * Naming one it does not in fact handle would be asking for silence about something nobody
         * is listening to, which is the one way this can lose information. Each name here has an
         * observer registered from [bpdEvents]; `ByRecompositionsWireTest` pins the pair.
         */
        internal val UNDERSTOOD_EVENTS: List<String> = listOf(ByMoved.EVENT, ByRecompositions.EVENT)

        /** Whether [message] is the adapter's `initialized` event, read off the envelope. */
        internal fun isInitializedEvent(message: JsonElement): Boolean {
            val envelope = message.objOrNull() ?: return false
            return envelope.string("type") == "event" && envelope.string("event") == "initialized"
        }
    }
}

/**
 * Receive bpd's own events — the ones [BySourceMapPublisher.UNDERSTOOD_EVENTS] names.
 *
 * DAP event bodies are open JSON objects and an adapter may name its own events; the platform's
 * client dispatches any event name an observer is registered for, reading its body with the
 * serializer the [EventType] carries. [JsonElement] is that serializer rather than [JsonObject],
 * because a body that does not decode is logged by the platform as an error: an event from a newer
 * bpd this cannot read should cost the report, never put an error in front of the user.
 *
 * Nothing here throws, for the same reason. The observers run on the connection's reader, and are
 * handed on parsed, which is all that happens there.
 *
 * A list rather than registrations made here, so the same events can be bound to the platform's
 * client in a test as the descriptor binds them to a session: [DapObserversBuilder.event] with each
 * one's [ByBpdEvent.type] and [ByBpdEvent.observe].
 */
internal fun bpdEvents(
    onMoved: (ByMoved) -> Unit,
    onRecomposed: (dev.basedpython.pycharm.debug.recompose.ByEvent) -> Unit,
): List<ByBpdEvent> = listOf(
    ByBpdEvent(ByMoved.EVENT) { ByMoved.parse(it)?.let(onMoved) },
    ByBpdEvent(ByRecompositions.EVENT) { ByRecompositions.parseEvent(it)?.let(onRecomposed) },
)

/** One of bpd's events, and what reading it does. */
internal class ByBpdEvent(name: String, private val read: (JsonObject?) -> Unit) {
    val type: EventType<JsonElement> = EventType(name, JsonElement.serializer())

    fun observe(body: JsonElement) {
        try {
            read(body.objOrNull())
        } catch (e: RuntimeException) {
            Logger.getInstance(BySourceMapPublisher::class.java).warn("could not read a ${type.event} event", e)
        }
    }
}

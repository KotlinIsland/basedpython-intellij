package dev.basedpython.pycharm.debug

import com.intellij.openapi.diagnostic.Logger
import com.jetbrains.dap.impl.DapClientHandlersBuilder
import com.jetbrains.dap.protocol.EventType
import dev.basedpython.pycharm.debug.recompose.ByEvent
import dev.basedpython.pycharm.debug.recompose.ByRecompositions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** bpd's own events, as this plugin reads them and as it names them to bpd. */
internal object ByBpdEvents {

    /**
     * Every event of bpd's this plugin reads, and therefore every narration it turns off.
     *
     * Sent as the `launch`'s `understands`: bpd narrates on the console what it also sends as these
     * events, for a client that has not said it reads them, and the launch is the one request that
     * cannot reach bpd after the program has started. Naming one this plugin does not in fact
     * handle would be asking for silence about something nobody is listening to, which is the one
     * way this can lose information: each name here has a reader in [bpdEvents], and
     * `ByRecompositionsWireTest` pins the pair.
     */
    val UNDERSTOOD: List<String> = listOf(ByMoved.EVENT, ByRecompositions.EVENT)

    /** Binds every one of [events] to the platform's client, which dispatches each by its name. */
    fun register(handlers: DapClientHandlersBuilder, events: List<ByBpdEvent>) {
        events.forEach { event -> handlers.event(event.type) { body -> event.observe(body) } }
    }
}

/**
 * Receive bpd's own events — the ones [ByBpdEvents.UNDERSTOOD] names.
 *
 * DAP event bodies are open JSON objects and an adapter may name its own events; the platform's
 * client dispatches any event name a handler is registered for, reading its body with the
 * serializer the [EventType] carries. [JsonElement] is that serializer rather than [JsonObject],
 * because a body that does not decode is logged by the platform as an error: an event from a newer
 * bpd this cannot read should cost the report, never put an error in front of the user.
 *
 * Nothing here throws, for the same reason. The handlers run on the connection's reader, and are
 * handed on parsed, which is all that happens there.
 *
 * A list rather than registrations made here, so the same events can be bound to the platform's
 * client in a test as the descriptor binds them to a session, through [ByBpdEvents.register].
 */
internal fun bpdEvents(
    onMoved: (ByMoved) -> Unit,
    onRecomposed: (ByEvent) -> Unit,
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
            LOG.warn("could not read a ${type.event} event", e)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(ByBpdEvent::class.java)
    }
}

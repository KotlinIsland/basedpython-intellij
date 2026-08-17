package dev.basedpython.pycharm.debug

import com.google.gson.JsonObject

/**
 * What a jump or a frame restart really did, as bpd reports it on `bpd/moved`.
 *
 * ## why there is a custom event at all
 *
 * A DAP `stopped` event carries a reason and a sentence, and the two facts a jump produces fit
 * neither: the locals cpython bound to `None` on the way, and the breakpoints on the destination
 * line that will not fire for this pass. bpd used to have nowhere to put them but the console, which
 * is a place a person can read and a client cannot — no gutter icon can be dimmed from a paragraph.
 *
 * DAP itself was never the obstacle. Its event bodies are open JSON objects and an adapter may name
 * its own events; what drops them is a client that deserialises into fixed types. lsp4j binds
 * notifications by reflecting over the **runtime class** of the local service
 * (`GenericEndpoint.recursiveFindRpcMethods` → `service.getClass()`), and the platform hands it the
 * object `DebugAdapterDescriptor.createClient` returns — ours. So an `@JsonNotification` on
 * [ByDapClient] receives whatever bpd sends, untyped, and nothing is lost.
 *
 * ## parsing
 *
 * Read field by field rather than through a Gson-mapped class, for the reason
 * [dev.basedpython.pycharm.debug.dfa.ByDataFlowFacts] is: the shape is bpd's `Jumped` serialised
 * whole, and a class here would be a second copy of a vocabulary that has to agree. Anything missing
 * or of the wrong shape yields null — an event from a newer bpd should cost this feature, never the
 * session.
 */
internal data class ByMoved(
    /** The stop this happened on. */
    val stop: Long,
    /** Where the frame is **now** — after a refusal, where it still is. */
    val file: String?,
    val line: Int?,
    val function: String?,
    /** Null when the move was refused; the line it came from when it happened. */
    val from: Int?,
    /** cpython's own refusal, or null when it moved. */
    val refusal: String?,
    /** The line that was asked for, when it was refused. */
    val wanted: Int?,
    /**
     * Locals that held nothing before the move and hold `None` after it.
     *
     * cpython's doing rather than bpd's: assigning to `f_lineno` binds every unbound local of the
     * frame and says so. Read back out of the frame afterwards, so this is what it really holds.
     */
    val boundToNone: List<String>,
    /**
     * Breakpoints on the destination line that will **not** fire for this pass.
     *
     * No line event is delivered for the line a jump moves to, so a breakpoint bound there is not
     * offered the destination's own execution of it. It is still set, and fires the next time.
     */
    val unannounced: List<Int>,
) {
    /** True when the frame did not move. */
    val refused: Boolean get() = refusal != null

    companion object {

        /** The event name, which is also what [ByDebugProtocolServer.understands] names back. */
        const val EVENT: String = "bpd/moved"

        fun parse(body: JsonObject?): ByMoved? {
            val stop = body?.get("stop")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
            val jumped = body.getAsJsonObject("jumped") ?: return null
            val at = jumped.getAsJsonObject("at")
            val outcome = jumped.getAsJsonObject("outcome")
            return ByMoved(
                stop = stop,
                file = at?.string("file"),
                line = at?.int("line"),
                function = at?.string("function"),
                from = outcome?.int("from"),
                refusal = outcome?.string("error"),
                wanted = outcome?.int("wanted"),
                boundToNone = outcome?.strings("bound_to_none").orEmpty(),
                unannounced = outcome?.ints("unannounced").orEmpty(),
            )
        }

        private fun JsonObject.string(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.int(name: String): Int? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asInt

        private fun JsonObject.strings(name: String): List<String> =
            getAsJsonArray(name)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                .orEmpty()

        private fun JsonObject.ints(name: String): List<Int> =
            getAsJsonArray(name)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asInt }
                .orEmpty()
    }
}

/**
 * What to tell the user about a move, or null when there is nothing worth saying.
 *
 * A move that went where it was asked and disturbed nothing needs no line: the editor caret has
 * already moved there, and narrating an ordinary success is how a console stops being read. What
 * does need saying is the two things a person cannot see — that a breakpoint they are watching will
 * be passed over, and that a local they are about to read holds `None` because of the move rather
 * than because of the program — and a refusal, which looks exactly like a button that did nothing.
 */
internal fun ByMoved.report(): String? = when {
    refused -> "the frame did not move" +
        (wanted?.let { " to line $it" } ?: "") +
        ": ${refusal}. it is still at ${where()}"

    boundToNone.isEmpty() && unannounced.isEmpty() -> null

    else -> buildString {
        append("moved to ${where()}")
        if (unannounced.isNotEmpty()) {
            append(
                "; breakpoint ${unannounced.joinToString(", ")} will not fire for this pass — " +
                    "no line event is delivered for the line a jump moves to, and it is still set",
            )
        }
        if (boundToNone.isNotEmpty()) {
            append(
                "; ${boundToNone.joinToString(", ")} held nothing before the move and " +
                    "hold `None` now, which cpython does to every unbound local of a frame it moves",
            )
        }
    }
}

private fun ByMoved.where(): String =
    listOfNotNull(
        file?.substringAfterLast('/'),
        line?.toString(),
    ).joinToString(":").ifEmpty { "the line it was on" } +
        (function?.let { " in $it" } ?: "")

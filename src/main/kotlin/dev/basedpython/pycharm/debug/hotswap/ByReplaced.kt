package dev.basedpython.pycharm.debug.hotswap

import com.google.gson.JsonObject

/**
 * The `bpd/replaceCode` request body.
 *
 * Field names are the wire format — bpd's DAP adapter reads them by these names, and answers a
 * misspelling with a refusal naming what it needed — so a rename here is a request it will not
 * understand.
 */
data class ByReplaceCodeArguments(
    /** The file whose code to replace, on the debuggee's own filesystem. */
    val file: String,
    /**
     * Whether to apply the replacement even where a frame is running the code being replaced.
     *
     * Left off, which is bpd's default and bpd's guarantee: a replacement made under a live frame
     * leaves the process running two versions of one function until that frame returns, and a
     * stack whose frames behave two different ways is evidence about neither. Sent explicitly
     * rather than omitted so that the choice is visible at the one place it is made.
     */
    val evenUnderALiveFrame: Boolean = false,
)

/**
 * What a code replacement did to the process, as bpd answers `bpd/replaceCode`.
 *
 * ## parsing
 *
 * Read field by field rather than through a Gson-mapped class, for the reason
 * [dev.basedpython.pycharm.debug.ByMoved] is: the shape is bpd's `Replaced` serialised whole, and a
 * class here would be a second copy of a vocabulary that has to agree. Anything missing or of the
 * wrong shape yields null — an answer from a newer bpd should cost this feature, never the session.
 *
 * ## why the refusals are not read
 *
 * bpd's `Unreplaceable` names eleven distinct things that can stand in the way, each with its own
 * fields, and bpd's DAP adapter already writes every one of them to the `output` stream under
 * category `important` — which this plugin puts where a person cannot miss it (see
 * [dev.basedpython.pycharm.debug.ByAdapterOutput]). Reading them again here would be a second copy
 * of that vocabulary rendering the same sentences twice, which is exactly what
 * [dev.basedpython.pycharm.debug.ByUnderstandsArguments] exists to stop for events.
 *
 * So all this needs from a refusal is *that* it was one — [applied] — and how many reasons there
 * were, which is what tells a caller whether the console is about to explain itself.
 */
internal data class ByReplaced(
    /** The file that was asked about, as it was named. */
    val file: String?,
    /**
     * Whether every function object in the process now runs the code on disk.
     *
     * Read from the tag bpd's `Replacement` serialises with, rather than inferred from whether some
     * other key parsed — that is how a shape change becomes a silent wrong answer instead of a
     * missing one.
     */
    val applied: Boolean,
    /**
     * The code objects that moved, one entry each.
     *
     * Empty on an applied replacement means the file on disk already **was** what the process is
     * running: nothing needed replacing, which is a different fact from nothing being replaceable
     * and must not be shown as one.
     */
    val changed: List<ByRebound>,
    /** `co_qualname` of the file's functions whose code is unchanged. */
    val unchanged: List<String>,
    /**
     * The lines breakpoints of this file are bound to now, for every breakpoint the replacement
     * rebound.
     *
     * Binding walks down from the file's registered root code object, so after a replacement the
     * old root describes code nothing will execute — bpd swaps it and resolves the whole set again.
     * A breakpoint is a *line of a file*, so an edit above it means the same request now names a
     * different statement, and where it ended up is worth saying rather than leaving to be
     * discovered.
     *
     * The line only, not bpd's `Resolved` whole: the id on it is the one bpd's own breakpoint set
     * uses, which is not the identity the IDE's gutter knows a breakpoint by.
     */
    val rebound: List<Int>,
    /** How many things stood in the way, when it was refused. */
    val refusals: Int,
) {
    companion object {

        /** The value of `Replacement`'s serde tag when the replacement was made. */
        private const val APPLIED = "applied"

        /**
         * Read an answer, or null when it is not one this can use.
         *
         * Total over any JSON, exactly as [dev.basedpython.pycharm.debug.ByMoved.parse] is: every
         * accessor checks the *kind* of what it found rather than merely that something was there,
         * because Gson's `asInt` on a string throws and a debug session must not end because a
         * newer bpd changed a shape.
         */
        fun parse(body: JsonObject?): ByReplaced? {
            val outcome = body?.obj("outcome") ?: return null
            val applied = outcome.string("replaced") == APPLIED
            return ByReplaced(
                file = body.string("file"),
                applied = applied,
                changed = outcome.array("changed")?.mapNotNull { ByRebound.parse(it) }.orEmpty(),
                unchanged = outcome.strings("unchanged"),
                rebound = outcome.array("rebound")
                    ?.mapNotNull { it.obj()?.obj("binding")?.int("line") }.orEmpty(),
                refusals = outcome.array("because")?.size() ?: 0,
            )
        }
    }
}

/** One code object whose code a replacement swapped. */
internal data class ByRebound(
    /** `co_qualname` of the code that was replaced. */
    val function: String,
    /** The line its code began on before. */
    val wasAt: Int?,
    /** The line its code begins on now. */
    val nowAt: Int?,
    /**
     * How many function objects in the process were running that code.
     *
     * One for an ordinary function. More when a decorator kept the original, when a closure factory
     * handed several out, or when the same function is bound under two names — every one of them
     * was rebound, and that is the fact a namespace walk would have missed.
     */
    val objects: Int?,
) {
    companion object {
        fun parse(element: com.google.gson.JsonElement): ByRebound? {
            val entry = element.obj() ?: return null
            val function = entry.string("function") ?: return null
            return ByRebound(
                function = function,
                wasAt = entry.int("was_at"),
                nowAt = entry.int("now_at"),
                objects = entry.int("objects"),
            )
        }
    }
}

/**
 * What to tell the user about a replacement, or null when there is nothing worth saying.
 *
 * Null for a refusal: bpd writes every reason to the `output` stream itself, as its own sentence,
 * and this plugin shows that stream where a person cannot miss it. A line here would be the same
 * fact a second time, in worse words.
 *
 * Everything else is said, including the replacement that changed nothing — a user who pressed a
 * button and saw no account of it has been told the process is now what they are reading, which on
 * that path happens to be true and on every other path would be a guess.
 */
internal fun ByReplaced.report(): String? {
    if (!applied) return null
    val what = file?.substringAfterLast('/') ?: "the file"
    if (changed.isEmpty()) {
        return "$what already was the code the process is running; nothing needed replacing"
    }
    return buildString {
        append("replaced the code of ")
        append(changed.joinToString(", ") { it.describe() })
        append(" in $what")
        if (unchanged.isNotEmpty()) {
            append("; ${unchanged.size} other ${if (unchanged.size == 1) "function" else "functions"} of it did not move")
        }
        if (rebound.isNotEmpty()) {
            append(
                "; ${rebound.size} ${if (rebound.size == 1) "breakpoint" else "breakpoints"} of it " +
                    "bound again, now at line ${rebound.sorted().joinToString(", ")} — an edit " +
                    "above a breakpoint means the same request names a different statement",
            )
        }
    }
}

/** One replaced function, as a person reads it: what moved, from where to where, and how many held it. */
private fun ByRebound.describe(): String = buildString {
    append('`')
    append(function)
    append('`')
    if (wasAt != null && nowAt != null && wasAt != nowAt) append(" (line $wasAt is now $nowAt)")
    if (objects != null && objects > 1) append(" — $objects function objects held it")
}

// The same total accessors [dev.basedpython.pycharm.debug.ByMoved] reads with, over the same
// question: what kind of thing is under this name, if anything is.

private fun com.google.gson.JsonElement.obj() = takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.primitive(name: String) =
    get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.string(name: String) = primitive(name)?.takeIf { it.isString }?.asString

private fun JsonObject.int(name: String) = primitive(name)?.takeIf { it.isNumber }?.asInt

private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.strings(name: String): List<String> =
    array(name)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }
        .orEmpty()

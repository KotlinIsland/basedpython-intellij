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
    /**
     * The files whose code to replace, on the debuggee's own filesystem.
     *
     * A list, and bpd applies it at once or not at all: every refusal of every file is collected
     * before anything is written. That is the rule one file already had — a process half way between
     * two versions produces evidence about neither — one level up, and it is what re-staging a
     * basedpython build needs, because one edit can change the python emitted for several modules.
     */
    val files: List<String>,
    /**
     * Whether `_by_sourcemap.py` was rewritten beside the code being replaced.
     *
     * Set for every re-stage, which is every hot reload of a `by run` session: the map moved, so the
     * generated lines every `.by` breakpoint is armed on came out of a table that no longer
     * describes the tree. bpd reads it again, installs it and translates the whole breakpoint set
     * through it **in this same message**, before any `__code__` is assigned — the agent holds the
     * GIL for the whole of one message and no longer, so anything split across two would leave a
     * window in which another thread's logpoint is mapped through the old table.
     */
    val remap: Boolean = false,
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
    /** What became of each file that was named, in the order bpd answered about them. */
    val files: List<ByReplacedFile>,
    /** What the map reload installed, when one was asked for and happened. */
    val remapped: ByRemapped?,
    /**
     * The lines breakpoints are bound to now, for every breakpoint the replacement rebound.
     *
     * Of the whole build rather than of one file, which is what it became when a replacement could
     * carry several: binding walks down from each file's registered root code object, so a
     * replacement that swapped several roots resolved every breakpoint of the build again.
     *
     * A breakpoint is a *line of a file*, so an edit above one means the same request now names a
     * different statement, and where it ended up is worth saying rather than leaving to be found.
     *
     * The line only, not bpd's `Resolved` whole: the id on it is the one bpd's own breakpoint set
     * uses, which is not the identity the IDE's gutter knows a breakpoint by.
     */
    val rebound: List<Int>,
) {
    /**
     * Whether the process now runs the code on disk for **every** file that was named.
     *
     * All of them, because bpd applies the set that way: one refusal anywhere leaves the process
     * untouched, so a partial reading of this would be a claim no state of the process matches.
     */
    val applied: Boolean get() = files.isNotEmpty() && files.all { it.applied }

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
            val answered = body?.array("files") ?: return null
            return ByReplaced(
                files = answered.mapNotNull { ByReplacedFile.parse(it) },
                remapped = body.obj("remapped")?.let { ByRemapped.parse(it) },
                rebound = body.array("rebound")
                    ?.mapNotNull { it.obj()?.obj("binding")?.int("line") }
                    .orEmpty(),
            )
        }

        /** The tag test, shared by the one place that reads it. */
        internal fun wasApplied(outcome: JsonObject?): Boolean =
            outcome?.string("replaced") == APPLIED
    }
}

/** What became of one file of a replacement. */
internal data class ByReplacedFile(
    /** The file that was asked about, as it was named. */
    val file: String?,
    /** Whether every function object in the process now runs the code on disk for it. */
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
    /** How many things stood in the way, when it was refused. */
    val refusals: Int,
) {
    companion object {
        fun parse(element: com.google.gson.JsonElement): ByReplacedFile? {
            val entry = element.obj() ?: return null
            val outcome = entry.obj("outcome")
            return ByReplacedFile(
                file = entry.string("file"),
                applied = ByReplaced.wasApplied(outcome),
                changed = outcome?.array("changed")?.mapNotNull { ByRebound.parse(it) }.orEmpty(),
                unchanged = outcome?.strings("unchanged").orEmpty(),
                refusals = outcome?.array("because")?.size() ?: 0,
            )
        }
    }
}

/**
 * What reading the build's map again installed.
 *
 * Worth reporting because nothing else says it and because it is the half a user cannot see: the
 * tables moved under every `.by` line of the build a moment before any code was replaced.
 */
internal data class ByRemapped(val files: Int, val breakpoints: Int) {
    companion object {
        fun parse(entry: JsonObject): ByRemapped? {
            val files = entry.int("files") ?: return null
            val breakpoints = entry.int("breakpoints") ?: return null
            return ByRemapped(files, breakpoints)
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
    return buildString {
        remapped?.let {
            append(
                "read the build's source map again — ${it.files} file(s) in it, " +
                    "${it.breakpoints} breakpoint(s) translated through it",
            )
            append('\n')
        }
        files.joinTo(this, "\n") { it.report() }
        if (rebound.isNotEmpty()) {
            append(
                "\n${rebound.size} ${if (rebound.size == 1) "breakpoint" else "breakpoints"} of " +
                    "the build bound again, now at line ${rebound.sorted().joinToString(", ")} — " +
                    "an edit above a breakpoint means the same request names a different statement",
            )
        }
    }
}

/** What one file's replacement changed, as a person reads it. */
private fun ByReplacedFile.report(): String {
    val what = file?.substringAfterLast('/') ?: "the file"
    if (changed.isEmpty()) {
        return "$what already was the code the process is running; nothing needed replacing"
    }
    return buildString {
        append("replaced the code of ")
        append(changed.joinToString(", ") { it.describe() })
        append(" in $what")
        if (unchanged.isNotEmpty()) {
            append(
                "; ${unchanged.size} other ${if (unchanged.size == 1) "function" else "functions"} " +
                    "of it did not move",
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

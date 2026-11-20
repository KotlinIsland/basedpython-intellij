package dev.basedpython.pycharm.debug.hotswap

import dev.basedpython.pycharm.debug.array
import dev.basedpython.pycharm.debug.int
import dev.basedpython.pycharm.debug.obj
import dev.basedpython.pycharm.debug.objOrNull
import dev.basedpython.pycharm.debug.string
import dev.basedpython.pycharm.debug.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    /**
     * What `by retranspile` answered for each edited `.by` of a program run in place, each as `by`
     * wrote it: the module's new transpile in the run directory and its entry of the map's tables.
     *
     * A run in place has no tree to restage into, and its map is never rewritten, since every python
     * the program starts reads it; so the new transpile comes with the request, and bpd checks each
     * answer against disk as it checks the map before it takes the place of the `.by`'s entry. A
     * `.by` named in [files] without its answer is refused, since the code running is the transpile
     * the run made. Left out when empty, which is every staged reload.
     */
    val retranspiled: List<JsonObject> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("files") { files.forEach { add(it) } }
        put("remap", remap)
        put("evenUnderALiveFrame", evenUnderALiveFrame)
        if (retranspiled.isNotEmpty()) putJsonArray("retranspiled") { retranspiled.forEach { add(it) } }
    }
}

/**
 * What a code replacement did to the process, as bpd answers `bpd/replaceCode`.
 *
 * ## parsing
 *
 * Read field by field rather than through a mapped class, for the reason
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
 * the launch's `understands` ([dev.basedpython.pycharm.debug.ByBpdEvents.UNDERSTOOD]) exists to
 * stop for events.
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
         * because a debug session must not end because a newer bpd changed a shape.
         */
        fun parse(body: JsonObject?): ByReplaced? {
            val answered = body?.array("files") ?: return null
            return ByReplaced(
                files = answered.mapNotNull { ByReplacedFile.parse(it) },
                remapped = body.obj("remapped")?.let { ByRemapped.parse(it) },
                rebound = body.array("rebound")
                    ?.mapNotNull { it.objOrNull()?.obj("binding")?.int("line") }
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
    /** bpd's sentence for each refusal, to show the user as it wrote them. */
    val said: List<String> = emptyList(),
) {
    companion object {
        fun parse(element: JsonElement): ByReplacedFile? {
            val entry = element.objOrNull() ?: return null
            val outcome = entry.obj("outcome")
            return ByReplacedFile(
                file = entry.string("file"),
                applied = ByReplaced.wasApplied(outcome),
                changed = outcome?.array("changed")?.mapNotNull { ByRebound.parse(it) }.orEmpty(),
                unchanged = outcome?.strings("unchanged").orEmpty(),
                refusals = outcome?.array("because")?.size ?: 0,
                said = entry.strings("said"),
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
        fun parse(element: JsonElement): ByRebound? {
            val entry = element.objOrNull() ?: return null
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

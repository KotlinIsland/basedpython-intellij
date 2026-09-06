package dev.basedpython.pycharm.debug.recompose

/**
 * Each cause as one sentence a person reads in a tree row or a margin label.
 *
 * Pure, so the spelling is testable without a tree. The vocabulary is fixed here and nowhere else:
 * the tool window and the editor label say the same thing about the same cause, which is what lets
 * someone read one and find it in the other.
 *
 * Values are shown exactly as bpd rendered them (`2`, `'abc'`, `list[3]`, `a Todo`) — this never
 * had the program object and must not pretend to.
 */
internal object ByCauseSentences {

    /** What is shown for a value slot the wire did not fill. */
    private const val UNKNOWN_VALUE = "?"

    fun sentence(cause: ByCause): String = when (cause) {
        ByCause.Created -> "created"
        ByCause.Invalidated -> "invalidated by hand, with no cause"
        ByCause.Inline -> "takes a content block, so it runs whenever its parent runs"
        ByCause.Uncommitted -> "not committed yet"
        is ByCause.Args ->
            if (cause.compared) {
                "${cause.parameter} ${cause.old.orUnknown()} → ${cause.new.orUnknown()}"
            } else {
                "${cause.parameter}: ${cause.new.orUnknown()} is unstable, never compared"
            }

        is ByCause.Recovery -> "the previous run raised ${cause.error}"
        is ByCause.Dirty -> "already dirty: " + cause.causes.joinToString("; ") { sentence(it) }
        is ByCause.State -> state(cause)
        is ByCause.Derived -> derived(cause)
    }

    /**
     * `count 0 → 2, set at counter.by:14`, then `, posted from thread N` for a write that came from
     * another thread, then `, nothing depends on it` when no tracker was notified.
     */
    private fun state(cause: ByCause.State): String = buildString {
        append(target(cause))
        append(' ').append(cause.old.orUnknown()).append(" → ").append(cause.new.orUnknown())
        append(", ").append(cause.op).append(" at ").append(site(cause.written))
        if (cause.posted) {
            append(", posted from thread ").append(cause.thread?.toString() ?: UNKNOWN_VALUE)
        }
        if (cause.readers == 0) append(", nothing depends on it")
    }

    /** `total 1 → 2 because count 0 → 2, set at counter.by:14`. */
    private fun derived(cause: ByCause.Derived): String = buildString {
        append(cause.declaredName ?: "a derived")
        append(' ').append(cause.old.orUnknown()).append(" → ").append(cause.new.orUnknown())
        if (!cause.changed) append(", compared equal")
        append(" because ").append(sentence(cause.because))
    }

    /** The cell as the program named it, with the index or key an op touched: `items[2]`. */
    private fun target(cause: ByCause.State): String {
        val name = cause.declaredName ?: "a ${cause.kind} cell"
        return cause.at?.let { "$name[${it.render()}]" } ?: name
    }

    /** `counter.by:14` — the file name alone, since the row is not the place to read a path. */
    fun site(location: ByTraceLocation): String =
        "${location.file.substringAfterLast('/').substringAfterLast('\\')}:${location.line}"

    /**
     * Why a location kept its generated place, in one clause for a tooltip.
     *
     * Shorter than bpd's own `Display`, which writes a paragraph ending in what to do about it: that
     * is the right length for a console line and the wrong one for an aside beside a place. The fact
     * is the same one, and the file names are left out where the tooltip has already named the file.
     *
     * A reason a newer bpd grew is written as its tag with the underscores taken out, which says
     * more than nothing and never claims a meaning this build does not have.
     */
    fun unmapped(reason: ByUnmapped): String = when (reason) {
        is ByUnmapped.NotInTheMap -> "the source map says nothing about this file"
        is ByUnmapped.PastTheEnd ->
            "line ${reason.line} is past the ${reason.covered} lines the map covers — " +
                "the file that was generated is not the file being read"

        is ByUnmapped.NoSourceLine ->
            "the transpiler emitted this line and no line of " +
                "${reason.source.substringAfterLast('/').substringAfterLast('\\')} is behind it"

        is ByUnmapped.NoGeneratedLine ->
            "nothing was generated for line ${reason.requested}, or for any line after it" +
                (reason.lastMapped?.let { ", the last being line $it" } ?: "")

        is ByUnmapped.Unknown ->
            if (reason.tag.isEmpty()) "unmapped, for a reason this build cannot read" else reason.tag.replace('_', ' ')
    }

    private fun String?.orUnknown(): String = this ?: UNKNOWN_VALUE
}

/** Nanoseconds as a person reads them: `300 ns`, `12 µs`, `1.2 ms`, `2.5 s`. */
internal object ByDurations {
    fun render(ns: Long): String = when {
        ns < 1_000L -> "$ns ns"
        ns < 1_000_000L -> "${ns / 1_000L} µs"
        ns < 1_000_000_000L -> "${oneDecimal(ns / 1_000_000.0)} ms"
        else -> "${oneDecimal(ns / 1_000_000_000.0)} s"
    }

    private fun oneDecimal(value: Double): String {
        val tenths = Math.round(value * 10)
        return "${tenths / 10}.${tenths % 10}"
    }
}

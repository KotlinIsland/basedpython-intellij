package dev.basedpython.pycharm.debug.recompose

/** What a row of the Recompositions tree is, which decides its icon and how it is written. */
internal enum class ByRowKind { FRAME, RUN, CAUSE, SKIPPED, DISPOSED, WRITE, ERROR, REFUSED, NOTE }

/**
 * One row of the Recompositions tree, with nothing Swing in it.
 *
 * @param key stable across rebuilds, so what the user had expanded and selected survives a refresh
 * @param text the row's own words
 * @param detail what follows in grey, or null
 * @param target where *Jump to Source* goes: the write site of a state cause, the composable's
 *   definition for a run
 * @param callSite for a run, where the parent called it — the second navigation
 * @param cause for a cause row, the cause itself, so a renderer can pick an icon by its kind
 */
internal data class ByTreeRow(
    val kind: ByRowKind,
    val key: String,
    val text: String,
    val detail: String? = null,
    val target: ByTraceLocation? = null,
    val callSite: ByTraceLocation? = null,
    val cause: ByCause? = null,
    val children: List<ByTreeRow> = emptyList(),
) {
    /** What speed search matches against, and what a `DefaultMutableTreeNode` shows by default. */
    override fun toString(): String = if (detail == null) text else "$text $detail"
}

/** A label for the margin of a composable's definition line: `ran ×2 · count 0 → 2, set at counter.by:14`. */
internal data class ByMarginLabel(val file: String, val line: Int, val text: String)

/**
 * Records in, rows out — the whole shape of the Recompositions tree, as a pure function.
 *
 * ## the shape
 *
 * frame → run (name, origin, elapsed, key) → causes → skipped / disposed. Newest frame first,
 * because the question is nearly always "why did that just happen", and within a frame the order
 * things happened in.
 *
 * A write record is shown under its frame only when no run **in that frame** names its cause. A
 * write that made something run is already there as that run's cause, and listing it twice is
 * noise; the one that made nothing run — `readers == 0`, the record a reader uses to say "nothing
 * depends on this" — has nowhere else to be seen, and is exactly the write worth seeing. The
 * frame is the unit because the protocol promises that a write and the run it caused share one:
 * an equal write in an earlier frame (the same cell set to the same values from the same site)
 * says nothing about this one, and a write in the current frame whose run has not happened yet — a
 * stop in the middle of a handler — is the write the user stopped to see.
 *
 * Errors and refusals are rows under their frame, marked. A gap the stream dropped is a note at
 * the place it happened.
 */
internal object ByRecompositionTree {

    fun rows(records: List<ByRecord>): List<ByTreeRow> {
        if (records.isEmpty()) return emptyList()
        val runtimes = records.mapTo(HashSet()) { it.runtime }
        val byFrame = LinkedHashMap<Pair<Int, Long>, MutableList<ByRecord>>()
        for (record in records) byFrame.getOrPut(record.runtime to record.frame) { ArrayList() } += record

        return byFrame.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, Long>, List<ByRecord>>> { it.key.second }.thenBy { it.key.first })
            .map { (id, frameRecords) -> frameRow(id.first, id.second, frameRecords, runtimes.size > 1) }
    }

    /**
     * What bpd's stream dropped, summed over every [ByRecord.Gap] held — the part of what is
     * missing that the last pull does not know about, since a pull fills the gaps of the frames
     * it carries and those gaps are gone by then.
     */
    fun droppedInStream(records: List<ByRecord>): Long = records.sumOf { (it as? ByRecord.Gap)?.dropped ?: 0L }

    /**
     * The margin labels for the latest frame of every runtime, one per composable definition.
     *
     * Counted per definition rather than per scope: a `Row` composable called five times ran five
     * scopes, and the line it is defined on gets `ran ×5`. The sentence is the first cause of the
     * first of those runs — one reason, readable in a margin; the rest are in the window.
     */
    fun labels(records: List<ByRecord>): List<ByMarginLabel> {
        val runs = records.filterIsInstance<ByRecord.Run>()
        if (runs.isEmpty()) return emptyList()
        val latest = runs.groupBy { it.runtime }.mapValues { (_, r) -> r.maxOf { it.frame } }
        val grouped = LinkedHashMap<Pair<String, Int>, MutableList<ByRecord.Run>>()
        for (run in runs) {
            if (run.frame != latest[run.runtime]) continue
            val defined = run.defined ?: continue
            grouped.getOrPut(defined.file to defined.line) { ArrayList() } += run
        }
        return grouped.entries
            .sortedWith(compareBy<Map.Entry<Pair<String, Int>, List<ByRecord.Run>>> { it.key.first }.thenBy { it.key.second })
            .map { (place, group) ->
                val first = group.first().causes.firstOrNull()?.let(ByCauseSentences::sentence)
                ByMarginLabel(
                    file = place.first,
                    line = place.second,
                    text = "ran ×${group.size}" + (first?.let { " · $it" } ?: ""),
                )
            }
    }

    /** Every cause a run in [records] carries, flattened through `dirty` and `derived`. */
    private fun causesOfRuns(records: List<ByRecord>): Set<ByCause> {
        val found = HashSet<ByCause>()
        fun walk(cause: ByCause) {
            found += cause
            when (cause) {
                is ByCause.Dirty -> cause.causes.forEach(::walk)
                is ByCause.Derived -> walk(cause.because)
                else -> Unit
            }
        }
        for (record in records) if (record is ByRecord.Run) record.causes.forEach(::walk)
        return found
    }

    private fun frameRow(
        runtime: Int,
        frame: Long,
        records: List<ByRecord>,
        severalRuntimes: Boolean,
    ): ByTreeRow {
        val prefix = "$runtime:$frame"
        // This frame's runs and no other's: see the class comment
        val explained = causesOfRuns(records)
        val summary = records.filterIsInstance<ByRecord.Frame>().lastOrNull()
        val runs = records.count { it is ByRecord.Run }
        val detail = if (summary != null) {
            "${count(summary.runs, "run")} · ${count(summary.skips, "skip")} · " +
                "compose ${ByDurations.render(summary.composeNs)} · commit ${ByDurations.render(summary.commitNs)}"
        } else {
            count(runs, "run")
        }
        val children = ArrayList<ByTreeRow>()
        var writes = 0
        var gaps = 0
        for (record in records) {
            when (record) {
                is ByRecord.Run -> children += runRow(prefix, record)
                is ByRecord.Write -> if (record.cause !in explained) {
                    children += ByTreeRow(
                        kind = ByRowKind.WRITE,
                        key = "$prefix:write:${writes++}",
                        text = ByCauseSentences.sentence(record.cause),
                        target = targetOf(record.cause),
                        cause = record.cause,
                    )
                }

                is ByRecord.Error -> children += ByTreeRow(
                    kind = ByRowKind.ERROR,
                    key = "$prefix:error:${record.scope}",
                    text = "${record.name} raised ${record.error}",
                    detail = if (record.keptPrevious) "the previous frame stayed on screen" else "nothing stayed on screen",
                )

                is ByRecord.Refused -> children += ByTreeRow(
                    kind = ByRowKind.REFUSED,
                    key = "$prefix:refused:${record.scope}",
                    text = "${record.name}: a ${record.what} during composition was refused",
                )

                is ByRecord.Gap -> children += ByTreeRow(
                    kind = ByRowKind.NOTE,
                    key = "$prefix:gap:${gaps++}",
                    text = gap(record.dropped),
                )

                is ByRecord.Frame -> Unit
            }
        }
        return ByTreeRow(
            kind = ByRowKind.FRAME,
            key = "$prefix:frame",
            text = (if (severalRuntimes) "runtime $runtime · " else "") + "frame $frame",
            detail = detail,
            children = children,
        )
    }

    private fun runRow(prefix: String, run: ByRecord.Run): ByTreeRow {
        val key = "$prefix:run:${run.scope}"
        val children = ArrayList<ByTreeRow>()
        run.causes.forEachIndexed { index, cause ->
            children += ByTreeRow(
                kind = ByRowKind.CAUSE,
                key = "$key:cause:$index",
                text = ByCauseSentences.sentence(cause),
                target = targetOf(cause),
                cause = cause,
            )
        }
        if (run.skipped.isNotEmpty()) {
            children += ByTreeRow(
                kind = ByRowKind.SKIPPED,
                key = "$key:skipped",
                text = "skipped ${run.skipped.joinToString(", ")}",
                detail = "emitted as references, not run",
            )
        }
        for (child in run.disposed) {
            children += ByTreeRow(
                kind = ByRowKind.DISPOSED,
                key = "$key:disposed:${child.scope}",
                text = "disposed ${child.name}" + (child.key?.let { " key ${it.render()}" } ?: ""),
                detail = "scope ${child.scope}",
            )
        }
        val detail = listOfNotNull(
            run.origin,
            run.elapsedNs?.let(ByDurations::render),
            run.key?.let { "key ${it.render()}" },
        ).joinToString(" · ")
        return ByTreeRow(
            kind = ByRowKind.RUN,
            key = key,
            text = run.name,
            detail = detail,
            target = run.defined,
            callSite = run.called,
            children = children,
        )
    }

    /** Where a cause points: the write site of a state, the declaration of a derived, the write behind a `dirty`. */
    private fun targetOf(cause: ByCause): ByTraceLocation? = when (cause) {
        is ByCause.State -> cause.written
        is ByCause.Derived -> cause.declared ?: targetOf(cause.because)
        is ByCause.Dirty -> cause.causes.firstNotNullOfOrNull(::targetOf)
        else -> null
    }

    /** `3 records were dropped before this one because the program outran the debugger` — the row sits before the record. */
    private fun gap(dropped: Long): String =
        (if (dropped == 1L) "1 record was" else "$dropped records were") +
            " dropped before this one because the program outran the debugger"

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
}

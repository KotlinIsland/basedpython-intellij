package dev.basedpython.pycharm.debug.recompose

/**
 * A pull's records folded into what a watch had already appended, without doubling.
 *
 * A watch appends records as they happen; a pull at a stop answers with the whole ring, which holds
 * those same records again. The ring is the truth for every frame it still holds, so **the pull is
 * authoritative for every frame it carries**: what was held survives only for frames older than
 * the pull's oldest — records that have fallen off the front of the ring since the watch saw them,
 * which the pull can no longer return and this can still show.
 *
 * Whole frames, never positions. The ring's halving and bpd's 4096 cap both cut wherever they
 * fall, so the pull's oldest frame is usually a partial one, and a record matched by its ordinal
 * among its neighbours would be the wrong record — the fallen write discarded and a surviving one
 * doubled. A held record in that frame that the pull no longer carries is one the ring has let go,
 * and the merge follows the ring.
 *
 * Per runtime: `frame` counts per runtime, and a program with two runtimes has two rings. Held
 * records of a runtime the pull does not mention at all are kept — that runtime has been disposed,
 * or answers nothing, and either way the pull has nothing newer to say about it.
 */
internal object ByRecordMerge {

    fun merge(held: List<ByRecord>, pulled: List<ByRecord>): List<ByRecord> {
        if (held.isEmpty()) return pulled
        if (pulled.isEmpty()) return held
        val oldest = HashMap<Int, Long>()
        for (record in pulled) oldest.merge(record.runtime, record.frame) { a, b -> minOf(a, b) }
        val kept = held.filter { record ->
            val floor = oldest[record.runtime]
            floor == null || record.frame < floor
        }
        if (kept.isEmpty()) return pulled
        return kept + pulled
    }
}

package dev.basedpython.pycharm.lsp.inlay

/**
 * Reading a doubled hint — `def f() → 1 → 1:` — back to whichever of three things drew it twice.
 *
 * The three are indistinguishable on screen and only one of them is the platform's fault, so
 * guessing between them is exactly what this exists to stop:
 *
 *  1. **The collector ran twice in one pass.** `InlayHintsPass` flattens the file with `Divider` and
 *     pushes every element through *one* collector instance with
 *     `JobLauncher.invokeConcurrentlyUnderProgress` — several pool threads, the same object. A
 *     collector that asks `by` once and remembers having asked (which [ByInlayHintsCollector] must,
 *     or a `.by` file would mean one request per token) is racing unless that memory is atomic, and
 *     two threads that both get through it both add the whole file's hints. The sink keeps a *list*
 *     per offset, `InlineInlayRenderer` draws that list end to end, and every hint in the file comes
 *     out twice.
 *  2. **`by` sent the hint twice** in one reply.
 *  3. **The platform is holding an inlay this pass never handed it.**
 *
 * None of it can be told after the fact: by the time the doubling is on screen the presentations
 * carry no history, and the next pass quietly drops the extra (`InlayHintsUtils.produceUpdatedRootList`
 * keeps as many as the new list has). So the collector records what it added and which run of itself
 * added it, and the audit reads that against what the editor is actually showing — see
 * [ByInlayAuditLog].
 *
 * Pure on purpose: everything here is offsets and strings, so the verdicts are testable without an
 * editor, and the one thing that needs a live editor (reading the inlays) sits in the service.
 */
object ByInlayAudit {

    /** One hint as the collector handed it to the platform's sink. */
    data class Collected(
        val offset: Int,
        /** As drawn, so it can be looked for in what a renderer says it is showing. */
        val text: String,
        /** Which run of the collector added it — 1 for the first, and there should never be a 2nd. */
        val run: Int,
        /** Where it sat in that run's reply from `by`, which is what separates a resend from a rerun. */
        val index: Int,
        val thread: String,
    )

    /** One inlay the editor is showing, as its own renderer describes itself. */
    data class Drawn(val offset: Int, val rendered: String)

    /** What one pass of the collector did. A doubling is judged against this. */
    data class Pass(
        /** Counts collector instances, and so passes: two ids for one daemon run is itself a finding. */
        val id: Long,
        val file: String,
        val docStamp: Long,
        /** How many times [ByInlayHintsCollector] got past its once-guard in this pass. */
        val runs: Int,
        val collected: List<Collected>,
    )

    /** Which of the three did it. */
    enum class Cause {
        /** Two runs of the collector in one pass — the guard leaked, and both replies were added. */
        COLLECTED_TWICE,

        /** One run, two hints at one position — `by` sent it twice. */
        SENT_TWICE,

        /** Handed over once, drawn more than once — the platform kept something of its own. */
        DRAWN_TWICE,
    }

    /** One hint that is on screen more times than it should be. */
    data class Doubling(
        val offset: Int,
        val text: String,
        val collected: Int,
        val drawn: Int,
        val runs: List<Int>,
        val threads: List<String>,
        val cause: Cause,
    ) {
        /** The finding in one sentence, naming the evidence rather than restating the symptom. */
        val explanation: String
            get() = when (cause) {
                Cause.COLLECTED_TWICE ->
                    "the collector asked `by` ${runs.size} times in one pass " +
                        "(runs ${runs.joinToString(", ")} on ${threads.joinToString(", ")}) and added every reply"
                Cause.SENT_TWICE ->
                    "one run of the collector, $collected hints at this position — `by` sent it more than once"
                Cause.DRAWN_TWICE ->
                    "collected $collected time(s), drawn $drawn — the platform is holding an inlay this pass did not hand it"
            }
    }

    /**
     * Every hint that is doubled, and why.
     *
     * [drawn] is only consulted for the third cause: the first two are settled by what the collector
     * added, and are findings whether or not the editor has caught up with them yet. A hint drawn
     * *fewer* times than it was collected is not reported — an inlay can be waiting for a repaint,
     * or sitting at an offset an edit has since moved, and neither is this bug.
     */
    fun doublings(collected: List<Collected>, drawn: List<Drawn>): List<Doubling> {
        val renderedAt: Map<Int, List<String>> = drawn.groupBy({ it.offset }, { it.rendered })
        return collected
            .groupBy { it.offset to it.text }
            .mapNotNull { (position, hints) ->
                val (offset, text) = position
                val drawnTimes = renderedAt[offset].orEmpty().sumOf { occurrences(it, text) }
                val runs = hints.map { it.run }.distinct().sorted()
                val cause = when {
                    hints.size > 1 && runs.size > 1 -> Cause.COLLECTED_TWICE
                    hints.size > 1 -> Cause.SENT_TWICE
                    drawnTimes > hints.size -> Cause.DRAWN_TWICE
                    else -> return@mapNotNull null
                }
                Doubling(
                    offset = offset,
                    text = text,
                    collected = hints.size,
                    drawn = drawnTimes,
                    runs = runs,
                    threads = hints.map { it.thread }.distinct(),
                    cause = cause,
                )
            }
            .sortedBy { it.offset }
    }

    /**
     * The whole record as one block of text, findings first and then both raw lists.
     *
     * Everything, not just the findings: a report that only says what is wrong is unreadable the one
     * time the answer is in what looks right, and this is written to be pasted somewhere by someone
     * who has just watched the bug happen.
     */
    fun report(pass: Pass?, drawn: List<Drawn>, doublings: List<Doubling>): String = buildString {
        if (pass == null) {
            appendLine("basedpython inlay hints — no pass recorded for this editor yet")
        } else {
            append("basedpython inlay hints — ")
            appendLine(
                when (doublings.size) {
                    0 -> "nothing doubled in ${pass.file}"
                    1 -> "1 doubled hint in ${pass.file}"
                    else -> "${doublings.size} doubled hints in ${pass.file}"
                },
            )
            appendLine(
                "  pass ${pass.id}, doc stamp ${pass.docStamp}, " +
                    "${pass.runs} run(s) of the collector, ${pass.collected.size} hint(s) collected",
            )
        }

        for (doubling in doublings) {
            appendLine("  offset ${doubling.offset} \"${doubling.text}\": ${doubling.explanation}")
        }

        appendLine("  collected")
        if (pass == null || pass.collected.isEmpty()) {
            appendLine("    (none)")
        } else {
            for (hint in pass.collected) {
                appendLine(
                    "    offset ${hint.offset}  run ${hint.run} #${hint.index}  " +
                        "\"${hint.text}\"  [${hint.thread}]",
                )
            }
        }

        appendLine("  drawn")
        if (drawn.isEmpty()) {
            appendLine("    (none)")
        } else {
            for (inlay in drawn) appendLine("    offset ${inlay.offset}  ${inlay.rendered}")
        }
    }

    /** How many times [what] occurs in [text], counting non-overlapping matches. */
    private fun occurrences(text: String, what: String): Int {
        if (what.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(what, from)
            if (at < 0) return count
            count++
            from = at + what.length
        }
    }
}

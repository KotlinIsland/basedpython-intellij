package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [ByInlayAudit] — the three causes of a doubled hint told apart from offsets
 * and strings, with no editor, pass or server anywhere near it.
 */
class ByInlayAuditTest {

    private fun collected(
        offset: Int,
        text: String,
        run: Int = 1,
        index: Int = 0,
        thread: String = "JobScheduler FJ pool 1/8",
    ) = ByInlayAudit.Collected(offset, text, run, index, thread)

    // region: causes

    @Test
    fun `one hint collected once and drawn once is not a finding`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, "→ 1")),
            listOf(ByInlayAudit.Drawn(7, "→ 1")),
        )
        assertEquals(emptyList<ByInlayAudit.Doubling>(), doublings)
    }

    @Test
    fun `two runs of the collector in one pass is the collector's fault`() {
        // The race this was written for: two pool threads both got past the once-guard and both
        // added the whole file's hints.
        val doublings = ByInlayAudit.doublings(
            listOf(
                collected(7, "→ 1", run = 1, thread = "pool 1/8"),
                collected(7, "→ 1", run = 2, thread = "pool 5/8"),
            ),
            listOf(ByInlayAudit.Drawn(7, "[→ 1 → 1]")),
        )
        assertEquals(1, doublings.size)
        val doubling = doublings.single()
        assertEquals(ByInlayAudit.Cause.COLLECTED_TWICE, doubling.cause)
        assertEquals(listOf(1, 2), doubling.runs)
        assertEquals(listOf("pool 1/8", "pool 5/8"), doubling.threads)
        assertEquals(2, doubling.collected)
        assertEquals(2, doubling.drawn)
    }

    @Test
    fun `one run holding the same hint twice is the server's fault`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, "→ 1", index = 0), collected(7, "→ 1", index = 1)),
            listOf(ByInlayAudit.Drawn(7, "[→ 1 → 1]")),
        )
        assertEquals(ByInlayAudit.Cause.SENT_TWICE, doublings.single().cause)
    }

    @Test
    fun `collected once and drawn twice is the platform's`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, "→ 1")),
            listOf(ByInlayAudit.Drawn(7, "[→ 1 → 1]")),
        )
        assertEquals(ByInlayAudit.Cause.DRAWN_TWICE, doublings.single().cause)
        assertEquals(2, doublings.single().drawn)
    }

    @Test
    fun `two inlays at one offset count as much as one holding two presentations`() {
        // The platform can hold the extra either way: a second presentation inside one renderer, or
        // a second inlay at the same offset. Both are the same finding.
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, "→ 1")),
            listOf(ByInlayAudit.Drawn(7, "→ 1"), ByInlayAudit.Drawn(7, "→ 1")),
        )
        assertEquals(ByInlayAudit.Cause.DRAWN_TWICE, doublings.single().cause)
    }

    // endregion

    // region: what is deliberately not a finding

    @Test
    fun `a hint that has not been drawn yet is not a finding`() {
        // A pass whose inlays the editor has not applied, or offsets an edit has since moved: drawn
        // *fewer* times than collected is not this bug.
        assertEquals(
            emptyList<ByInlayAudit.Doubling>(),
            ByInlayAudit.doublings(listOf(collected(7, "→ 1")), emptyList()),
        )
    }

    @Test
    fun `the same text at two offsets is two hints, not a doubling`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, ": int"), collected(19, ": int")),
            listOf(ByInlayAudit.Drawn(7, ": int"), ByInlayAudit.Drawn(19, ": int")),
        )
        assertEquals(emptyList<ByInlayAudit.Doubling>(), doublings)
    }

    @Test
    fun `two different hints at one offset are not a doubling`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(7, ": int", index = 0), collected(7, " | float", index = 1)),
            listOf(ByInlayAudit.Drawn(7, "[: int  | float]")),
        )
        assertEquals(emptyList<ByInlayAudit.Doubling>(), doublings)
    }

    @Test
    fun `findings come out in offset order`() {
        val doublings = ByInlayAudit.doublings(
            listOf(collected(19, "b"), collected(19, "b"), collected(2, "a"), collected(2, "a")),
            emptyList(),
        )
        assertEquals(listOf(2, 19), doublings.map { it.offset })
    }

    // endregion

    // region: report

    @Test
    fun `the report names the file, the pass and every hint`() {
        val pass = ByInlayAudit.Pass(
            id = 12,
            file = "foo.by",
            docStamp = 34,
            runs = 2,
            collected = listOf(
                collected(7, "→ 1", run = 1, thread = "pool 1/8"),
                collected(7, "→ 1", run = 2, thread = "pool 5/8"),
            ),
        )
        val drawn = listOf(ByInlayAudit.Drawn(7, "[→ 1 → 1]"))
        val report = ByInlayAudit.report(pass, drawn, ByInlayAudit.doublings(pass.collected, drawn))

        assertTrue(report.contains("1 doubled hint in foo.by"), report)
        assertTrue(report.contains("pass 12, doc stamp 34, 2 run(s)"), report)
        assertTrue(report.contains("asked `by` 2 times in one pass"), report)
        assertTrue(report.contains("run 1 #0"), report)
        assertTrue(report.contains("run 2 #0"), report)
        assertTrue(report.contains("[→ 1 → 1]"), report)
    }

    @Test
    fun `a clean report still lists what was collected and drawn`() {
        val pass = ByInlayAudit.Pass(1, "foo.by", 2, runs = 1, collected = listOf(collected(7, "→ 1")))
        val drawn = listOf(ByInlayAudit.Drawn(7, "→ 1"))
        val report = ByInlayAudit.report(pass, drawn, ByInlayAudit.doublings(pass.collected, drawn))

        assertTrue(report.contains("nothing doubled in foo.by"), report)
        assertTrue(report.contains("offset 7"), report)
    }

    @Test
    fun `a report with no pass says so rather than reading as empty`() {
        val report = ByInlayAudit.report(null, emptyList(), emptyList())
        assertTrue(report.contains("no pass recorded"), report)
        assertTrue(report.contains("(none)"), report)
    }

    // endregion
}

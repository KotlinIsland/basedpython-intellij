package dev.basedpython.pycharm.inspections.explain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The command lines behind "Explain Rule".
 *
 * Both were checked against the real binaries. `buff rule F401` prints the linter's docs and
 * rejects `redundant-return-annotation` with "invalid value for '[RULE]'"; `by explain rule
 * redundant-return-annotation` prints the checker's docs and rejects `F401` with "Unknown rule".
 * Two disjoint sets, which is why both get asked.
 */
class ByRuleExplainerTest {

    @Test
    fun `buff takes the code directly`() {
        assertEquals(listOf("rule", "F401"), ByRuleExplainer.buffArguments("F401"))
    }

    /**
     * The bug this guards: `explain` is a command *group*, so `by explain <code>` dies with
     * `error: unrecognized subcommand`. Every `by`-owned rule fell through to "no explanation"
     * because the `rule` command was missing.
     */
    @Test
    fun `by needs the rule command under the explain group`() {
        assertEquals(
            listOf("explain", "rule", "redundant-return-annotation"),
            ByRuleExplainer.byArguments("redundant-return-annotation"),
        )
    }

    @Test
    fun `the code is passed through untouched`() {
        assertEquals("PLR0913", ByRuleExplainer.buffArguments("PLR0913").last())
        assertEquals("unresolved-import", ByRuleExplainer.byArguments("unresolved-import").last())
    }
}

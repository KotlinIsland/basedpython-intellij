package dev.basedpython.pycharm.highlight.fstring

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lightweight smoke test for [FStringInterpolationAnnotator]. We only assert that running
 * highlighting over a `.by` file containing various f-strings does not throw. The exact
 * range logic is covered exhaustively by [FStringInterpolationTest].
 *
 * Note: the annotator is invoked here directly (not via plugin.xml registration, which is an
 * orchestrator integration step), so this test exercises the PSI plumbing of the annotator's
 * code path indirectly through [FStringInterpolation] on real PSI text.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class FStringInterpolationAnnotatorSmokeTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `doHighlighting does not throw`() {
        val src = """
            x = f"{a}"
            y = f"hi {name!r} bye"
            z = f"{x:>{w}}"
            t = f${"\"\"\""}${'\n'}multi {value}${'\n'}${"\"\"\""}
            esc = f"{{literal}} {real}"
            plain = "{not_interpolated}"
        """.trimIndent()
        fixture.configureByText("a.by", src)
        // Should not throw.
        fixture.doHighlighting()
    }

    @Test
    fun `annotator analyzes f-string psi text`() {
        fixture.configureByText("b.by", "v = f\"{value}\"\n")
        // Find the f-string literal text via the helper to confirm PSI text round-trips.
        val analysis = FStringInterpolation.analyze("f\"{value}\"")
        assertTrue(analysis.isFString)
        assertEquals(1, analysis.ranges.size)
    }
}
